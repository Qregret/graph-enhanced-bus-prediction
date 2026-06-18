from __future__ import annotations

from pathlib import Path
from threading import Lock
from typing import Optional

import torch
import uvicorn
from fastapi import FastAPI, HTTPException
from peft import PeftModel
from pydantic import BaseModel, Field
from transformers import AutoModelForCausalLM, AutoTokenizer


BASE_DIR = Path(__file__).resolve().parent
BASE_MODEL_PATH = BASE_DIR / "qwen_base_model"
LORA_PATH = BASE_DIR / "checkpoint-1875"


class ChatRequest(BaseModel):
    prompt: str = Field(..., min_length=1, description="用户问题")
    max_tokens: int = Field(256, ge=1, le=1024, description="最大生成 token 数")
    temperature: float = Field(0.7, ge=0.0, le=1.5, description="采样温度")


app = FastAPI(title="公交 OD 客服大模型微服务", version="1.1")

model: Optional[PeftModel] = None
tokenizer = None
_load_lock = Lock()


def _ensure_model_files() -> None:
    missing = []
    if not BASE_MODEL_PATH.exists():
        missing.append(str(BASE_MODEL_PATH))
    if not LORA_PATH.exists():
        missing.append(str(LORA_PATH))
    if missing:
        raise FileNotFoundError(
            "模型目录不存在，请先解压模型文件：\n" + "\n".join(missing)
        )


def _infer_model_device():
    if torch.cuda.is_available():
        return "auto", torch.float16
    return None, torch.float32


def load_model_if_needed() -> None:
    global model, tokenizer
    if model is not None and tokenizer is not None:
        return

    with _load_lock:
        if model is not None and tokenizer is not None:
            return

        _ensure_model_files()
        print("正在加载模型，首次启动会较慢。")

        device_map, dtype = _infer_model_device()

        tokenizer = AutoTokenizer.from_pretrained(
            str(BASE_MODEL_PATH),
            trust_remote_code=True,
        )

        model_kwargs = {
            "trust_remote_code": True,
            "torch_dtype": dtype,
            "low_cpu_mem_usage": True,
        }
        if device_map is not None:
            model_kwargs["device_map"] = device_map

        base_model = AutoModelForCausalLM.from_pretrained(
            str(BASE_MODEL_PATH),
            **model_kwargs,
        )

        model = PeftModel.from_pretrained(base_model, str(LORA_PATH))
        model.eval()
        print("模型加载完成，服务可用。")


def _get_model_input_device() -> torch.device:
    assert model is not None
    return next(model.parameters()).device


@app.get("/api/v1/health")
async def health():
    return {
        "status": "ok",
        "model_loaded": model is not None and tokenizer is not None,
        "base_model_path": str(BASE_MODEL_PATH),
        "lora_path": str(LORA_PATH),
    }


@app.post("/api/v1/chat")
async def chat_endpoint(request: ChatRequest):
    try:
        load_model_if_needed()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"模型加载失败: {exc}") from exc

    try:
        messages = [
            {"role": "system", "content": "你是专业的智慧公交调度与出行客服专家。"},
            {"role": "user", "content": request.prompt},
        ]
        text = tokenizer.apply_chat_template(
            messages,
            tokenize=False,
            add_generation_prompt=True,
        )

        model_inputs = tokenizer([text], return_tensors="pt")
        model_inputs = {k: v.to(_get_model_input_device()) for k, v in model_inputs.items()}

        with torch.no_grad():
            generated_ids = model.generate(
                **model_inputs,
                max_new_tokens=request.max_tokens,
                temperature=request.temperature,
                do_sample=request.temperature > 0,
                pad_token_id=tokenizer.eos_token_id,
            )

        input_ids = model_inputs["input_ids"]
        new_token_ids = [
            output_ids[len(source_ids):]
            for source_ids, output_ids in zip(input_ids, generated_ids)
        ]
        response_text = tokenizer.batch_decode(
            new_token_ids,
            skip_special_tokens=True,
        )[0].strip()

        return {"status": "success", "data": {"response": response_text}}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"推理错误: {exc}") from exc


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000, reload=False)
