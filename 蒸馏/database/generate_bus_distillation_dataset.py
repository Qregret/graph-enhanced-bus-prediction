import hashlib
import random
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd


NUM_SAMPLES = 10000
OUTPUT_FILE = "bus_distillation_dataset.csv"
RANDOM_SEED = 20260411

BASE_DIR = Path(__file__).resolve().parent
SOURCE_FILE = BASE_DIR.parent / "bus_teacher_labeling" / "bus_distillation_dataset.csv"
OUTPUT_PATH = BASE_DIR / OUTPUT_FILE

WEATHER_NORMAL = ["晴", "多云", "阴", "小雨"]
WEATHER_EXTREME = ["大雨", "暴雨", "雷阵雨", "大雪"]
HOT_STATION_KEYWORDS = ["广场", "图书馆", "学校", "中学", "枢纽", "中心", "桥", "高笋塘", "人武部", "景区"]


def make_hash(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def load_real_assets() -> tuple[list[dict], list[dict]]:
    if not SOURCE_FILE.exists():
        raise FileNotFoundError(f"未找到真实底表: {SOURCE_FILE}")

    df = pd.read_csv(SOURCE_FILE)

    if not {"line_id", "line_name", "station_id", "station_name", "total_vehicle"}.issubset(df.columns):
        raise RuntimeError("真实底表缺少 line_id / line_name / station_id / station_name / total_vehicle 字段")

    line_df = (
        df[["line_id", "line_name", "total_vehicle"]]
        .drop_duplicates(subset=["line_id", "line_name"])
        .sort_values(["line_name", "line_id"])
        .reset_index(drop=True)
    )
    lines = [
        {
            "id": str(row["line_id"]),
            "name": str(row["line_name"]),
            "total_veh": int(row["total_vehicle"]),
        }
        for _, row in line_df.iterrows()
    ]

    station_df = (
        df[["station_id", "station_name"]]
        .drop_duplicates(subset=["station_id", "station_name"])
        .sort_values(["station_name", "station_id"])
        .reset_index(drop=True)
    )
    stations = [
        {
            "id": str(row["station_id"]) if pd.notna(row["station_id"]) else make_hash(str(row["station_name"])),
            "name": str(row["station_name"]),
        }
        for _, row in station_df.iterrows()
    ]

    if not lines or not stations:
        raise RuntimeError("真实底表中未解析出有效线路或站点")

    return lines, stations


def pick_station_for_line(line_name: str, stations: list[dict]) -> dict:
    hot_candidates = [station for station in stations if any(keyword in station["name"] for keyword in HOT_STATION_KEYWORDS)]
    if "105" in line_name or "202" in line_name:
        return random.choice(hot_candidates or stations)
    return random.choice(stations)


def scenario_base_time() -> datetime:
    return datetime(2024, 4, 7, 6, 0) + timedelta(minutes=random.randint(0, 1080))


def safe_vehicle_count(total_vehicle: int, low_ratio: float, high_ratio: float) -> int:
    return max(1, min(total_vehicle, int(round(total_vehicle * random.uniform(low_ratio, high_ratio)))))


def generate_realistic_sample(lines: list[dict], stations: list[dict]) -> dict:
    scenario = np.random.choice(["A", "B", "C", "D", "E"], p=[0.40, 0.15, 0.10, 0.15, 0.20])
    line = random.choice(lines)
    station = pick_station_for_line(line["name"], stations)
    time_base = scenario_base_time()

    total_vehicle = max(1, int(line["total_veh"]))
    temperature = round(random.uniform(5.0, 35.0), 1)
    is_peak = 0
    weather = random.choice(WEATHER_NORMAL)

    if scenario == "A":
        is_peak = 1
        congestion = round(random.uniform(0.50, 0.75), 3)
        headway = round(random.uniform(8.0, 12.0), 1)
        online_vehicle = safe_vehicle_count(total_vehicle, 0.70, 0.85)
        inflow_30m = int(random.uniform(15, 60))
        crowding = round(random.uniform(0.50, 0.80), 3)
    elif scenario == "B":
        is_peak = random.choice([0, 1])
        weather = random.choice(WEATHER_EXTREME)
        congestion = round(random.uniform(0.80, 0.98), 3)
        headway = round(random.uniform(15.0, 30.0), 1)
        online_vehicle = safe_vehicle_count(total_vehicle, 0.80, 0.95)
        inflow_30m = int(random.uniform(40, 120) * (headway / 10))
        crowding = round(random.uniform(0.85, 1.00), 3)
    elif scenario == "C":
        is_peak = 0
        congestion = round(random.uniform(0.90, 1.00), 3)
        headway = round(random.uniform(20.0, 45.0), 1)
        online_vehicle = safe_vehicle_count(total_vehicle, 0.60, 0.80)
        inflow_30m = int(random.uniform(5, 20))
        crowding = round(random.uniform(0.30, 0.60), 3)
    elif scenario == "D":
        is_peak = random.choice([0, 1])
        congestion = round(random.uniform(0.40, 0.60), 3)
        headway = round(random.uniform(8.0, 15.0), 1)
        online_vehicle = safe_vehicle_count(total_vehicle, 0.60, 0.80)
        inflow_30m = int(random.uniform(80, 200))
        crowding = round(random.uniform(0.70, 0.95), 3)
    else:
        is_peak = 1
        congestion = round(random.uniform(0.60, 0.80), 3)
        headway = round(random.uniform(8.0, 12.0), 1)
        online_vehicle = total_vehicle
        inflow_30m = int(random.uniform(30, 80))
        crowding = round(random.uniform(0.75, 0.95), 3)

    if any(keyword in station["name"] for keyword in HOT_STATION_KEYWORDS):
        inflow_30m = int(inflow_30m * random.uniform(1.05, 1.35))
        crowding = round(min(1.0, crowding + random.uniform(0.03, 0.08)), 3)

    outflow_30m = int(max(1, inflow_30m * random.uniform(0.2, 0.8)))

    return {
        "time": time_base.strftime("%Y-%m-%d %H:%M:%S"),
        "line_id": line["id"],
        "line_name": line["name"],
        "station_id": station["id"],
        "station_name": station["name"],
        "inflow_30m": inflow_30m,
        "outflow_30m": outflow_30m,
        "crowding": crowding,
        "congestion": congestion,
        "weather": weather,
        "temperature": temperature,
        "online_vehicle": online_vehicle,
        "total_vehicle": total_vehicle,
        "headway": headway,
        "is_peak": is_peak,
    }


def main() -> None:
    random.seed(RANDOM_SEED)
    np.random.seed(RANDOM_SEED)
    BASE_DIR.mkdir(parents=True, exist_ok=True)

    lines, stations = load_real_assets()
    print(f"正在基于真实底表生成 {NUM_SAMPLES} 条数据...")
    print(f"线路数: {len(lines)}，站点数: {len(stations)}")

    data = [generate_realistic_sample(lines, stations) for _ in range(NUM_SAMPLES)]
    df = pd.DataFrame(data)
    df = df.sort_values(by="time").reset_index(drop=True)

    columns_order = [
        "time",
        "line_id",
        "line_name",
        "station_id",
        "station_name",
        "inflow_30m",
        "outflow_30m",
        "crowding",
        "congestion",
        "weather",
        "temperature",
        "online_vehicle",
        "total_vehicle",
        "headway",
        "is_peak",
    ]
    df = df[columns_order]
    df.to_csv(OUTPUT_PATH, index=False, encoding="utf-8-sig")

    print(f"数据生成完毕，保存至: {OUTPUT_PATH}")
    print("\n--- 格式核对预览 ---")
    print(",".join(df.columns))
    print(",".join(map(str, df.iloc[0].values)))


if __name__ == "__main__":
    main()
