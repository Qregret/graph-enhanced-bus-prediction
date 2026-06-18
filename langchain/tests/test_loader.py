from __future__ import annotations

import csv
from pathlib import Path

from retrieval.loader import BuildStats, load_advice_documents, load_station_documents


def write_csv(path: Path, fieldnames: list[str], rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def test_advice_loader_filters_invalid_and_duplicate_rows(tmp_path):
    path = tmp_path / "advice.csv"
    base = {
        "sample_id": "TA1",
        "time": "2024-04-07 06:00:00",
        "line_id": "line-1",
        "line_name": "206路",
        "station_id": "station-1",
        "station_name": "沙溪温泉",
        "user_question": "现在乘坐206路是否合适？",
        "assistant_answer": "当前运行情况基本正常，建议结合实时到站信息提前候车。",
        "reasoning": "客流和发车间隔均处于正常范围。",
        "status": "ok",
    }
    rows = [base, dict(base), {**base, "sample_id": "TA2", "assistant_answer": ""}]
    write_csv(path, list(base), rows)
    stats = BuildStats()

    knowledge, evaluation = load_advice_documents(
        path,
        evaluation_ratio=0,
        stats=stats,
    )

    assert len(knowledge) == 1
    assert evaluation == []
    assert stats.rejected_duplicate == 1
    assert stats.rejected_empty == 1
    assert "用户问题" in knowledge[0].page_content
    assert knowledge[0].metadata["document_type"] == "advice_case"
    assert "destination_station" in knowledge[0].metadata


def test_station_loader_deduplicates_line_station_pairs(tmp_path):
    path = tmp_path / "bus.csv"
    fields = ["line_id", "line_name", "station_id", "station_name"]
    row = {
        "line_id": "line-1",
        "line_name": "206路",
        "station_id": "station-1",
        "station_name": "沙溪温泉",
    }
    write_csv(path, fields, [row, row])
    stats = BuildStats()

    documents = load_station_documents(path, stats=stats)

    assert len(documents) == 1
    assert stats.station_rows == 2
    assert documents[0].metadata["source"] == "bus_distillation_dataset.csv"
