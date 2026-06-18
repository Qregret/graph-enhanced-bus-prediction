from __future__ import annotations

from typing import Protocol

from config import Settings


class Reranker(Protocol):
    def score(self, query: str, documents: list[str]) -> list[float]: ...


class FastEmbedReranker:
    def __init__(self, model_name: str, batch_size: int):
        from fastembed.rerank.cross_encoder import TextCrossEncoder

        self.model = TextCrossEncoder(model_name=model_name, lazy_load=True)
        self.batch_size = batch_size

    def score(self, query: str, documents: list[str]) -> list[float]:
        return [
            float(score)
            for score in self.model.rerank(
                query,
                documents,
                batch_size=self.batch_size,
            )
        ]


def create_reranker(settings: Settings) -> Reranker | None:
    if not settings.reranker_enabled:
        return None
    return FastEmbedReranker(
        model_name=settings.local_reranker_model,
        batch_size=settings.reranker_batch_size,
    )
