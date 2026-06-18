from __future__ import annotations

from functools import lru_cache
from typing import Sequence

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI

from config import Settings
from schemas import ChatHistoryMessage


SYSTEM_PROMPT = """你是专业的智慧公交调度与出行客服专家。
回答应准确、简洁，并给出可执行的建议。
不得虚构实时客流、拥挤度、车辆数、天气或等待时间；缺少数据时应明确说明。"""


class GLMServiceError(RuntimeError):
    """A safe, user-facing GLM service error."""


class GLMService:
    def __init__(self, settings: Settings):
        self.settings = settings

    @lru_cache(maxsize=16)
    def _model(self, temperature: float, max_tokens: int) -> ChatOpenAI:
        return ChatOpenAI(
            model=self.settings.model,
            api_key=self.settings.require_api_key(),
            base_url=self.settings.base_url,
            temperature=temperature,
            max_tokens=max_tokens,
            timeout=self.settings.timeout_seconds,
            max_retries=self.settings.max_retries,
        )

    def build_messages(
        self,
        prompt: str,
        history: Sequence[ChatHistoryMessage],
        system_prompt: str = SYSTEM_PROMPT,
    ) -> list[BaseMessage]:
        messages: list[BaseMessage] = [SystemMessage(content=system_prompt)]
        for item in history:
            message_type = HumanMessage if item.role == "user" else AIMessage
            messages.append(message_type(content=item.content))
        messages.append(HumanMessage(content=prompt))
        return messages

    async def chat(
        self,
        prompt: str,
        history: Sequence[ChatHistoryMessage],
        temperature: float | None = None,
        max_tokens: int | None = None,
        system_prompt: str = SYSTEM_PROMPT,
    ) -> str:
        effective_temperature = (
            self.settings.default_temperature
            if temperature is None
            else temperature
        )
        effective_max_tokens = (
            self.settings.default_max_tokens
            if max_tokens is None
            else max_tokens
        )

        try:
            response = await self._model(
                effective_temperature,
                effective_max_tokens,
            ).ainvoke(self.build_messages(prompt, history, system_prompt))
        except RuntimeError:
            raise
        except Exception as exc:
            raise GLMServiceError(
                "GLM 服务调用失败，请检查网络、模型额度或稍后重试。"
            ) from exc

        text = self._content_to_text(response.content).strip()
        if not text:
            raise GLMServiceError("GLM 返回了空内容，请重新提问。")
        return text

    @staticmethod
    def _content_to_text(content: object) -> str:
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            parts: list[str] = []
            for item in content:
                if isinstance(item, str):
                    parts.append(item)
                elif isinstance(item, dict) and isinstance(item.get("text"), str):
                    parts.append(item["text"])
            return "".join(parts)
        return str(content)
