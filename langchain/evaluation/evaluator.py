from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass
from pathlib import Path

from langchain_core.documents import Document

from retrieval.retriever import HybridRetriever


SERVICE_DIR = Path(__file__).resolve().parents[1]
DEFAULT_EVALUATION_PATH = SERVICE_DIR / "data" / "processed" / "evaluation_documents.jsonl"
DEFAULT_REPORT_PATH = SERVICE_DIR / "data" / "evaluation" / "retrieval_report.json"


@dataclass(frozen=True)
class RetrievalEvaluationReport:
    evaluated_questions: int
    top_k: int
    line_hit_rate: float
    station_hit_rate: float
    line_station_hit_rate: float
    mean_reciprocal_rank: float
    metadata_filter_rate: float
    line_station_filter_rate: float
    filtered_pair_hit_rate: float
    vector_only_pair_hit_rate: float
    hybrid_pair_uplift: float


def load_evaluation_documents(path: Path = DEFAULT_EVALUATION_PATH) -> list[Document]:
    documents: list[Document] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            try:
                record = json.loads(line)
                documents.append(Document(**record))
            except (json.JSONDecodeError, TypeError) as exc:
                raise ValueError(f"评测文件第 {line_number} 行格式无效。") from exc
    return documents


def _stable_sample(documents: list[Document], limit: int) -> list[Document]:
    return sorted(
        documents,
        key=lambda document: hashlib.sha256(
            str(document.metadata.get("sample_id", "")).encode("utf-8")
        ).hexdigest(),
    )[:limit]


def _question(document: Document) -> str:
    first_line = document.page_content.splitlines()[0]
    return first_line.removeprefix("用户问题：").strip()


def evaluate_retriever(
    retriever: HybridRetriever,
    documents: list[Document],
    limit: int = 50,
    top_k: int = 5,
) -> RetrievalEvaluationReport:
    selected = _stable_sample(documents, min(limit, len(documents)))
    if not selected:
        raise ValueError("没有可用的评测文档。")

    line_hits = 0
    station_hits = 0
    pair_hits = 0
    reciprocal_rank_sum = 0.0
    metadata_filter_hits = 0
    pair_filter_count = 0
    filtered_pair_hits = 0
    vector_only_pair_hits = 0

    for expected in selected:
        hits, filters = retriever.retrieve(_question(expected), k=top_k)
        vector_only_hits = retriever.vector_store.similarity_search(
            _question(expected),
            k=top_k,
        )
        expected_line = str(expected.metadata.get("line_id", ""))
        expected_station = str(expected.metadata.get("station_id", ""))
        if filters:
            metadata_filter_hits += 1
        has_pair_filter = "line_name" in filters and "station_name" in filters
        if has_pair_filter:
            pair_filter_count += 1

        vector_only_pair_hits += int(
            any(
                str(document.metadata.get("line_id", "")) == expected_line
                and str(document.metadata.get("station_id", "")) == expected_station
                for document in vector_only_hits
            )
        )

        line_hits += int(
            any(str(hit.document.metadata.get("line_id", "")) == expected_line for hit in hits)
        )
        station_hits += int(
            any(str(hit.document.metadata.get("station_id", "")) == expected_station for hit in hits)
        )
        for rank, hit in enumerate(hits, start=1):
            metadata = hit.document.metadata
            if (
                str(metadata.get("line_id", "")) == expected_line
                and str(metadata.get("station_id", "")) == expected_station
            ):
                pair_hits += 1
                if has_pair_filter:
                    filtered_pair_hits += 1
                reciprocal_rank_sum += 1.0 / rank
                break

    count = len(selected)
    hybrid_pair_rate = pair_hits / count
    vector_pair_rate = vector_only_pair_hits / count
    return RetrievalEvaluationReport(
        evaluated_questions=count,
        top_k=top_k,
        line_hit_rate=round(line_hits / count, 4),
        station_hit_rate=round(station_hits / count, 4),
        line_station_hit_rate=round(hybrid_pair_rate, 4),
        mean_reciprocal_rank=round(reciprocal_rank_sum / count, 4),
        metadata_filter_rate=round(metadata_filter_hits / count, 4),
        line_station_filter_rate=round(pair_filter_count / count, 4),
        filtered_pair_hit_rate=round(
            filtered_pair_hits / pair_filter_count if pair_filter_count else 0.0,
            4,
        ),
        vector_only_pair_hit_rate=round(vector_pair_rate, 4),
        hybrid_pair_uplift=round(hybrid_pair_rate - vector_pair_rate, 4),
    )


def save_report(
    report: RetrievalEvaluationReport,
    path: Path = DEFAULT_REPORT_PATH,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(asdict(report), handle, ensure_ascii=False, indent=2)
