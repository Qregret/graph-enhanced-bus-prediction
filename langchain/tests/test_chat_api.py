from __future__ import annotations

from fastapi.testclient import TestClient
from langchain_core.documents import Document

import app as app_module
from chains.chat_chain import RAGAnswer
from retrieval.retriever import RetrievalHit, TripEntities
from services.transit_service import RoutePlanContext, TransitContext


def test_health_does_not_expose_api_key(monkeypatch):
    monkeypatch.setattr(
        app_module,
        "read_index_manifest",
        lambda: {"document_count": 2442, "embedding_model": "test-local"},
    )
    response = TestClient(app_module.app).get("/api/v1/health")

    assert response.status_code == 200
    payload = response.json()
    assert "api_key" not in payload
    assert payload["index_ready"] is True
    assert payload["indexed_documents"] == 2442


def test_chat_response_contract(monkeypatch):
    class FakeRAGChain:
        async def answer(self, **_kwargs):
            document = Document(
                page_content="历史调度案例",
                metadata={"source": "cases.csv", "sample_id": "TA1"},
            )
            return RAGAnswer(
                answer="建议先核实实时客流，再决定是否增发车辆。[来源1]",
                hits=[RetrievalHit(document, 0.03, 1, 1)],
                filters={},
                sources=[{"source": "cases.csv", "sample_id": "TA1"}],
                realtime=TransitContext(False, {}, "test unavailable"),
                route_plan=RoutePlanContext(False, {}, "test unavailable"),
                trip=TripEntities(None, None, {}),
            )

    monkeypatch.setattr(app_module, "get_rag_chain", lambda: FakeRAGChain())
    response = TestClient(app_module.app).post(
        "/api/v1/chat",
        json={"prompt": "是否需要增发车辆？"},
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "success"
    assert payload["data"]["model"] == "glm-4.5-air"
    assert payload["data"]["response"].startswith("建议")
    assert payload["data"]["request_id"]
    assert payload["data"]["sources"][0]["sample_id"] == "TA1"
    assert payload["data"]["realtime_available"] is False


def test_knowledge_rebuild_contract(monkeypatch):
    monkeypatch.setattr(
        app_module,
        "rebuild_knowledge_sync",
        lambda _rebuild: (
            2442,
            184,
            {"document_count": 2442, "embedding_model": "test-local"},
        ),
    )
    response = TestClient(app_module.app).post(
        "/api/v1/knowledge/rebuild",
        json={"rebuild_documents": True},
    )

    assert response.status_code == 200
    assert response.json()["indexed_documents"] == 2442
