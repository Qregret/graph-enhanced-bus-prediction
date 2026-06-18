from __future__ import annotations

from pathlib import Path

from langchain_core.embeddings import Embeddings

from config import Settings
from retrieval.vector_store import (
    DEFAULT_DOCUMENTS_PATH,
    DEFAULT_INDEX_DIR,
    build_and_save_vector_store,
)


def build_index(
    settings: Settings,
    documents_path: Path = DEFAULT_DOCUMENTS_PATH,
    index_dir: Path = DEFAULT_INDEX_DIR,
    embeddings: Embeddings | None = None,
) -> dict[str, object]:
    return build_and_save_vector_store(
        settings=settings,
        documents_path=documents_path,
        index_dir=index_dir,
        embeddings=embeddings,
    )
