from __future__ import annotations

import csv
import hashlib
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

from langchain_core.documents import Document


PROJECT_DIR = Path(__file__).resolve().parents[2]
DEFAULT_ADVICE_CSV = (
    PROJECT_DIR / "蒸馏" / "出行问答训练" / "travel_advice_dataset_glm.csv"
)
DEFAULT_BUS_CSV = PROJECT_DIR / "蒸馏" / "database" / "bus_distillation_dataset.csv"
DEFAULT_OUTPUT_DIR = Path(__file__).resolve().parents[1] / "data" / "processed"


@dataclass
class BuildStats:
    advice_rows: int = 0
    accepted_advice: int = 0
    rejected_empty: int = 0
    rejected_status: int = 0
    rejected_duplicate: int = 0
    station_rows: int = 0
    unique_station_documents: int = 0
    knowledge_documents: int = 0
    evaluation_documents: int = 0


def _normalized(value: object) -> str:
    return re.sub(r"\s+", "", str(value or "")).lower()


def _text(row: dict[str, str], name: str, fallback: str = "未知") -> str:
    value = str(row.get(name, "")).strip()
    return value or fallback


def _number(row: dict[str, str], name: str) -> float | None:
    try:
        return float(row[name])
    except (KeyError, TypeError, ValueError):
        return None


def _display_number(value: float | None, suffix: str = "") -> str:
    if value is None:
        return "未知"
    formatted = f"{value:.2f}".rstrip("0").rstrip(".")
    return f"{formatted}{suffix}"


def _advice_document(row: dict[str, str]) -> Document:
    page_content = "\n".join(
        [
            f"用户问题：{_text(row, 'user_question')}",
            (
                f"公交场景：{_text(row, 'time')}，乘客位于{_text(row, 'current_location')}，"
                f"计划乘坐{_text(row, 'line_name')}前往{_text(row, 'destination_station')}。"
            ),
            (
                f"运行状态：天气{_text(row, 'weather')}，温度{_display_number(_number(row, 'temperature'), '℃')}，"
                f"近30分钟上车{_display_number(_number(row, 'inflow_30m'), '人')}、"
                f"下车{_display_number(_number(row, 'outflow_30m'), '人')}，"
                f"拥挤度{_display_number(_number(row, 'crowding'))}，"
                f"道路拥堵度{_display_number(_number(row, 'congestion'))}，"
                f"发车间隔{_display_number(_number(row, 'headway'), '分钟')}，"
                f"在线车辆{_display_number(_number(row, 'online_vehicle'), '辆')}/"
                f"总车辆{_display_number(_number(row, 'total_vehicle'), '辆')}。"
            ),
            f"分析依据：{_text(row, 'reasoning')}",
            f"参考建议：{_text(row, 'assistant_answer')}",
        ]
    )
    return Document(
        page_content=page_content,
        metadata={
            "document_type": "advice_case",
            "sample_id": _text(row, "sample_id", ""),
            "line_id": _text(row, "line_id", ""),
            "line_name": _text(row, "line_name", ""),
            "station_id": _text(row, "station_id", ""),
            "station_name": _text(row, "station_name", ""),
            "destination_station": _text(row, "destination_station", ""),
            "weather": _text(row, "weather", ""),
            "scene": "peak" if _text(row, "is_peak", "0") == "1" else "off_peak",
            "advice_level": _text(row, "advice_level", ""),
            "source": "travel_advice_dataset_glm.csv",
        },
    )


def _is_evaluation_document(document: Document, ratio: float) -> bool:
    metadata = document.metadata
    group_key = "|".join(
        [
            str(metadata.get("line_id", "")),
            str(metadata.get("station_id", "")),
            _normalized(document.page_content.splitlines()[0]),
        ]
    )
    bucket = int(hashlib.sha256(group_key.encode("utf-8")).hexdigest()[:8], 16)
    return bucket % 10_000 < round(ratio * 10_000)


