import argparse
import json
import math
import os
import sys
import threading
import random
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Dict, List

import pandas as pd
from openai import OpenAI

# ==================== 配置区 ====================
DEFAULT_MODEL = "glm-4.5-air"
DEFAULT_MAX_SAMPLES = 2000    # 目标总数
DEFAULT_TRAIN_SIZE = 1800     # 训练集数量
DEFAULT_VAL_SIZE = 200        # 验证集数量

# --- 以下是影响速率的关键优化设置 ---
DEFAULT_BATCH_SIZE = 10       # 每批次 10 条是平衡“生成质量”与“单次请求时长”的最佳点
DEFAULT_WORKERS = 2           # 保持并发数为 2，这是解决 429 报错的最稳妥做法
DEFAULT_MAX_CALLS = 300       # 理论需要 200 次请求 (2000/10)，设置 300 留出重试空间

API_KEY = "YOUR_ZHIPU_API_KEY"

BASE_DIR = Path(__file__).resolve().parent
# 输入的公交基础数据
DEFAULT_INPUT = BASE_DIR.parent / "database" / "bus_distillation_dataset.csv"

# 输出文件配置
DEFAULT_LABELED_OUTPUT = BASE_DIR / "travel_advice_dataset_glm.csv"  # 供人工检查的完整表格
DEFAULT_TRAIN_OUTPUT = BASE_DIR / "travel_advice_train_glm.jsonl"  # 大模型微调专属 JSONL
DEFAULT_VAL_OUTPUT = BASE_DIR / "travel_advice_val_glm.jsonl"  # 大模型微调专属 JSONL
DEFAULT_CHECKPOINT = BASE_DIR / "advice_label_checkpoint.json"

RANDOM_SEED = 20260411

# ==================== 场景生成辅助函数 ====================
SCENIC_SPOTS = [
    ("白鹤梁", "文化景区"), ("宏声桥商圈", "商圈客流"),
    ("高笋塘", "商圈客流"), ("江东滨江带", "滨江休闲"),
    ("实验中学", "高峰集散"), ("区政府", "政务集散")
]

QUESTION_TEMPLATES = [
    "我现在在{current_location}，要去{destination}，走过去坐{line_name}合适吗？",
    "外面天气感觉一般，我从{current_location}出发去{destination}，帮我看看路况和车上挤不挤。",
    "我刚到{current_location}附近，准备回{destination}，坐{line_name}的话要等很久吗？",
    "结合现在的天气和附近客流，从{current_location}去{destination}怎么走最稳妥？"
]

LOCATION_TEMPLATES = [
    "{station}东侧约{meters}米", "{station}北侧路口约{meters}米",
    "{station}商圈附近约{meters}米", "{station}对面天桥下约{meters}米"
]


def generate_context_for_row(row: pd.Series, df: pd.DataFrame) -> dict:
    """为每一行公交客观数据，随机生成用户的提问场景"""
    station_name = str(row["station_name"])
    # 找同线路的其他站作为目的地
    line_df = df[df["line_name"] == row["line_name"]]
    candidates = line_df["station_name"].unique().tolist()
    candidates = [c for c in candidates if c != station_name]
    destination = random.choice(candidates) if candidates else "终点站"

    meters = random.randint(50, 800)
    current_location = random.choice(LOCATION_TEMPLATES).format(station=station_name, meters=meters)
    scenic_name, scenic_type = random.choice(SCENIC_SPOTS)
    scenic_intensity = round(random.uniform(0.1, 0.9), 2)

    user_question = random.choice(QUESTION_TEMPLATES).format(
        current_location=current_location,
        destination=destination,
        line_name=row["line_name"]
    )

    return {
        "destination_station": destination,
        "current_location": current_location,
        "walk_distance_m": meters,
        "scenic_name": scenic_name,
        "scenic_intensity": scenic_intensity,
        "user_question": user_question
    }


