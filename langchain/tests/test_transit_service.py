from __future__ import annotations

import asyncio

import httpx

from config import Settings
from services.transit_service import TransitService


def make_settings() -> Settings:
    return Settings(
        api_key="test-key",
        model="glm-4.5-air",
        base_url="https://open.bigmodel.cn/api/paas/v4/",
        default_temperature=0,
        default_max_tokens=500,
        timeout_seconds=60,
        max_retries=2,
        transit_api_base_url="http://testserver",
    )


def test_transit_service_compacts_realtime_payloads():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("dispatch"):
            return httpx.Response(200, json={"date": "2026-06-18", "metrics": {"recommendedExtraVehicles": 2}, "strategies": [], "resourcePool": {"standbyVehicles": 3}})
        if request.url.path.endswith("forecast"):
            return httpx.Response(200, json={"hotspots": [{"name": "宏声桥"}], "insights": []})
        if request.url.path.endswith("route-plan"):
            return httpx.Response(200, json={"origin": "高山湾枢纽站", "destination": "奥体中心", "direct": True, "routes": [{"lineName": "127路"}]})
        return httpx.Response(200, json={"weather": {"condition": "小雨"}, "metrics": {}, "topCongestedRoutes": [], "alerts": []})

    service = TransitService(make_settings(), transport=httpx.MockTransport(handler))
    result = asyncio.run(service.get_context("2026-06-18"))

    assert result.available is True
    assert result.data["dispatch"]["resourcePool"]["standbyVehicles"] == 3
    assert result.data["overview"]["weather"]["condition"] == "小雨"


def test_transit_service_loads_deterministic_route_plan():
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"direct": True, "routes": [{"lineName": "127路"}]})

    service = TransitService(make_settings(), transport=httpx.MockTransport(handler))
    result = asyncio.run(service.get_route_plan("高山湾枢纽站", "奥体中心"))

    assert result.available is True
    assert result.data["routes"][0]["lineName"] == "127路"
