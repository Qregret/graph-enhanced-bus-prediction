from __future__ import annotations

import hashlib
import math
import re
from collections import Counter
from dataclasses import dataclass

from langchain_community.vectorstores import FAISS
from langchain_core.documents import Document

from retrieval.reranker import Reranker


WEATHER_TERMS = ("晴", "多云", "阴", "小雨", "中雨", "大雨", "暴雨", "雪", "雾")


@dataclass(frozen=True)
class RetrievalHit:
    document: Document
    score: float
    vector_rank: int | None
    keyword_rank: int | None


@dataclass(frozen=True)
class TripEntities:
    origin: str | None
    destination: str | None
    matched_aliases: dict[str, str]


class HybridRetriever:
    def __init__(
        self,
        vector_store: FAISS,
        documents: list[Document],
        reranker: Reranker | None = None,
    ):
        self.vector_store = vector_store
        self.documents = documents
        self.reranker = reranker
        self._documents_by_key = {self._document_key(doc): doc for doc in documents}
        self._line_names = self._catalog("line_name")
        self._station_names = self._catalog("station_name")
        self._station_aliases = self._build_station_aliases()
        self._station_lines = self._build_station_line_index()
        self._document_features = {
            self._document_key(document): self._query_features(document.page_content)
            for document in documents
        }
        self._feature_frequency = Counter(
            feature
            for features in self._document_features.values()
            for feature in features
        )

    def _catalog(self, field: str) -> list[str]:
        values = {
            str(document.metadata.get(field, "")).strip()
            for document in self.documents
            if str(document.metadata.get(field, "")).strip()
        }
        return sorted(values, key=len, reverse=True)

    def _build_station_line_index(self) -> dict[str, set[str]]:
        index: dict[str, set[str]] = {}
        for document in self.documents:
            line_name = str(document.metadata.get("line_name", "")).strip()
            station_name = str(document.metadata.get("station_name", "")).strip()
            destination = str(document.metadata.get("destination_station", "")).strip()
            if not line_name:
                continue
            for name in (station_name, destination):
                if name:
                    index.setdefault(name, set()).add(line_name)
        return index

    def _build_station_aliases(self) -> list[tuple[str, str]]:
        aliases: dict[str, str] = {}
        suffixes = ("公交枢纽站", "枢纽站", "公交站", "站")
        for station in self._station_names:
            aliases.setdefault(station, station)
            for suffix in suffixes:
                if station.endswith(suffix) and len(station) - len(suffix) >= 2:
                    aliases.setdefault(station[: -len(suffix)], station)
        return sorted(
            aliases.items(),
            key=lambda item: len(item[0]),
            reverse=True,
        )

    @staticmethod
    def _document_key(document: Document) -> str:
        metadata = document.metadata
        stable_id = str(metadata.get("sample_id") or "").strip()
        if stable_id:
            return f"sample:{stable_id}"
        digest = hashlib.sha256(document.page_content.encode("utf-8")).hexdigest()
        return f"content:{digest}"

    @staticmethod
    def _query_features(text: str) -> set[str]:
        normalized = re.sub(r"\s+", "", text.lower())
        features = set(re.findall(r"[a-z0-9]+", normalized))
        chinese = "".join(re.findall(r"[\u4e00-\u9fff]", normalized))
        for size in (2, 3):
            features.update(
                chinese[index : index + size]
                for index in range(max(0, len(chinese) - size + 1))
            )
        return features

    def extract_filters(self, query: str) -> dict[str, str]:
        filters: dict[str, str] = {}
        line_matches = [
            (query.find(value), -len(value), value)
            for value in self._line_names
            if value in query
        ]
        if line_matches:
            filters["line_name"] = min(line_matches)[2]
        station_matches = self._station_mentions(query)
        if station_matches:
            origin = station_matches[0][2]
            filters["station_name"] = origin
            destinations = [match[2] for match in station_matches[1:] if match[2] != origin]
            if "line_name" not in filters and destinations:
                origin_lines = self._station_lines.get(origin, set())
                destination_lines = self._station_lines.get(destinations[0], set())
                shared_lines = origin_lines & destination_lines
                if len(shared_lines) == 1:
                    filters["line_name"] = next(iter(shared_lines))
        for value in WEATHER_TERMS:
            if value in query:
                filters["weather"] = value
                break
        return filters

    def _station_mentions(self, query: str) -> list[tuple[int, int, str]]:
        matches: dict[str, tuple[int, int, str]] = {}
        for alias, station in self._station_aliases:
            position = query.find(alias)
            if position < 0:
                continue
            candidate = (position, -len(alias), station)
            current = matches.get(station)
            if current is None or candidate < current:
                matches[station] = candidate
        return sorted(matches.values())

    def resolve_trip_entities(self, query: str) -> TripEntities:
        mentions = self._station_mentions(query)
        origin = mentions[0][2] if mentions else None
        destination = mentions[1][2] if len(mentions) > 1 else None
        aliases: dict[str, str] = {}
        for position, negative_length, station in mentions[:2]:
            alias = query[position : position - negative_length]
            aliases[alias] = station
        return TripEntities(origin, destination, aliases)

    @staticmethod
    def _metadata_matches(document: Document, filters: dict[str, str]) -> bool:
        return all(str(document.metadata.get(key, "")) == value for key, value in filters.items())

    def _keyword_ranked(
        self,
        query: str,
        filters: dict[str, str],
        limit: int,
        destination_hint: str | None = None,
    ) -> list[Document]:
        query_features = self._query_features(query)
        query_weight = sum(self._feature_weight(feature) for feature in query_features)
        scored: list[tuple[float, Document]] = []
        for document in self.documents:
            if filters and not self._metadata_matches(document, filters):
                continue
            content = document.page_content.lower()
            document_features = self._document_features[self._document_key(document)]
            shared_features = query_features & document_features
            if not shared_features:
                continue
            score = sum(self._feature_weight(feature) for feature in shared_features)
            score /= max(query_weight, 1.0)
            for value in filters.values():
                if value and value in content:
                    score += 0.25
            if (
                destination_hint
                and str(document.metadata.get("destination_station", ""))
                == destination_hint
            ):
                score += 1.0
            scored.append((score, document))
        scored.sort(key=lambda item: item[0], reverse=True)
        return [document for _, document in scored[:limit]]

    def _feature_weight(self, feature: str) -> float:
        document_frequency = self._feature_frequency.get(feature, 0)
        return math.log((len(self.documents) + 1) / (document_frequency + 1)) + 1.0

    def retrieve(self, query: str, k: int = 5, fetch_k: int = 30) -> tuple[list[RetrievalHit], dict[str, str]]:
        filters = self.extract_filters(query)
        station_mentions = self._station_mentions(query)
        destination_hint = station_mentions[1][2] if len(station_mentions) > 1 else None
        vector_documents = self.vector_store.similarity_search(query, k=fetch_k)
        if filters:
            vector_documents = [
                document
                for document in vector_documents
                if self._metadata_matches(document, filters)
            ]
        keyword_documents = self._keyword_ranked(
            query,
            filters,
            fetch_k,
            destination_hint=destination_hint,
        )

        scores: dict[str, float] = {}
        vector_ranks: dict[str, int] = {}
        keyword_ranks: dict[str, int] = {}
        for rank, document in enumerate(vector_documents, start=1):
            key = self._document_key(document)
            scores[key] = scores.get(key, 0.0) + 1.0 / (60 + rank)
            vector_ranks[key] = rank
            self._documents_by_key.setdefault(key, document)
        for rank, document in enumerate(keyword_documents, start=1):
            key = self._document_key(document)
            scores[key] = scores.get(key, 0.0) + 1.25 / (60 + rank)
            keyword_ranks[key] = rank

        ranked_keys = sorted(scores, key=scores.get, reverse=True)
        candidate_limit = max(k, 20) if self.reranker else k
        ranked_keys = ranked_keys[:candidate_limit]
        hits = [
            RetrievalHit(
                document=self._documents_by_key[key],
                score=round(scores[key], 6),
                vector_rank=vector_ranks.get(key),
                keyword_rank=keyword_ranks.get(key),
            )
            for key in ranked_keys
        ]
        if self.reranker and hits:
            reranker_scores = self.reranker.score(
                query,
                [hit.document.page_content for hit in hits],
            )
            hits = [
                RetrievalHit(
                    document=hit.document,
                    score=round(score, 6),
                    vector_rank=hit.vector_rank,
                    keyword_rank=hit.keyword_rank,
                )
                for hit, score in zip(hits, reranker_scores)
            ]
            hits.sort(key=lambda hit: hit.score, reverse=True)
        hits = hits[:k]
        return hits, filters