def load_advice_documents(
    source: Path = DEFAULT_ADVICE_CSV,
    evaluation_ratio: float = 0.1,
    stats: BuildStats | None = None,
) -> tuple[list[Document], list[Document]]:
    if not 0 <= evaluation_ratio < 1:
        raise ValueError("evaluation_ratio 必须大于等于 0 且小于 1。")
    stats = stats or BuildStats()
    knowledge: list[Document] = []
    evaluation: list[Document] = []
    seen: set[str] = set()

    with source.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            stats.advice_rows += 1
            question = _text(row, "user_question", "")
            answer = _text(row, "assistant_answer", "")
            if len(question) < 8 or len(answer) < 20 or answer == "暂无建议。":
                stats.rejected_empty += 1
                continue
            if row.get("status") and _text(row, "status", "").lower() != "ok":
                stats.rejected_status += 1
                continue

            duplicate_key = "|".join(
                [
                    _normalized(question),
                    _normalized(row.get("line_id")),
                    _normalized(row.get("station_id")),
                ]
            )
            if duplicate_key in seen:
                stats.rejected_duplicate += 1
                continue
            seen.add(duplicate_key)

            document = _advice_document(row)
            stats.accepted_advice += 1
            target = evaluation if _is_evaluation_document(document, evaluation_ratio) else knowledge
            target.append(document)

    return knowledge, evaluation


def load_station_documents(
    source: Path = DEFAULT_BUS_CSV,
    stats: BuildStats | None = None,
) -> list[Document]:
    stats = stats or BuildStats()
    stations: dict[tuple[str, str], Document] = {}

    with source.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            stats.station_rows += 1
            line_id = _text(row, "line_id", "")
            station_id = _text(row, "station_id", "")
            if not line_id or not station_id:
                continue
            key = (line_id, station_id)
            if key in stations:
                continue
            line_name = _text(row, "line_name")
            station_name = _text(row, "station_name")
            stations[key] = Document(
                page_content=f"站点信息：{station_name}是{line_name}线路的公交站点。",
                metadata={
                    "document_type": "station_profile",
                    "sample_id": "",
                    "line_id": line_id,
                    "line_name": line_name,
                    "station_id": station_id,
                    "station_name": station_name,
                    "destination_station": "",
                    "weather": "",
                    "scene": "station_profile",
                    "advice_level": "",
                    "source": "bus_distillation_dataset.csv",
                },
            )

    stats.unique_station_documents = len(stations)
    return list(stations.values())


def build_documents(
    advice_source: Path = DEFAULT_ADVICE_CSV,
    bus_source: Path = DEFAULT_BUS_CSV,
    evaluation_ratio: float = 0.1,
) -> tuple[list[Document], list[Document], BuildStats]:
    stats = BuildStats()
    knowledge, evaluation = load_advice_documents(
        advice_source,
        evaluation_ratio=evaluation_ratio,
        stats=stats,
    )
    knowledge.extend(load_station_documents(bus_source, stats=stats))
    stats.knowledge_documents = len(knowledge)
    stats.evaluation_documents = len(evaluation)
    return knowledge, evaluation, stats


def _document_record(document: Document) -> dict[str, object]:
    return {
        "page_content": document.page_content,
        "metadata": document.metadata,
    }


def _write_jsonl(path: Path, documents: Iterable[Document]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for document in documents:
            handle.write(json.dumps(_document_record(document), ensure_ascii=False) + "\n")


def save_documents(
    knowledge: list[Document],
    evaluation: list[Document],
    stats: BuildStats,
    output_dir: Path = DEFAULT_OUTPUT_DIR,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    _write_jsonl(output_dir / "knowledge_documents.jsonl", knowledge)
    _write_jsonl(output_dir / "evaluation_documents.jsonl", evaluation)
    with (output_dir / "manifest.json").open("w", encoding="utf-8") as handle:
        json.dump(asdict(stats), handle, ensure_ascii=False, indent=2)
