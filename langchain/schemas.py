from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class ChatHistoryMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(..., min_length=1, max_length=8000)


class ChatRequest(BaseModel):
    prompt: str = Field(..., min_length=1, max_length=8000, description="用户问题")
    history: list[ChatHistoryMessage] = Field(default_factory=list, max_length=20)
    max_tokens: int | None = Field(default=None, ge=1, le=4096)
    temperature: float | None = Field(default=None, ge=0.0, le=1.5)
    top_k: int = Field(default=8, ge=1, le=10)
    date: str | None = Field(default=None, pattern=r"^\d{4}-\d{2}-\d{2}$")


class ChatData(BaseModel):
    response: str
    model: str
    request_id: str
    latency_ms: int
    filters: dict[str, str] = Field(default_factory=dict)
    sources: list[dict[str, str]] = Field(default_factory=list)
    retrieved_documents: list["RetrievedDocument"] = Field(default_factory=list)
    realtime_available: bool = False
    realtime_data: dict[str, object] = Field(default_factory=dict)
    realtime_error: str | None = None
    route_plan_available: bool = False
    route_plan: dict[str, object] = Field(default_factory=dict)
    trip: dict[str, object] = Field(default_factory=dict)


class ChatResponse(BaseModel):
    status: Literal["success"] = "success"
    data: ChatData


class HealthResponse(BaseModel):
    status: Literal["ok", "degraded"]
    provider: Literal["zhipuai"] = "zhipuai"
    model: str
    configured: bool
    index_ready: bool = False
    indexed_documents: int = 0
    embedding_model: str | None = None


class RetrieveRequest(BaseModel):
    query: str = Field(..., min_length=1, max_length=8000)
    top_k: int = Field(default=5, ge=1, le=20)


class RetrievedDocument(BaseModel):
    content: str
    metadata: dict[str, object]
    score: float
    vector_rank: int | None
    keyword_rank: int | None


class RetrieveResponse(BaseModel):
    status: Literal["success"] = "success"
    filters: dict[str, str]
    documents: list[RetrievedDocument]


class KnowledgeRebuildRequest(BaseModel):
    rebuild_documents: bool = True


class KnowledgeRebuildResponse(BaseModel):
    status: Literal["success"] = "success"
    knowledge_documents: int
    evaluation_documents: int
    indexed_documents: int
    embedding_model: str
