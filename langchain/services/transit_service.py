from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any

import httpx

from config import Settings


@dataclass(frozen=True)
class TransitContext:
    available: bool
    data: dict[str, Any]
    error: str | None = None


@dataclass(frozen=True)
class RoutePlanContext:
    available: bool
    data: dict[str, Any]
    error: str | None = None


class TransitService:
    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None):
        self.base_url = settings.transit_api_base_url
        self.timeout = settings.transit_api_timeout_seconds
        self.transport = transport

    async def get_context(self, date: str | None = None) -> TransitContext:
        params = {"date": date} if date else None
        endpoints = {
            "dispatch": "/api/system/dispatch",
            "forecast": "/api/system/forecast",
            "overview": "/api/system/overview",
        }
        try:
            async with httpx.AsyncClient(
                base_url=self.base_url,
                timeout=self.timeout,
                transport=self.transport,
            ) as client:
                responses = await asyncio.gather(
                    *(client.get(path, params=params) for path in endpoints.values())
                )
                for response in responses:
                    response.raise_for_status()
                payloads = {
                    name: response.json()
                    for name, response in zip(endpoints, responses)
                }
        except (httpx.HTTPError, ValueError):
            return TransitContext(
                available=False,
                data={},
                error="实时公交服务当前不可用，回答将仅参考历史案例。",
            )

        return TransitContext(
            available=True,
            data=self._compact(payloads),
        )

    async def get_route_plan(
        self,
        origin: str,
        destination: str,
    ) -> RoutePlanContext:
        try:
            async with httpx.AsyncClient(
                base_url=self.base_url,
                timeout=self.timeout,
                transport=self.transport,
            ) as client:
                response = await client.get(
                    "/api/system/route-plan",
                    params={"origin": origin, "destination": destination},
                )
                response.raise_for_status()
                payload = response.json()
        except (httpx.HTTPError, ValueError):
            return RoutePlanContext(
                available=False,
                data={},
                error="线路拓扑服务不可用，无法确认直达线路。",
            )
        return RoutePlanContext(available=True, data=payload)

    @staticmethod
    def _compact(payloads: dict[str, dict[str, Any]]) -> dict[str, Any]:
        dispatch = payloads.get("dispatch", {})
        forecast = payloads.get("forecast", {})
        overview = payloads.get("overview", {})
        return {
            "date": dispatch.get("date") or forecast.get("date") or overview.get("date"),
            "dispatch": {
                "metrics": dispatch.get("metrics", {}),
                "strategies": dispatch.get("strategies", [])[:5],
                "resourcePool": dispatch.get("resourcePool", {}),
            },
            "forecast": {
                "hotspots": forecast.get("hotspots", [])[:10],
                "insights": forecast.get("insights", [])[:5],
            },
            "overview": {
                "metrics": overview.get("metrics", {}),
                "weather": overview.get("weather", {}),
                "topCongestedRoutes": overview.get("topCongestedRoutes", [])[:5],
                "alerts": overview.get("alerts", [])[:5],
            },
        }