# ==================== 核心：提示词工程 ====================
def build_prompt(batch: List[Dict]) -> str:
    payload = json.dumps(batch, ensure_ascii=False, indent=2)
    return f"""
你是资深的智慧交通专家与高情商服务设计大师。请基于提供的公交实时快照和用户场景，生成用于训练 SFT 模型的“黄金标准”出行建议语料。

【专家决策逻辑矩阵（必须严格执行）】
1. 环境层（Environment）：
   - 风险判定：若 (weather 包含"雨/雪") 且 walk_distance_m > 400米，必须在回答首段提示路滑并建议携带雨具。
   - 舒适度：若 temperature > 32°C 或 < 5°C，需在建议中加入温差提醒。

2. 运行层（Operation）：
   - 负荷预警：crowding > 0.8 或 congestion > 0.85 时，判定为“极度不适”，必须建议“错峰”或“换站”。
   - 等候策略：headway > 15分钟 时，判定为“久等风险”，必须建议用户“在室内稍候”而非立刻前往站台。
   - 资源瓶颈：若 online_vehicle 接近 total_vehicle，说明运力已达极限，建议中不应提到“会有更多车辆加入”。

3. 外部联动（External）：
   - 景区压力：scenic_intensity > 0.7 时，告知用户受{batch[0].get('scenic_name', '周边景区')}客流叠加影响，路况复杂。

【输出要求与约束】
1. 只输出合法 JSON 数组，禁止 Markdown 嵌套。
2. 返回数组长度必须与输入一致。
3. **Reasoning 强制要求**：请按 [环境判定 -> 运行分析 -> 决策选型] 的顺序书写理由。
4. **Assistant_Answer 三段式要求**：
   - 第一段：【关怀】基于天气、距离、温度的即时问候与安全提醒。
   - 第二段：【数据】用非术语化语言播报拥挤度、拥堵感及预计等待时间。
   - 第三段：【建议】给出“避让、推荐、常态”等明确行动方案。

输出示例：
[
  {{
    "sample_id": "TA00001",
    "reasoning": "环境：暴雨且步行500米，路滑。运行：拥挤度0.95(极高)，发车间隔25分(极长)。决策：由于路况与运力双重瘫痪，建议用户放弃当前班次。",
    "advice_level": "强提醒",
    "assistant_answer": "您好！目前外面雨势非常大，您距离车站较远，走过去不仅容易淋湿且路面湿滑，请务必注意安全。\\n\\n帮您查询到当前105路车厢内已极度拥挤，受暴雨影响道路基本处于停滞状态，下一班车预计至少需要等待25分钟。\\n\\n💡 综合建议：为了您的出行体验，强烈建议您暂时在室内避雨休息，等雨势减小、高峰错开后再出发，现在出门不仅等候时间长且体验较差。"
  }}
]

【待分析样本（JSON负载）】
{payload}
""".strip()


# ==================== 网络请求与处理 ====================
def build_client() -> OpenAI:
    return OpenAI(api_key=API_KEY, base_url="https://open.bigmodel.cn/api/paas/v4/")


def sanitize_content(content: str) -> str:
    text = content.strip()
    if text.startswith("```"):
        text = text.replace("```json", "").replace("```", "").strip()
    return text


def request_labels(client: OpenAI, batch: List[Dict]) -> List[Dict]:
    response = client.chat.completions.create(
        model=DEFAULT_MODEL,
        temperature=0.3,  # 稍微给一点温度，让回答的语言更丰富自然
        messages=[
            {"role": "system", "content": "你是智慧公交出行问答语料生成专家，只返回合法 JSON 数组。"},
            {"role": "user", "content": build_prompt(batch)},
        ],
    )
    data = json.loads(sanitize_content(response.choices[0].message.content))
    if not isinstance(data, list):
        raise RuntimeError("模型返回结果不是 JSON 数组。")
    return data


