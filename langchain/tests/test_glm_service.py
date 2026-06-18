from __future__ import annotations

from config import Settings
from schemas import ChatHistoryMessage
from services.glm_service import GLMService


def make_settings(api_key: str = "test-key") -> Settings:
    return Settings(
        api_key=api_key,
        model="glm-4.5-air",
        base_url="https://open.bigmodel.cn/api/paas/v4/",
        default_temperature=0.0,
        default_max_tokens=500,
        timeout_seconds=60,
        max_retries=2,
    )


def test_build_model_uses_glm_configuration():
    model = GLMService(make_settings())._model(0.0, 500)

    assert model.model_name == "glm-4.5-air"
    assert model.temperature == 0.0
    assert model.max_tokens == 500


def test_build_messages_keeps_history_order():
    service = GLMService(make_settings())
    messages = service.build_messages(
        "是否需要增发车辆？",
        [
            ChatHistoryMessage(role="user", content="宏声桥拥挤"),
            ChatHistoryMessage(role="assistant", content="请补充线路"),
        ],
    )

    assert [message.type for message in messages] == [
        "system",
        "human",
        "ai",
        "human",
    ]
    assert messages[-1].content == "是否需要增发车辆？"


def test_missing_api_key_is_rejected():
    service = GLMService(make_settings(api_key=""))

    try:
        service._model(0.0, 500)
    except RuntimeError as exc:
        assert "ZHIPUAI_API_KEY" in str(exc)
    else:
        raise AssertionError("missing API key should fail")
