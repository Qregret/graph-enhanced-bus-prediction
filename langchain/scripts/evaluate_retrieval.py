from __future__ import annotations

import json
import sys
from dataclasses import asdict
from pathlib import Path


SERVICE_DIR = Path(__file__).resolve().parents[1]
if str(SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(SERVICE_DIR))

from config import settings
from evaluation.evaluator import (
    evaluate_retriever,
    load_evaluation_documents,
    save_report,
)
from retrieval.retriever import HybridRetriever
from retrieval.reranker import create_reranker
from retrieval.vector_store import load_processed_documents, load_vector_store


def main() -> None:
    retriever = HybridRetriever(
        load_vector_store(settings),
        load_processed_documents(),
        reranker=create_reranker(settings),
    )
    report = evaluate_retriever(retriever, load_evaluation_documents(), limit=50, top_k=8)
    save_report(report)
    print(json.dumps(asdict(report), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