def main() -> None:
    random.seed(RANDOM_SEED)
    df = pd.read_csv(DEFAULT_INPUT)

    # 1. 为每条数据生成场景上下文
    print("正在为基础数据构建出行问答场景...")
    scenarios = []
    for _, row in df.iterrows():
        scenarios.append(generate_context_for_row(row, df))
    context_df = pd.DataFrame(scenarios)
    full_df = pd.concat([df, context_df], axis=1)

    # 构建送给模型打标的 payload
    sample_df = full_df.head(DEFAULT_MAX_SAMPLES).copy()
    sample_df.insert(0, "sample_id", [f"TA{idx + 1:05d}" for idx in range(len(sample_df))])

    checkpoint_file = DEFAULT_CHECKPOINT
    labels = {}
    if checkpoint_file.exists():
        with checkpoint_file.open("r", encoding="utf-8") as f:
            labels = json.load(f).get("labels", {})

    pending_df = sample_df[~sample_df["sample_id"].isin(labels.keys())].reset_index(drop=True)

    print(f"总样本数: {len(sample_df)} | 已完成: {len(labels)} | 待处理: {len(pending_df)}")

    if not pending_df.empty:
        # 将需要的字段挑出来发给大模型
        prompt_fields = ["sample_id", "line_name", "weather", "walk_distance_m", "crowding",
                         "congestion", "headway", "scenic_name", "scenic_intensity", "user_question"]

        pending_records = pending_df[prompt_fields].to_dict(orient="records")
        batches = [pending_records[i:i + DEFAULT_BATCH_SIZE] for i in
                   range(0, len(pending_records), DEFAULT_BATCH_SIZE)]

        print(f"🚀 启动并发生成回答，并发数: {DEFAULT_WORKERS}...")
        client = build_client()
        checkpoint_lock = threading.Lock()
        completed = 0

        with ThreadPoolExecutor(max_workers=DEFAULT_WORKERS) as executor:
            future_to_batch = {executor.submit(request_labels, client, batch): batch for batch in batches}

            for future in as_completed(future_to_batch):
                batch = future_to_batch[future]
                try:
                    result = future.result()
                    mapped = {item["sample_id"]: item for item in result if "sample_id" in item}

                    with checkpoint_lock:
                        labels.update(mapped)
                        with checkpoint_file.open("w", encoding="utf-8") as f:
                            json.dump({"labels": labels}, f, ensure_ascii=False, indent=2)
                        completed += 1
                        print(f"[{completed}/{len(batches)}] 成功生成 {len(batch)} 条专家回答")
                except Exception as exc:
                    print(f"❌ 批次失败: {exc}")

    # 3. 将标签合并回数据集，并同时生成 JSONL 微调格式
    rows = []
    sft_records = []  # 专门用于存储 SFT 大模型微调格式

    for _, row in sample_df.iterrows():
        record = row.to_dict()
        label = labels.get(record["sample_id"], {})

        if label:
            assistant_answer = label.get("assistant_answer", "暂无建议。")

            # 记录完整 CSV 数据
            record.update({
                "advice_level": label.get("advice_level", "普通"),
                "reasoning": label.get("reasoning", ""),
                "assistant_answer": assistant_answer,
                "status": "ok"
            })
            rows.append(record)

            # 构建标准的 SFT (JSONL) 微调消息格式
            sft_format = {
                "messages": [
                    {
                        "role": "system",
                        "content": "你是高情商、专业的智慧出行AI助手。请根据当前场景给出合理的出行建议。"
                    },
                    {
                        "role": "user",
                        "content": record["user_question"]
                    },
                    {
                        "role": "assistant",
                        "content": assistant_answer
                    }
                ]
            }
            sft_records.append(sft_format)

    labeled_df = pd.DataFrame(rows)
    sft_df = pd.DataFrame(sft_records)

    # 划分 JSONL 训练集和验证集
    train_sft_df = sft_df.head(DEFAULT_TRAIN_SIZE)
    val_sft_df = sft_df.iloc[DEFAULT_TRAIN_SIZE:DEFAULT_TRAIN_SIZE + DEFAULT_VAL_SIZE]

    # 保存完整的 CSV 供人工检查数据
    labeled_df.to_csv(DEFAULT_LABELED_OUTPUT, index=False, encoding="utf-8-sig")

    # 以 JSONL (JSON Lines) 格式保存，force_ascii=False 保证中文不乱码
    train_sft_df.to_json(DEFAULT_TRAIN_OUTPUT, orient="records", lines=True, force_ascii=False)
    val_sft_df.to_json(DEFAULT_VAL_OUTPUT, orient="records", lines=True, force_ascii=False)

    print(f"\n✅ 数据集生成完毕！")
    print(f"👉 人工核对用 CSV: {DEFAULT_LABELED_OUTPUT}")
    print(f"👉 训练集 JSONL: {DEFAULT_TRAIN_OUTPUT} ({len(train_sft_df)} 条)")
    print(f"👉 验证集 JSONL: {DEFAULT_VAL_OUTPUT} ({len(val_sft_df)} 条)")

    if len(train_sft_df) < DEFAULT_TRAIN_SIZE:
        print(f"⚠️ 注意：由于部分 API 请求可能失败，实际生成数据少于设定的 {DEFAULT_TRAIN_SIZE} 条。")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"执行失败: {exc}", file=sys.stderr)
        sys.exit(1)
