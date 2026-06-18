import argparse
import json
import math
import os
import sys
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Dict, List

import pandas as pd
from openai import OpenAI

DEFAULT_MODEL = "glm-4.5-air"
DEFAULT_MAX_CALLS = 200
DEFAULT_BATCH_SIZE = 20  # 【优化】提升单次请求样本数，减少网络握手次数
DEFAULT_WORKERS = 5  # 【优化】默认并发线程数
DEFAULT_MAX_SAMPLES = 1000
DEFAULT_TRAIN_SIZE = 800
DEFAULT_VAL_SIZE = 200
API_KEY = "YOUR_ZHIPU_API_KEY"

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_INPUT = BASE_DIR.parent / "database" / "bus_distillation_dataset.csv"

DEFAULT_LABELED_OUTPUT = BASE_DIR / "labeled_samples_glm.csv"
DEFAULT_TRAIN_OUTPUT = BASE_DIR / "train_samples_glm.csv"
DEFAULT_VAL_OUTPUT = BASE_DIR / "val_samples_glm.csv"
DEFAULT_CHECKPOINT = BASE_DIR / "label_checkpoint.json"

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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="使用 GLM 对公交蒸馏样本打标签，并输出 train/val CSV。")
    parser.add_argument("--input", default=str(DEFAULT_INPUT), help="输入 CSV 文件路径")
    parser.add_argument("--labeled-output", default=str(DEFAULT_LABELED_OUTPUT), help="完整打标结果输出 CSV")
    parser.add_argument("--train-output", default=str(DEFAULT_TRAIN_OUTPUT), help="训练集输出 CSV")
    parser.add_argument("--val-output", default=str(DEFAULT_VAL_OUTPUT), help="验证集输出 CSV")
    parser.add_argument("--checkpoint", default=str(DEFAULT_CHECKPOINT), help="断点文件路径")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="GLM 模型名称")
    parser.add_argument("--max-calls", type=int, default=DEFAULT_MAX_CALLS, help="最多调用次数")
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE, help="每次请求样本数")
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS, help="并发线程数")
    parser.add_argument("--max-samples", type=int, default=DEFAULT_MAX_SAMPLES, help="最多处理样本数")
    parser.add_argument("--train-size", type=int, default=DEFAULT_TRAIN_SIZE, help="训练集条数")
    parser.add_argument("--val-size", type=int, default=DEFAULT_VAL_SIZE, help="验证集条数")
    parser.add_argument("--resume", action="store_true", help="从 checkpoint 继续")
    parser.add_argument("--dry-run", action="store_true", help="只预览提示词，不调用模型")
    return parser.parse_args()


def require_api_key() -> str:
    api_key = API_KEY or os.getenv("ZHIPU_API_KEY") or os.getenv("GLM_API_KEY")
    if not api_key:
        raise RuntimeError("未找到 API Key。")
    return api_key


def build_client(api_key: str) -> OpenAI:
    return OpenAI(api_key=api_key, base_url="https://open.bigmodel.cn/api/paas/v4/")


def load_dataset(path: str) -> pd.DataFrame:
    df = pd.read_csv(path)
    rename_map = {
        "station_ic": "station_id",
        "station_n": "station_name",
        "inflow_30": "inflow_30m",
        "outflow_30": "outflow_30m",
        "online_veh": "online_vehicle",
        "total_veh": "total_vehicle",
        "headway_minutes": "headway",
    }
    df = df.rename(columns=rename_map)
    missing = [col for col in FEATURE_COLUMNS if col not in df.columns]
    if missing:
        raise RuntimeError(f"输入 CSV 缺少字段: {missing}")

    for col in ["inflow_30m", "outflow_30m", "crowding", "congestion", "temperature", "online_vehicle", "total_vehicle",
                "headway", "is_peak"]:
        df[col] = pd.to_numeric(df[col], errors="coerce").fillna(0)

    df = df.reset_index(drop=True).copy()
    df.insert(0, "source_row_id", df.index.astype(int))
    return df


def sample_rows(df: pd.DataFrame, max_samples: int) -> pd.DataFrame:
    sampled = (
        df.assign(
            pressure_score=(
                    df["is_peak"] * 3.0
                    + df["crowding"] * 2.8
                    + df["congestion"] * 2.2
                    + df["inflow_30m"] / max(float(df["inflow_30m"].max()), 1.0)
                    + df["headway"] / max(float(df["headway"].max()), 1.0)
            )
        )
        .sort_values(
            by=["pressure_score", "inflow_30m", "crowding", "congestion", "headway"],
            ascending=[False, False, False, False, False],
        )
        .head(max_samples)
        .reset_index(drop=True)
    )
    sampled.insert(0, "sample_id", [f"S{idx + 1:04d}" for idx in range(len(sampled))])
    return sampled


def load_checkpoint(path: str) -> Dict[str, Dict]:
    checkpoint_path = Path(path)
    if not checkpoint_path.exists():
        return {}
    with checkpoint_path.open("r", encoding="utf-8") as f:
        payload = json.load(f)
    return payload.get("labels", {})


