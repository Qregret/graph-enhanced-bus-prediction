from __future__ import annotations

import hashlib
import json
import pickle
from datetime import datetime, timezone
from pathlib import Path

import faiss
import numpy as np
from langchain_community.vectorstores import FAISS
from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings
from langchain_openai import OpenAIEmbeddings

from config import Settings


SERVICE_DIR = Path(__file__).resolve().parents[1]
DEFAULT_DOCUMENTS_PATH = SERVICE_DIR / "data" / "processed" / "knowledge_documents.jsonl"
DEFAULT_INDEX_DIR = SERVICE_DIR / "data" / "vector_store"


class LocalFastEmbedEmbeddings(Embeddings):
    def __init__(self, model_name: str, batch_size: int):
        from fastembed import TextEmbedding

        self.model = TextEmbedding(model_name=model_name)
        self.batch_size = batch_size

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        vectors = self.model.embed(texts, batch_size=self.batch_size)
        return [vector.tolist() for vector in vectors]

    def embed_query(self, text: str) -> list[float]:
        return next(self.model.query_embed(text)).tolist()


def create_embeddings(settings: Settings) -> Embeddings:
    if settings.embedding_provider == "local":
        return LocalFastEmbedEmbeddings(
            model_name=settings.local_embedding_model,
            batch_size=settings.embedding_batch_size,
        )
    if settings.embedding_provider == "zhipu":
        return OpenAIEmbeddings(
            model=settings.embedding_model,
            api_key=settings.require_api_key(),
            base_url=settings.base_url,
            chunk_size=settings.embedding_batch_size,
            request_timeout=settings.timeout_seconds,
            max_retries=settings.max_retries,
            check_embedding_ctx_length=False,
        )
    raise ValueError("EMBEDDING_PROVIDER 只支持 local 或 zhipu。")


def load_processed_documents(path: Path = DEFAULT_DOCUMENTS_PATH) -> list[Document]:
    if not path.exists():
        raise FileNotFoundError(f"知识文档不存在，请先执行 build_documents.py：{path}")

    documents: list[Document] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
                documents.append(
                    Document(
                        page_content=record["page_content"],
                        metadata=record["metadata"],
                    )
                )
            except (json.JSONDecodeError, KeyError, TypeError) as exc:
                raise ValueError(f"知识文档第 {line_number} 行格式无效。") from exc

    if not documents:
        raise ValueError("知识文档为空，无法构建索引。")
    return documents


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _save_faiss_unicode_safe(vector_store: FAISS, index_dir: Path) -> None:
    index_bytes = faiss.serialize_index(vector_store.index).tobytes()
    (index_dir / "index.faiss").write_bytes(index_bytes)
    with (index_dir / "index.pkl").open("wb") as handle:
        pickle.dump(
            (vector_store.docstore, vector_store.index_to_docstore_id),
            handle,
        )


def build_and_save_vector_store(
    settings: Settings,
    documents_path: Path = DEFAULT_DOCUMENTS_PATH,
    index_dir: Path = DEFAULT_INDEX_DIR,
    embeddings: Embeddings | None = None,
) -> dict[str, object]:
    documents = load_processed_documents(documents_path)
    embedding_client = embeddings or create_embeddings(settings)
    vector_store = FAISS.from_documents(documents, embedding_client)

    index_dir.mkdir(parents=True, exist_ok=True)
    _save_faiss_unicode_safe(vector_store, index_dir)
    index_faiss = index_dir / "index.faiss"
    index_pickle = index_dir / "index.pkl"
    manifest: dict[str, object] = {
        "index_version": 1,
        "built_at": datetime.now(timezone.utc).isoformat(),
        "document_count": len(documents),
        "embedding_provider": settings.embedding_provider,
        "embedding_model": (
            settings.local_embedding_model
            if settings.embedding_provider == "local"
            else settings.embedding_model
        ),
        "source_file": documents_path.name,
        "source_sha256": file_sha256(documents_path),
        "index_faiss_sha256": file_sha256(index_faiss),
        "index_pickle_sha256": file_sha256(index_pickle),
    }
    with (index_dir / "manifest.json").open("w", encoding="utf-8") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
    return manifest


def load_vector_store(
    settings: Settings,
    index_dir: Path = DEFAULT_INDEX_DIR,
    embeddings: Embeddings | None = None,
) -> FAISS:
    index_faiss = index_dir / "index.faiss"
    index_pickle = index_dir / "index.pkl"
    manifest_path = index_dir / "manifest.json"
    required = [index_faiss, index_pickle, manifest_path]
    missing = [str(path) for path in required if not path.exists()]
    if missing:
        raise FileNotFoundError("FAISS 索引不完整：" + ", ".join(missing))

    with manifest_path.open("r", encoding="utf-8") as handle:
        manifest = json.load(handle)
    expected_hashes = {
        index_faiss: manifest.get("index_faiss_sha256"),
        index_pickle: manifest.get("index_pickle_sha256"),
    }
    for path, expected in expected_hashes.items():
        if not expected or file_sha256(path) != expected:
            raise ValueError(f"FAISS 索引校验失败：{path.name}")

    embedding_client = embeddings or create_embeddings(settings)
    index_data = np.frombuffer(index_faiss.read_bytes(), dtype=np.uint8)
    index = faiss.deserialize_index(index_data)
    with index_pickle.open("rb") as handle:
        docstore, index_to_docstore_id = pickle.load(handle)
    return FAISS(
        embedding_function=embedding_client,
        index=index,
        docstore=docstore,
        index_to_docstore_id=index_to_docstore_id,
    )
