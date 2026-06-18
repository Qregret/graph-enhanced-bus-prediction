from __future__ import annotations

import asyncio
import json
import time
from functools import lru_cache
from pathlib import Path
from uuid import uuid4

import uvicorn
from fastapi import FastAPI, HTTPException

from chains.chat_chain import RAGChatChain
from config import settings
from retrieval.retriever import HybridRetriever
from retrieval.reranker import create_reranker
from retrieval.indexer import build_index
from retrieval.loader import build_documents, save_documents
from retrieval.vector_store import load_processed_documents, load_vector_store
from schemas import (
    ChatData,
    ChatRequest,
    ChatResponse,
    HealthResponse,
    KnowledgeRebuildRequest,
    KnowledgeRebuildResponse,
    RetrievedDocument,
    RetrieveRequest,
    RetrieveResponse,
)
from services.glm_service import GLMService, GLMServiceError
from services.transit_service import TransitService


app = FastAPI(
    title="智慧公交 LangChain RAG 服务",
    description="阶段一：通过 LangChain 调用智谱 GLM。",
    version="1.0.0",
)
glm_service = GLMService(settings)
transit_service = TransitService(settings)
rebuild_lock = asyncio.Lock()
INDEX_MANIFEST = Path(__file__).resolve().parent / "data" / "vector_store" / "manifest.json"


@lru_cache(maxsize=1)
def get_retriever() -> HybridRetriever:
    return HybridRetriever(
        vector_store=load_vector_store(settings),
        documents=load_processed_documents(),
        reranker=create_reranker(settings),
    )


@lru_cache(maxsize=1)
def get_rag_chain() -> RAGChatChain:
    return RAGChatChain(get_retriever(), glm_service, transit_service)


def read_index_manifest() -> dict[str, object]:
    if not INDEX_MANIFEST.exists():
        return {}
    try:
        with INDEX_MANIFEST.open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return {}


def rebuild_knowledge_sync(rebuild_documents: bool) -> tuple[int, int, dict[str, object]]:
    knowledge_count = 0
    evaluation_count = 0
    if rebuild_documents:
        knowledge, evaluation, stats = build_documents()
        save_documents(knowledge, evaluation, stats)
        knowledge_count = stats.knowledge_documents
        evaluation_count = stats.evaluation_documents
    else:
        knowledge_count = len(load_processed_documents())

    manifest = build_index(settings)
    return knowledge_count, evaluation_count, manifest


@app.get("/api/v1/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    manifest = read_index_manifest()
    return HealthResponse(
        status="ok" if settings.configured else "degraded",
        model=settings.model,
        configured=settings.configured,
        index_ready=bool(manifest),
        indexed_documents=int(manifest.get("document_count", 0)),
        embedding_model=str(manifest.get("embedding_model")) if manifest else None,
    )


@app.post("/api/v1/knowledge/rebuild", response_model=KnowledgeRebuildResponse)
async def rebuild_knowledge(request: KnowledgeRebuildRequest) -> KnowledgeRebuildResponse:
    if rebuild_lock.locked():
        raise HTTPException(status_code=409, detail="知识库正在重建，请稍后重试。")
    async with rebuild_lock:
        try:
            knowledge_count, evaluation_count, manifest = await asyncio.to_thread(
                rebuild_knowledge_sync,
                request.rebuild_documents,
            )
        except (FileNotFoundError, ValueError, RuntimeError) as exc:
            raise HTTPException(status_code=500, detail=str(exc)) from exc
        get_rag_chain.cache_clear()
        get_retriever.cache_clear()
        return KnowledgeRebuildResponse(
            knowledge_documents=knowledge_count,
            evaluation_documents=evaluation_count,
            indexed_documents=int(manifest["document_count"]),
            embedding_model=str(manifest["embedding_model"]),
        )


@app.post("/api/v1/retrieve", response_model=RetrieveResponse)
async def retrieve_endpoint(request: RetrieveRequest) -> RetrieveResponse:
    try:
        hits, filters = get_retriever().retrieve(request.query, k=request.top_k)
    except (FileNotFoundError, ValueError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return RetrieveResponse(
        filters=filters,
        documents=[
            RetrievedDocument(
                content=hit.document.page_content,
                metadata=hit.document.metadata,
                score=hit.score,
                vector_rank=hit.vector_rank,
                keyword_rank=hit.keyword_rank,
            )
            for hit in hits
        ],
    )


@app.post("/api/v1/chat", response_model=ChatResponse)
async def chat_endpoint(request: ChatRequest) -> ChatResponse:
    started_at = time.perf_counter()
    request_id = uuid4().hex

    try:
        result = await get_rag_chain().answer(
            question=request.prompt,
            history=request.history,
            top_k=request.top_k,
            temperature=request.temperature,
            max_tokens=request.max_tokens,
            date=request.date,
        )
    except GLMServiceError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc

    return ChatResponse(
        data=ChatData(
            response=result.answer,
            model=settings.model,
            request_id=request_id,
            latency_ms=round((time.perf_counter() - started_at) * 1000),
            filters=result.filters,
            sources=result.sources,
            retrieved_documents=[
                RetrievedDocument(
                    content=hit.document.page_content,
                    metadata=hit.document.metadata,
                    score=hit.score,
                    vector_rank=hit.vector_rank,
                    keyword_rank=hit.keyword_rank,
                )
                for hit in result.hits
            ],
            realtime_available=result.realtime.available,
            realtime_data=result.realtime.data,
            realtime_error=result.realtime.error,
            route_plan_available=result.route_plan.available,
            route_plan=result.route_plan.data,
            trip={
                "origin": result.trip.origin,
                "destination": result.trip.destination,
                "matched_aliases": result.trip.matched_aliases,
            },
        )
    )


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000, reload=False)
