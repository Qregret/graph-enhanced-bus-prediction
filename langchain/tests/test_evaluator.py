from __future__ import annotations

from langchain_core.documents import Document

from evaluation.evaluator import evaluate_retriever
from retrieval.retriever import RetrievalHit


class FakeRetriever:
    class FakeVectorStore:
        @staticmethod
        def similarity_search(_query: str, k: int = 5):
            return [
                Document(
                    page_content="匹配案例",
                    metadata={"line_id": "line-1", "station_id": "station-1"},
                )
            ][:k]

    vector_store = FakeVectorStore()

    def retrieve(self, _query: str, k: int = 5):
        document = Document(
            page_content="匹配案例",
            metadata={"line_id": "line-1", "station_id": "station-1"},
        )
        return [RetrievalHit(document, 0.1, 1, 1)][:k], {"line_name": "105路"}


def test_evaluator_calculates_retrieval_metrics():
    expected = Document(
        page_content="用户问题：105路怎么乘坐？",
        metadata={"sample_id": "TA1", "line_id": "line-1", "station_id": "station-1"},
    )

    report = evaluate_retriever(FakeRetriever(), [expected], limit=1, top_k=5)

    assert report.line_hit_rate == 1.0
    assert report.station_hit_rate == 1.0
    assert report.line_station_hit_rate == 1.0
    assert report.mean_reciprocal_rank == 1.0
    assert report.filtered_pair_hit_rate == 0.0
    assert report.vector_only_pair_hit_rate == 1.0
