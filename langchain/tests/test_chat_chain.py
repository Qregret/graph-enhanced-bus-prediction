from __future__ import annotations

import asyncio

from langchain_core.documents import Document

from chains.chat_chain import RAGChatChain
from chains.prompts import RAG_SYSTEM_PROMPT
from retrieval.retriever import RetrievalHit, TripEntities
from services.transit_service import RoutePlanContext, TransitContext


class FakeRetriever:
    def resolve_trip_entities(self, _query: str):
        return TripEntities("宏声桥", "奥体中心", {})

    def retrieve(self, _query: str, k: int = 5):
        document = Document(
            page_content="历史案例显示，105路在高峰拥挤时应先核实备用运力。",
            metadata={
                "source": "cases.csv",
                "sample_id": "TA1",
                "line_name": "105路",
                "station_name": "宏声桥",
            },
        )
        return [RetrievalHit(document, 0.03, 1, 1)][:k], {"line_name": "105路"}


class FakeGLMService:
    def __init__(self):
        self.arguments = None

    async def chat(self, **kwargs):
        self.arguments = kwargs
        return "情况分析：历史案例存在拥挤。行动建议：先核实实时运力。[来源1]"


class FakeTransitService:
    async def get_context(self, _date=None):
        return TransitContext(
            True,
            {"date": "2026-06-18", "dispatch": {"resourcePool": {"standbyVehicles": 2}}},
        )

    async def get_route_plan(self, origin, destination):
        return RoutePlanContext(
            False,
            {},
            "route unavailable",
        )


def test_rag_chain_injects_context_and_returns_sources():
    glm = FakeGLMService()
    chain = RAGChatChain(FakeRetriever(), glm, FakeTransitService())

    result = asyncio.run(chain.answer("105路需要增发车辆吗？", []))

    assert "[来源1]" in result.answer
    assert result.sources[0]["sample_id"] == "TA1"
    assert "历史案例显示" in glm.arguments["prompt"]
    assert glm.arguments["system_prompt"] == RAG_SYSTEM_PROMPT
    assert "standbyVehicles" in glm.arguments["prompt"]
    assert result.realtime.available is True
    assert result.route_plan.available is False


class DirectRouteTransitService(FakeTransitService):
    async def get_route_plan(self, origin, destination):
        return RoutePlanContext(
            True,
            {"origin": origin, "destination": destination, "routes": [{"lineName": "105路"}]},
        )


def test_direct_route_uses_topology_without_historical_cases():
    glm = FakeGLMService()
    result = asyncio.run(
        RAGChatChain(FakeRetriever(), glm, DirectRouteTransitService()).answer(
            "我从宏声桥去奥体中心怎么走？",
            [],
        )
    )

    assert result.route_plan.available is True
    assert result.hits == []
    assert "105路" in glm.arguments["prompt"]
    assert "历史案例显示，105路" not in glm.arguments["prompt"]


class EmptyRetriever:
    def resolve_trip_entities(self, _query: str):
        return TripEntities(None, None, {})

    def retrieve(self, _query: str, k: int = 5):
        return [], {}


def test_rag_chain_does_not_call_glm_without_evidence():
    glm = FakeGLMService()
    result = asyncio.run(RAGChatChain(EmptyRetriever(), glm).answer("未知问题", []))

    assert "没有检索到足够依据" in result.answer
    assert glm.arguments is None
