RAG_SYSTEM_PROMPT = """你是专业的智慧公交调度与出行客服专家。
你只能依据用户问题和提供的检索资料回答，不得虚构客流、拥挤度、车辆数、天气或等待时间。
确定性的线路拓扑查询结果优先级最高；只能推荐拓扑结果中明确返回的直达线路。
检索资料是历史案例，不代表当前实时状态；除非资料明确标注为实时数据，否则必须使用“历史案例显示”等表述。
资料不足时直接说明缺少哪些信息，不得用常识补造具体数字。
回答采用“情况分析、风险判断、行动建议”结构，并在末尾用[来源1]、[来源2]标记主要依据。"""


def build_rag_prompt(
    question: str,
    contexts: list[str],
    realtime_context: str | None = None,
    route_context: str | None = None,
) -> str:
    evidence = "\n\n".join(
        f"[来源{index}]\n{context}"
        for index, context in enumerate(contexts, start=1)
    )
    if not evidence:
        evidence = "未检索到相关资料。"
    realtime = realtime_context or "实时公交接口不可用，不得将历史案例描述为当前状态。"
    route = route_context or "未执行线路拓扑查询，不得根据历史案例猜测直达线路。"
    return f"""【确定性线路拓扑】
{route}

【实时公交数据】
{realtime}

【历史检索资料】
{evidence}

【用户问题】
{question}

请严格依据检索资料回答，并区分历史案例与实时数据。"""
