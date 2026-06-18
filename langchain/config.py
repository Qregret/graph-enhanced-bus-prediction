from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv


BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")


def _read_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise ValueError(f"环境变量 {name} 必须是整数。") from exc


@dataclass(frozen=True)
class Settings:
    api_key: str
    model: str
    base_url: str
    default_temperature: float
    default_max_tokens: int
    timeout_seconds: int
    max_retries: int
    embedding_model: str = "embedding-3"
    embedding_batch_size: int = 64
    embedding_provider: str = "local"
    local_embedding_model: str = "BAAI/bge-small-zh-v1.5"
    transit_api_base_url: str = "http://127.0.0.1:8080"
    transit_api_timeout_seconds: int = 8
    reranker_enabled: bool = False
    local_reranker_model: str = "BAAI/bge-reranker-base"
    reranker_batch_size: int = 16

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            api_key=os.getenv("ZHIPUAI_API_KEY", "").strip(),
            model=os.getenv("GLM_MODEL", "glm-4.5-air").strip(),
            base_url=os.getenv(
                "GLM_BASE_URL",
                "https://open.bigmodel.cn/api/paas/v4/",
            ).strip(),
            default_temperature=float(os.getenv("GLM_TEMPERATURE", "0")),
            default_max_tokens=_read_int("GLM_MAX_TOKENS", 500),
            timeout_seconds=_read_int("GLM_TIMEOUT_SECONDS", 60),
            max_retries=_read_int("GLM_MAX_RETRIES", 2),
            embedding_model=os.getenv(
                "GLM_EMBEDDING_MODEL",
                "embedding-3",
            ).strip(),
            embedding_batch_size=_read_int("EMBEDDING_BATCH_SIZE", 64),
            embedding_provider=os.getenv("EMBEDDING_PROVIDER", "local").strip().lower(),
            local_embedding_model=os.getenv(
                "LOCAL_EMBEDDING_MODEL",
                "BAAI/bge-small-zh-v1.5",
            ).strip(),
            transit_api_base_url=os.getenv(
                "TRANSIT_API_BASE_URL",
                "http://127.0.0.1:8080",
            ).strip(),
            transit_api_timeout_seconds=_read_int("TRANSIT_API_TIMEOUT_SECONDS", 8),
            reranker_enabled=os.getenv("RERANKER_ENABLED", "false").lower()
            in {"1", "true", "yes", "on"},
            local_reranker_model=os.getenv(
                "LOCAL_RERANKER_MODEL",
                "BAAI/bge-reranker-base",
            ).strip(),
            reranker_batch_size=_read_int("RERANKER_BATCH_SIZE", 16),
        )

    @property
    def configured(self) -> bool:
        return bool(self.api_key)

    def require_api_key(self) -> str:
        if not self.api_key:
            raise RuntimeError(
                "缺少 ZHIPUAI_API_KEY，请在 langchain/.env 或系统环境变量中配置。"
            )
        return self.api_key


settings = Settings.from_env()
