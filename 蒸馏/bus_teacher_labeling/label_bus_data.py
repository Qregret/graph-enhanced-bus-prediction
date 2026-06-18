import json
import random
from pathlib import Path
from typing import Dict, List, Tuple

import pandas as pd


DEFAULT_MAX_SAMPLES = 5500
DEFAULT_TRAIN_SIZE = 5000
DEFAULT_VAL_SIZE = 500
RANDOM_SEED = 20260411

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_INPUT = BASE_DIR.parent / "database" / "bus_distillation_dataset.csv"
DEFAULT_LABELED_OUTPUT = BASE_DIR / "labeled_samples_glm.csv"
DEFAULT_TRAIN_OUTPUT = BASE_DIR / "train.jsonl"
DEFAULT_VAL_OUTPUT = BASE_DIR / "valid.jsonl"
DEFAULT_PROMPT_PREVIEW = BASE_DIR / "prompt_preview.txt"

FEATURE_COLUMNS = [
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


def build_prompt(batch: List[Dict]) -> str:
    payload = json.dumps(batch, ensure_ascii=False, indent=2)
    return f"""
你是智慧公交调度与出行建议专家。请分析以下公交运行快照，为每一条样本生成专业的调度建议和用户回复。

【专家决策多因素量化标准】
1. 拥堵与拥挤：crowding 或 congestion > 0.8 视为极端状态。
2. 资源约束：若 online_vehicle == total_vehicle，说明运力已满，严禁建议 add_trip (加车)。
3. 天气联动：若 weather 包含"雨"或"雪"，且 congestion > 0.8，应优先建议 user_diversion (分流)。
4. 候车压力：若 headway > 15分钟 且 inflow_30m > 50人，属于严重滞留风险。

【输出要求】
1. 只返回合法 JSON 数组。
2. 必须先输出 reasoning (推理过程)，强制执行思维链分析。
3. assistant_answer 采用三段式：[关怀告知] -> [数据播报] -> [决策方案]。语言要自然、专业且具备同理心。

输出示例：
[
  {{
    "sample_id": "TA0001",
    "reasoning": "高峰期且路面瘫痪(0.95)，加车无意义，且发车间隔已达20分，需引导分流。",
    "dispatch_action": "user_diversion",
    "assistant_answer": "您好！目前受高峰及道路极端拥堵影响，105路发车间隔已拉长至 20 分钟且车内非常拥挤。为了您的出行体验，建议您暂时错峰出发，或尝试步行至附近地铁站换乘。"
  }}
]

【待分析样本】
{payload}
""".strip()


def load_dataset(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path)
    missing = [column for column in FEATURE_COLUMNS if column not in df.columns]
    if missing:
        raise RuntimeError(f"输入数据缺少字段: {missing}")

    numeric_columns = [
        "inflow_30m",
        "outflow_30m",
        "crowding",
        "congestion",
        "temperature",
        "online_vehicle",
        "total_vehicle",
        "headway",
        "is_peak",
    ]
    for column in numeric_columns:
        df[column] = pd.to_numeric(df[column], errors="coerce").fillna(0)

    df = df.reset_index(drop=True).copy()
    df.insert(0, "source_row_id", df.index.astype(int))
    return df


def choose_samples(df: pd.DataFrame, total_needed: int) -> pd.DataFrame:
    scored = df.assign(
        pressure_score=(
            df["is_peak"] * 3.0
            + df["crowding"] * 2.8
            + df["congestion"] * 2.2
            + df["inflow_30m"] / max(float(df["inflow_30m"].max()), 1.0)
            + df["headway"] / max(float(df["headway"].max()), 1.0)
        )
    )

    hot_size = min(len(scored), max(1, total_needed // 2))
    hot = scored.sort_values(
        by=["pressure_score", "inflow_30m", "crowding", "congestion"],
        ascending=[False, False, False, False],
    ).head(hot_size)

    remaining = scored.drop(index=hot.index)
    cold = remaining.sample(
        n=min(len(remaining), total_needed - len(hot)),
        random_state=RANDOM_SEED,
        replace=False,
    )

    sampled = (
        pd.concat([hot, cold], ignore_index=True)
        .sample(frac=1.0, random_state=RANDOM_SEED)
        .reset_index(drop=True)
    )
    sampled.insert(0, "sample_id", [f"S{index + 1:05d}" for index in range(len(sampled))])
    return sampled


def contains_bad_weather(weather: str) -> bool:
    weather = str(weather)
    return any(token in weather for token in ["雨", "雪", "暴雨", "雷阵雨", "大雪"])


def infer_action(row: pd.Series) -> Tuple[str, str, float, str, str]:
    crowding = float(row["crowding"])
    congestion = float(row["congestion"])
    headway = float(row["headway"])
    inflow = int(row["inflow_30m"])
    online = int(row["online_vehicle"])
    total = int(row["total_vehicle"])
    weather = str(row["weather"])
    line_name = str(row["line_name"])
    station_name = str(row["station_name"])
    is_peak = int(row["is_peak"]) == 1

    extreme_load = crowding > 0.8 or congestion > 0.8
    full_capacity = total > 0 and online >= total
    bad_weather = contains_bad_weather(weather)
    waiting_pressure = headway > 15 and inflow > 50

    if bad_weather and congestion > 0.8:
        reasoning = f"{weather}天气叠加道路拥堵指数{congestion:.2f}，{line_name}{station_name}继续排队风险高，优先引导分流。"
        answer = (
            f"您好，目前{station_name}附近天气较差，出行舒适度和安全性都受到了影响。"
            f"{line_name}当前道路拥堵指数约为{congestion:.2f}，站点拥挤度约为{crowding:.2f}，继续原地候车会比较难受。"
            f"建议您优先考虑分流换乘、短距离步行到附近其他站点，或错峰出发。"
        )
        return "user_diversion", "high", 0.95, reasoning, answer

    if full_capacity and extreme_load:
        reasoning = f"{line_name}当前在线车辆已满载运行({online}/{total})，但拥挤或拥堵仍处高位，不能再直接加车，应先调整发车间隔。"
        answer = (
            f"您好，当前这条线的运力已经基本全部投放，短时间内再加车的空间不大。"
            f"系统监测到{station_name}站点拥挤度{crowding:.2f}、道路拥堵指数{congestion:.2f}，发车节奏已经需要重新平衡。"
            f"建议优先执行间隔调整，并提醒乘客分散候车，避免站台持续堆积。"
        )
        return "interval_adjust", "high", 0.92, reasoning, answer

    if waiting_pressure and not full_capacity:
        reasoning = f"{station_name}发车间隔已达{headway:.1f}分钟，未来30分钟进站客流{inflow}人，且仍有剩余运力，适合增发班次。"
        answer = (
            f"您好，当前{station_name}的候车压力已经明显抬升。"
            f"系统预测未来30分钟仍会有约{inflow}人上车，当前发车间隔约{headway:.1f}分钟，等待时间偏长。"
            f"建议及时增发班次，尽快释放站台压力，避免形成连续滞留。"
        )
        return "add_trip", "high", 0.91, reasoning, answer

    if extreme_load and not full_capacity:
        reasoning = f"{station_name}局部拥挤度{crowding:.2f}、拥堵指数{congestion:.2f}偏高，但线路仍有运力余量，更适合短线折返缓解局部压力。"
        answer = (
            f"您好，目前这条线整体还能维持运行，但{station_name}局部压力已经比较突出。"
            f"从监测结果看，车内拥挤和道路拥堵都处在偏高水平，不过系统仍检测到一定的运力余量。"
            f"建议优先采用短线折返，把运力更快补到高压区段。"
        )
        return "short_turn", "medium", 0.87, reasoning, answer

    if is_peak and (crowding > 0.65 or congestion > 0.65):
        reasoning = f"高峰期线路波动明显，{line_name}{station_name}虽未失控，但已进入压力抬升区间，更适合轻量化节奏调优。"
        answer = (
            f"您好，目前处于高峰时段，线路客流和路况都在持续波动。"
            f"{station_name}附近暂未达到极端拥堵，但拥挤和拥堵水平已经明显高于平峰状态。"
            f"建议适度收紧发车间隔，提前稳住高峰波动。"
        )
        return "interval_adjust", "medium", 0.82, reasoning, answer

    reasoning = f"{line_name}{station_name}当前客流、拥挤度和道路状态整体平稳，暂无明显调度干预必要。"
    answer = (
        f"您好，当前{station_name}附近的运行状态整体比较平稳。"
        f"系统监测到这条线的车内拥挤、道路拥堵和候车间隔都还处在可接受区间。"
        f"建议维持现有发车节奏，继续观察后续客流变化即可。"
    )
    return "none", "low", 0.78, reasoning, answer


def build_labeled_dataframe(sample_df: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for _, row in sample_df.iterrows():
        dispatch_action, priority, confidence, reasoning, assistant_answer = infer_action(row)
        record = row.to_dict()
        record.update(
            {
                "dispatch_action": dispatch_action,
                "priority": priority,
                "confidence": round(confidence, 3),
                "reasoning": reasoning,
                "reason": reasoning,
                "assistant_answer": assistant_answer,
                "status": "ok",
            }
        )
        rows.append(record)
    return pd.DataFrame(rows)


def build_split_frames(labeled_df: pd.DataFrame, train_size: int, val_size: int) -> Tuple[pd.DataFrame, pd.DataFrame]:
    shuffled = labeled_df.sample(frac=1.0, random_state=RANDOM_SEED).reset_index(drop=True)
    train_df = shuffled.head(train_size).copy()
    val_df = shuffled.iloc[train_size : train_size + val_size].copy()
    return train_df, val_df


def write_jsonl_records(df: pd.DataFrame, path: Path) -> None:
    with path.open("w", encoding="utf-8") as file:
        for record in df.to_dict(orient="records"):
            file.write(json.dumps(record, ensure_ascii=False) + "\n")


def main() -> None:
    random.seed(RANDOM_SEED)
    total_needed = DEFAULT_TRAIN_SIZE + DEFAULT_VAL_SIZE

    df = load_dataset(DEFAULT_INPUT)
    sample_df = choose_samples(df, min(DEFAULT_MAX_SAMPLES, total_needed))
    labeled_df = build_labeled_dataframe(sample_df)

    ordered_columns = [
        "source_row_id",
        *FEATURE_COLUMNS,
        "sample_id",
        "dispatch_action",
        "priority",
        "confidence",
        "reasoning",
        "reason",
        "assistant_answer",
        "pressure_score",
        "status",
    ]
    labeled_df = labeled_df[[column for column in ordered_columns if column in labeled_df.columns]]

    train_df, val_df = build_split_frames(labeled_df, DEFAULT_TRAIN_SIZE, DEFAULT_VAL_SIZE)
    labeled_df.to_csv(DEFAULT_LABELED_OUTPUT, index=False, encoding="utf-8-sig")
    write_jsonl_records(train_df, DEFAULT_TRAIN_OUTPUT)
    write_jsonl_records(val_df, DEFAULT_VAL_OUTPUT)

    prompt_preview = build_prompt(sample_df.head(2).to_dict(orient="records"))
    DEFAULT_PROMPT_PREVIEW.write_text(prompt_preview, encoding="utf-8")

    print(f"基础样本总数: {len(df)}")
    print(f"本次生成样本: {len(labeled_df)}")
    print(f"训练集: {len(train_df)}")
    print(f"验证集: {len(val_df)}")


if __name__ == "__main__":
    main()