def save_checkpoint(path: str, labels: Dict[str, Dict]) -> None:
    with Path(path).open("w", encoding="utf-8") as f:
        json.dump({"labels": labels}, f, ensure_ascii=False, indent=2)


def row_to_prompt_item(row: pd.Series) -> Dict:
    return {
        "sample_id": row["sample_id"],
        "source_row_id": int(row["source_row_id"]),
        "time": row["time"],
        "line_id": row["line_id"],
        "line_name": row["line_name"],
        "station_id": row["station_id"],
        "station_name": row["station_name"],
        "inflow_30m": int(row["inflow_30m"]),
        "outflow_30m": int(row["outflow_30m"]),
        "crowding": round(float(row["crowding"]), 3),
        "congestion": round(float(row["congestion"]), 3),
        "weather": row["weather"],
        "temperature": round(float(row["temperature"]), 1),
        "online_vehicle": int(row["online_vehicle"]),
        "total_vehicle": int(row["total_vehicle"]),
        "headway": round(float(row["headway"]), 1),
        "is_peak": int(row["is_peak"]),
    }


def build_prompt(batch: List[Dict]) -> str:
    payload = json.dumps(batch, ensure_ascii=False, indent=2)
    return f"""
你是资深的智慧公交调度专家。你的任务是分析公交运行快照，并输出结构化的调度决策标签，用于训练下游模型。

【输入特征说明】
- crowding/congestion: 0~1之间的指数（越接近1越拥挤/拥堵）
- inflow_30m: 未来30分钟预测入站客流
- online_vehicle / total_vehicle: 在线车辆数与总运力，当 online_vehicle 接近 total_vehicle 时代表运力资源紧张
- headway: 当前发车间隔（分钟）
- is_peak: 1代表高峰期，0代表平峰期

【调度决策逻辑树（严格遵循）】
1. add_trip (增加班次):
   - 触发条件: is_peak=1 且 crowding>0.7 且 inflow_30m激增，且当前运力充足 (online_vehicle < total_vehicle)。
2. interval_adjust (动态调频/拉大间隔):
   - 触发条件: crowding>0.7 且 congestion>0.7（路堵导致车过不来），或者运力已满载 (online_vehicle == total_vehicle)。
3. short_turn (区间车/掉头):
   - 触发条件: 仅局部站点 inflow_30m 极高，但全线 crowding 并不极度危险。
4. user_diversion (乘客引导分流):
   - 触发条件: 站点客流极大且面临恶劣天气(weather包含雨/雪) 或 道路极端拥堵(congestion>0.9)。
5. none (维持现状):
   - 触发条件: is_peak=0 且 crowding<0.5，整体运行平稳。

【输出要求与格式】
1. 只输出合法的 JSON 数组，禁止任何 Markdown 格式或额外解释文本。
2. 返回数组长度必须与输入样本数一致。
3. 必须先输出 reason，再输出动作和优先级，以此强制你先思考再决策！

输出示例：
[
  {{
    "sample_id": "S0001",
    "source_row_id": 102,
    "reason": "晚高峰且拥挤度达0.85，未来30分客流激增，运力有余量，需加车。",
    "dispatch_action": "add_trip",
    "priority": "high",
    "confidence": 0.92
  }},
  {{
    "sample_id": "S0002",
    "source_row_id": 103,
    "reason": "拥堵指数0.9道路瘫痪，虽拥挤但无车可加，建议调频等候。",
    "dispatch_action": "interval_adjust",
    "priority": "medium",
    "confidence": 0.85
  }}
]

【当前输入样本】
{payload}
""".strip()


def sanitize_content(content: str) -> str:
    text = content.strip()
    if text.startswith("```"):
        text = text.replace("```json", "").replace("```", "").strip()
    return text


def request_labels(client: OpenAI, model: str, batch: List[Dict]) -> List[Dict]:
    response = client.chat.completions.create(
        model=model,
        temperature=0,
        messages=[
            {"role": "system", "content": "你是严格的公交调度标签老师模型，只返回合法 JSON。"},
            {"role": "user", "content": build_prompt(batch)},
        ],
    )
    data = json.loads(sanitize_content(response.choices[0].message.content))
    if not isinstance(data, list):
        raise RuntimeError("模型返回结果不是 JSON 数组。")
    return data


def normalize_label(item: Dict) -> Dict:
    valid_actions = {"none", "add_trip", "short_turn", "interval_adjust", "user_diversion"}
    valid_priority = {"low", "medium", "high"}
    action = str(item.get("dispatch_action", "none")).strip()
    priority = str(item.get("priority", "low")).strip()
    confidence = float(item.get("confidence", 0.5))
    reason = str(item.get("reason", "")).strip()[:28] or "模型未给出明确说明"
    if action not in valid_actions:
        action = "none"
    if priority not in valid_priority:
        priority = "low"
    confidence = max(0.0, min(1.0, confidence))
    try:
        source_row_id = int(item.get("source_row_id"))
    except (TypeError, ValueError):
        source_row_id = -1
    return {
        "sample_id": str(item.get("sample_id", "")).strip(),
        "source_row_id": source_row_id,
        "dispatch_action": action,
        "priority": priority,
        "confidence": round(confidence, 3),
        "reason": reason,
    }


