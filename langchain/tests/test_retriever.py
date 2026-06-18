from __future__ import annotations

from langchain_community.vectorstores import FAISS
from langchain_core.documents import Document

from retrieval.retriever import HybridRetriever
from tests.test_vector_store import TinyEmbeddings


def make_retriever() -> HybridRetriever:
    documents = [
        Document(
            page_content="宏声桥商圈客流拥挤，建议评估备用车辆。",
            metadata={
                "sample_id": "TA1",
                "line_name": "105路",
                "station_name": "宏声桥",
                "weather": "暴雨",
            },
        ),
        Document(
            page_content="奥体中心客流正常，可维持当前班次。",
            metadata={
                "sample_id": "TA2",
                "line_name": "127路",
                "station_name": "奥体中心",
                "weather": "晴",
            },
        ),
    ]
    vector_store = FAISS.from_documents(documents, TinyEmbeddings())
    return HybridRetriever(vector_store, documents)


def test_extracts_line_station_and_weather_filters():
    filters = make_retriever().extract_filters("暴雨时宏声桥的105路需要增发车辆吗？")

    assert filters == {
        "line_name": "105路",
        "station_name": "宏声桥",
        "weather": "暴雨",
    }


def test_station_filter_prefers_origin_mentioned_first():
    filters = make_retriever().extract_filters(
        "我从宏声桥出发去奥体中心，乘坐127路合适吗？"
    )

    assert filters["station_name"] == "宏声桥"


def test_station_alias_resolves_short_name_and_trip_roles():
    documents = [
        Document(
            page_content="高山湾枢纽站属于127路。",
            metadata={"sample_id": "A", "line_name": "127路", "station_name": "高山湾枢纽站"},
        ),
        Document(
            page_content="奥体中心属于127路。",
            metadata={"sample_id": "B", "line_name": "127路", "station_name": "奥体中心"},
        ),
    ]
    retriever = HybridRetriever(
        FAISS.from_documents(documents, TinyEmbeddings()),
        documents,
    )

    trip = retriever.resolve_trip_entities("我现在在高山湾，我想去奥体中心")

    assert trip.origin == "高山湾枢纽站"
    assert trip.destination == "奥体中心"
    assert trip.matched_aliases["高山湾"] == "高山湾枢纽站"


def test_infers_unique_line_from_origin_and_destination():
    documents = [
        Document(
            page_content="甲站属于105路。",
            metadata={"sample_id": "A", "line_name": "105路", "station_name": "甲站"},
        ),
        Document(
            page_content="乙站属于105路。",
            metadata={"sample_id": "B", "line_name": "105路", "station_name": "乙站"},
        ),
    ]
    retriever = HybridRetriever(
        FAISS.from_documents(documents, TinyEmbeddings()),
        documents,
    )

    filters = retriever.extract_filters("我从甲站去乙站，应该坐哪条线路？")

    assert filters == {"station_name": "甲站", "line_name": "105路"}




def test_keyword_and_metadata_filter_select_expected_document():
    hits, filters = make_retriever().retrieve(
        "暴雨时宏声桥的105路需要增发车辆吗？",
        k=2,
    )

    assert filters["station_name"] == "宏声桥"
    assert len(hits) == 1
    assert hits[0].document.metadata["sample_id"] == "TA1"
    assert hits[0].keyword_rank == 1


class ReverseReranker:
    def score(self, _query: str, documents: list[str]) -> list[float]:
        return [float(index) for index, _ in enumerate(documents)]


def test_reranker_reorders_fused_candidates():
    base = make_retriever()
    retriever = HybridRetriever(base.vector_store, base.documents, ReverseReranker())

    hits, _ = retriever.retrieve("客流情况", k=1)

    assert hits[0].score > 0
