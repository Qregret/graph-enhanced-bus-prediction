from __future__ import annotations

import json
from pathlib import Path

from langchain_core.embeddings import Embeddings

from config import Settings
from retrieval.vector_store import build_and_save_vector_store, load_vector_store


class TinyEmbeddings(Embeddings):
    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return [self.embed_query(text) for text in texts]

    def embed_query(self, text: str) -> list[float]:
        values = [0.0] * 8
        for index, char in enumerate(text.encode("utf-8")):
            values[index % len(values)] += char / 255.0
        return values


def make_settings() -> Settings:
    return Settings(
        api_key="test-key",
        model="glm-4.5-air",
        base_url="https://open.bigmodel.cn/api/paas/v4/",
        default_temperature=0.0,
        default_max_tokens=500,
        timeout_seconds=60,
        max_retries=2,
        embedding_model="test-embedding",
        embedding_batch_size=2,
        embedding_provider="local",
        local_embedding_model="test-local-embedding",
    )


def write_documents(path: Path) -> None:
    rows = [
        {
            "page_content": "宏声桥站客流拥挤，需要评估备用车辆。",
            "metadata": {"sample_id": "TA1", "line_name": "105路"},
        },
        {
            "page_content": "暴雨天气应提醒乘客注意安全。",
            "metadata": {"sample_id": "TA2", "line_name": "206路"},
        },
    ]
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def test_build_save_and_load_faiss_index(tmp_path):
    documents_path = tmp_path / "knowledge.jsonl"
    index_dir = tmp_path / "index"
    write_documents(documents_path)
    embeddings = TinyEmbeddings()

    manifest = build_and_save_vector_store(
        settings=make_settings(),
        documents_path=documents_path,
        index_dir=index_dir,
        embeddings=embeddings,
    )
    loaded = load_vector_store(
        settings=make_settings(),
        index_dir=index_dir,
        embeddings=embeddings,
    )

    assert manifest["document_count"] == 2
    assert manifest["embedding_model"] == "test-local-embedding"
    assert len(manifest["source_sha256"]) == 64
    assert len(manifest["index_pickle_sha256"]) == 64
    assert (index_dir / "index.faiss").exists()
    assert (index_dir / "index.pkl").exists()
    assert loaded.index.ntotal == 2