def attach_labels(sample_df: pd.DataFrame, labels: Dict[str, Dict]) -> pd.DataFrame:
    rows = []
    for _, row in sample_df.iterrows():
        record = row.to_dict()
        label = labels.get(record["sample_id"], {})
        record.update(
            {
                "source_row_id": int(label.get("source_row_id", record["source_row_id"])),
                "dispatch_action": label.get("dispatch_action"),
                "priority": label.get("priority"),
                "confidence": label.get("confidence"),
                "reason": label.get("reason"),
                "status": "ok" if label else "pending",
            }
        )
        rows.append(record)
    return pd.DataFrame(rows)


def build_split_frames(labeled_df: pd.DataFrame, train_size: int, val_size: int) -> tuple[pd.DataFrame, pd.DataFrame]:
    usable = labeled_df[labeled_df["status"] == "ok"].copy()
    usable = usable.sort_values(["source_row_id", "sample_id"]).reset_index(drop=True)
    ordered_columns = [
        "source_row_id",
        *FEATURE_COLUMNS,
        "sample_id",
        "dispatch_action",
        "priority",
        "confidence",
        "reason",
        "pressure_score",
        "status",
    ]
    ordered_columns = [col for col in ordered_columns if col in usable.columns]
    usable = usable[ordered_columns]
    train_df = usable.head(train_size).copy()
    val_df = usable.iloc[train_size:train_size + val_size].copy()
    return train_df, val_df


def main() -> None:
    args = parse_args()
    df = load_dataset(args.input)
    sample_df = sample_rows(df, args.max_samples)

    labels = load_checkpoint(args.checkpoint) if args.resume else {}
    pending_df = sample_df[~sample_df["sample_id"].isin(labels.keys())].reset_index(drop=True)

    print(f"输入样本数: {len(df)}")
    print(f"本次采样数: {len(sample_df)}")
    print(f"已完成标签数: {len(labels)}")
    print(f"待处理样本数: {len(pending_df)}")

    if not pending_df.empty:
        max_batches = max(1, math.floor(args.max_calls))
        pending_records = [row_to_prompt_item(row) for _, row in pending_df.iterrows()]
        batches = [pending_records[i:i + args.batch_size] for i in range(0, len(pending_records), args.batch_size)][
                  :max_batches]

        print(f"计划请求批次: {len(batches)} / 调用上限 {args.max_calls}")
        print(f"计划打标样本: {sum(len(batch) for batch in batches)}")

        if args.dry_run:
            print("\n===== DRY RUN PROMPT PREVIEW =====\n")
            print(build_prompt(batches[0])[:4000])
            print("\n===== END =====")
            return

        client = build_client(require_api_key())
        print(f"🚀 启动多线程并发打标，并发线程数: {args.workers}...")

        checkpoint_lock = threading.Lock()
        completed_batches = 0

        # 【优化】使用线程池并发发送网络请求
        with ThreadPoolExecutor(max_workers=args.workers) as executor:
            future_to_batch = {
                executor.submit(request_labels, client, args.model, batch): batch
                for batch in batches
            }

            for future in as_completed(future_to_batch):
                batch = future_to_batch[future]
                try:
                    result = future.result()
                    normalized = [normalize_label(item) for item in result]
                    mapped = {item["sample_id"]: item for item in normalized if item["sample_id"]}

                    missing_ids = [item["sample_id"] for item in batch if item["sample_id"] not in mapped]
                    if missing_ids:
                        print(f"⚠️ 警告: 模型返回缺少 sample_id: {missing_ids}")

                    # 【优化】使用线程锁确保多线程安全写入
                    with checkpoint_lock:
                        labels.update(mapped)
                        save_checkpoint(args.checkpoint, labels)
                        completed_batches += 1
                        print(f"[{completed_batches}/{len(batches)}] ok, 完成样本 {len(batch)} 条")

                except Exception as exc:
                    print(f"❌ 批次执行失败: {exc}")
                    print("错误已跳过，将继续执行其他批次...")

    labeled_df = attach_labels(sample_df, labels)
    train_df, val_df = build_split_frames(labeled_df, args.train_size, args.val_size)

    labeled_df.to_csv(args.labeled_output, index=False, encoding="utf-8-sig")
    train_df.to_csv(args.train_output, index=False, encoding="utf-8-sig")
    val_df.to_csv(args.val_output, index=False, encoding="utf-8-sig")

    print(f"\n打标文件: {args.labeled_output}")
    print(f"训练集: {args.train_output} ({len(train_df)})")
    print(f"验证集: {args.val_output} ({len(val_df)})")
    print(f"已完成标签: {(labeled_df['status'] == 'ok').sum()} / {len(labeled_df)}")

    if len(train_df) < args.train_size or len(val_df) < args.val_size:
        print(
            f"⚠️ 注意：当前可用标签不足，目标是 train={args.train_size}, val={args.val_size}，"
            f"实际只有 train={len(train_df)}, val={len(val_df)}。"
        )


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"执行失败: {exc}", file=sys.stderr)
        sys.exit(1)
