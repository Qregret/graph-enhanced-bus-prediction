from __future__ import annotations

import json
from dataclasses import dataclass

from chains.prompts import RAG_SYSTEM_PROMPT, build_rag_prompt
from retrieval.retriever import HybridRetriever, RetrievalHit, TripEntities
from schemas import ChatHistoryMessage
from services.glm_service import GLMService
from services.transit_service import RoutePlanContext, TransitContext, TransitService


@dataclass(frozen=True)
class RAGAnswer:
    answer: str
    hits: list[RetrievalHit]
    filters: dict[str, str]
    sources: list[dict[str, str]]
    realtime: TransitContext
    route_plan: RoutePlanContext
    trip: TripEntities


class RAGChatChain:
    def __init__(
        self,
        retriever: HybridRetriever,
        glm_service: GLMService,
        transit_service: TransitService | None = None,
    ):
        self.retriever = retriever
        self.glm_service = glm_service
        self.transit_service = transit_service

    async def answer(
        self,
        question: str,
        history: list[ChatHistoryMessage],
        top_k: int = 8,
        temperature: float | None = None,
        max_tokens: int | None = None,
        date: str | None = None,
    ) -> RAGAnswer:
        trip = self.retriever.resolve_trip_entities(question)
        normalized_question = question
        if trip.origin:
            normalized_question += f" 起点：{trip.origin}。"
        if trip.destination:
            normalized_question += f" 终点：{trip.destination}。"
        hits, filters = self.retriever.retrieve(normalized_question, k=top_k)
        realtime = (
            await self.transit_service.get_context(date)
            if self.transit_service
            else TransitContext(False, {}, "未配置实时公交服务。")
        )
        route_plan = (
            await self.transit_service.get_route_plan(trip.origin, trip.destination)
            if self.transit_service and trip.origin and trip.destination
            else RoutePlanContext(False, {}, "未识别出完整的起点和终点。")
        )
        generation_hits = [] if route_plan.available else hits
        sources = self._sources(generation_hits)
        route_intent = any(term in question for term in ("想去", "要去", "前往", "怎么走", "怎么坐", "从"))
        if route_intent and (not trip.origin or not trip.destination):
            known = trip.origin or trip.destination or "未识别"
            return RAGAnswer(
                answer=f"我目前只识别到“{known}”。请按“我从某站去某站”的方式补充起点和终点。",
                hits=[],
                filters=filters,
                sources=[],
                realtime=realtime,
                route_plan=route_plan,
                trip=trip,
            )
        if route_plan.available:
            sources.append(
                {
                    "source": "route_topology_api",
                    "sample_id": "",
                    "line_name": "",
                    "station_name": "",
                },
            )
        if realtime.available:
            sources.append(
                {
                    "source": "spring_boot_realtime_api",
                    "sample_id": "",
                    "line_name": "",
                    "station_name": "",
                },
            )
        if not generation_hits and not realtime.available and not route_plan.available:
            return RAGAnswer(
                answer="知识库中没有检索到足够依据。请补充具体线路、站点、日期或实时客流信息。",
                hits=[],
                filters=filters,
                sources=[],
                realtime=realtime,
                route_plan=route_plan,
                trip=trip,
            )

        prompt = build_rag_prompt(
            question,
            [hit.document.page_content for hit in generation_hits],
            json.dumps(realtime.data, ensure_ascii=False) if realtime.available else None,
            json.dumps(route_plan.data, ensure_ascii=False) if route_plan.available else None,
        )
        answer = await self.glm_service.chat(
            prompt=prompt,
            history=history,
            temperature=temperature,
            max_tokens=max_tokens,
            system_prompt=RAG_SYSTEM_PROMPT,
        )
        return RAGAnswer(
            answer=answer,
            hits=generation_hits,
            filters=filters,
            sources=sources,
            realtime=realtime,
            route_plan=route_plan,
            trip=trip,
        )

    @staticmethod
    def _sources(hits: list[RetrievalHit]) -> list[dict[str, str]]:
        sources: list[dict[str, str]] = []
        seen: set[tuple[str, str]] = set()
        for hit in hits:
            metadata = hit.document.metadata
            source = str(metadata.get("source", ""))
            sample_id = str(metadata.get("sample_id", ""))
            key = (source, sample_id)
            if key in seen:
                continue
            seen.add(key)
            sources.append(
                {
                    "source": source,
                    "sample_id": sample_id,
                    "line_name": str(metadata.get("line_name", "")),
                    "station_name": str(metadata.get("station_name", "")),
                }
            )
        return sources
