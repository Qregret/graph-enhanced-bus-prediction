from __future__ import annotations

import os
from pathlib import Path

# This API-backed service does not use local Transformers/PyTorch. Disabling the
# optional probe also isolates it from the separate local distillation runtime.
os.environ.setdefault("USE_TORCH", "0")

import uvicorn
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, Field


BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")

GLM_MODEL = os.getenv("GLM_MODEL", "glm-4.5-air")
GLM_BASE_URL = os.getenv(
    "GLM_BASE_URL",
    "https://open.bigmodel.cn/api/paas/v4/",
)
SYSTEM_PROMPT = "你是专业的智慧公交调度与出行客服专家。回答应准确、简洁，不得虚构实时数据。"


class ChatRequest(BaseModel):
    prompt: str = Field(..., min_length=1, description="用户问题")
    max_tokens: int = Field(500, ge=1, le=4096, description="最大生成 token 数")
    temperature: float = Field(0.0, ge=0.0, le=1.5, description="采样温度")


app = FastAPI(title="公交 OD 客服 GLM 微服务", version="2.0")


def build_model(request: ChatRequest) -> ChatOpenAI:
    api_key = os.getenv("ZHIPUAI_API_KEY")
    if not api_key:
        raise RuntimeError(
            "缺少 ZHIPUAI_API_KEY，请在环境变量或 蒸馏/OD客服/.env 中配置。"
        )

    return ChatOpenAI(
        model=GLM_MODEL,
        api_key=api_key,
        base_url=GLM_BASE_URL,
        temperature=request.temperature,
        max_tokens=request.max_tokens,
        timeout=60,
        max_retries=2,
    )


@app.get("/api/v1/health")
async def health():
    return {
        "status": "ok",
        "provider": "zhipuai",
        "model": GLM_MODEL,
        "configured": bool(os.getenv("ZHIPUAI_API_KEY")),
    }


@app.post("/api/v1/chat")
async def chat_endpoint(request: ChatRequest):
    try:
        model = build_model(request)
        response = await model.ainvoke(
            [
                SystemMessage(content=SYSTEM_PROMPT),
                HumanMessage(content=request.prompt),
            ]
        )
        response_text = response.content
        if not isinstance(response_text, str):
            response_text = str(response_text)

        return {
            "status": "success",
            "data": {
                "response": response_text.strip(),
                "model": GLM_MODEL,
            },
        }
    except RuntimeError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"GLM 调用失败: {exc}") from exc


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000, reload=False)
