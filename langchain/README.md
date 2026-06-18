# 智慧公交 LangChain RAG 实施计划

## 1. 建设目标

在现有智慧公交项目中增加独立的 LangChain RAG 服务，使 AI 能够结合公交知识、历史问答案例和实时业务数据生成可追溯的调度建议。

第一阶段只建设 Python 后端服务，不修改 Vue 前端。待接口与回答格式稳定后，再接入现有调度助手页面。

## 2. 总体技术方案

- 大语言模型：智谱 GLM，默认使用 `glm-4.5-air`
- 模型接入：`langchain-openai` 的 `ChatOpenAI`
- 服务框架：FastAPI
- 知识编排：LangChain
- 向量数据库：第一版使用 FAISS 本地持久化
- Embedding：优先使用智谱 Embedding API，无法使用时切换本地中文 BGE 模型
- 静态知识来源：公交问答数据、调度规则、历史调度案例
- 动态数据来源：现有 Spring Boot API、Redis 和 MySQL

RAG 采用“静态知识检索 + 实时数据查询”的混合模式：

```text
用户问题
  -> 问题解析（线路、站点、日期、场景）
  -> 向量检索（规则、案例、历史问答）
  -> Java API 查询（客流、拥挤度、天气、车辆资源）
  -> 上下文组装
  -> GLM 生成回答
  -> 返回答案、引用来源和运行信息
```

## 3. 计划目录结构

```text
langchain/
├── README.md
├── app.py                    # FastAPI 服务入口
├── config.py                 # 模型、路径和环境变量配置
├── schemas.py                # API 请求与响应模型
├── requirements.txt
├── .env.example
├── chains/
│   ├── __init__.py
│   ├── chat_chain.py         # GLM RAG 问答链
│   └── prompts.py            # 系统提示词与上下文模板
├── retrieval/
│   ├── __init__.py
│   ├── loader.py             # CSV、JSONL 和文档加载
│   ├── indexer.py            # 文档切分与向量化
│   ├── retriever.py          # 检索、过滤与去重
│   └── vector_store.py       # FAISS 加载与持久化
├── services/
│   ├── __init__.py
│   ├── glm_service.py        # GLM 模型封装
│   └── transit_service.py    # Spring Boot 实时数据查询
├── scripts/
│   └── build_index.py        # 知识库重建脚本
├── data/
│   └── vector_store/         # 本地向量索引，不提交 Git
└── tests/
    ├── test_retriever.py
    └── test_chat_api.py
```

## 4. 分阶段实施

### 阶段一：迁移并完善 GLM 服务（已完成）

目标：将当前 `蒸馏/OD客服` 中已经验证的 GLM 调用迁移为 LangChain 服务的模型层。

任务：

1. 从环境变量读取 `ZHIPUAI_API_KEY`。
2. 使用以下参数创建 `ChatOpenAI`：
   - `model=glm-4.5-air`
   - `base_url=https://open.bigmodel.cn/api/paas/v4/`
   - `temperature=0`
   - `max_tokens=500`
3. 增加请求超时、限流重试和错误转换。
4. 禁止在日志、响应和代码仓库中输出 API Key。
5. 保留 `/api/v1/health` 和 `/api/v1/chat` 两个基础接口。

验收标准：GLM 可以通过 LangChain 稳定回答普通公交问题，API Key 不出现在代码中。

#### 阶段一实际操作流程

本阶段于 2026-06-18 完成，操作过程如下。

**第一步：建立独立运行环境**

在 `langchain/.venv` 创建了独立 Python 虚拟环境。这样可以隔离原有蒸馏模型使用的 PyTorch、Transformers 和 PEFT 依赖，防止两个模型服务互相影响。

执行命令：

```powershell
python -m venv .\langchain\.venv
.\langchain\.venv\Scripts\python.exe -m pip install -r .\langchain\requirements.txt
```

`.venv` 已经加入项目 `.gitignore`，不会提交到代码仓库。

**第二步：抽离配置层**

创建 `config.py`，集中读取以下环境变量：

- `ZHIPUAI_API_KEY`
- `GLM_MODEL`
- `GLM_BASE_URL`
- `GLM_TEMPERATURE`
- `GLM_MAX_TOKENS`
- `GLM_TIMEOUT_SECONDS`
- `GLM_MAX_RETRIES`

程序只记录密钥是否已经配置，不会返回或打印密钥内容。缺少 API Key 时，聊天接口会返回明确的配置错误。

**第三步：迁移 GLM 模型封装**

创建 `services/glm_service.py`，将原来位于 `蒸馏/OD客服/__init__.py` 的 GLM 调用迁移到独立服务类 `GLMService`。

当前模型参数：

```python
ChatOpenAI(
    model="glm-4.5-air",
    base_url="https://open.bigmodel.cn/api/paas/v4/",
    temperature=0,
    max_tokens=500,
    timeout=60,
    max_retries=2,
)
```

服务层完成了以下处理：

1. 使用 LangChain 消息对象组装 system、user 和 assistant 消息。
2. 支持最多 20 条历史对话。
3. 支持请求级 `temperature` 和 `max_tokens` 覆盖。
4. 缓存相同参数的 `ChatOpenAI` 客户端。
5. 统一处理字符串和分段内容响应。
6. 将网络、额度和上游模型异常转换成安全错误，不暴露密钥。

**第四步：建立 FastAPI 接口**

创建 `app.py`，作为后续 LangChain RAG 服务的唯一入口。原有 `蒸馏/OD客服` 服务暂时保留，便于对照和回退。

当前接口：

```text
GET  /api/v1/health
POST /api/v1/chat
```

健康检查只返回服务状态、模型名称和是否完成配置。聊天响应包含回答、模型名称、请求 ID 和耗时。

**第五步：定义请求和响应格式**

创建 `schemas.py`，使用 Pydantic 校验问题、历史消息、温度和最大 token 数。

当前聊天请求：

```json
{
  "prompt": "宏声桥当前拥挤，是否需要增发车辆？",
  "history": [],
  "temperature": 0,
  "max_tokens": 500
}
```

当前成功响应：

```json
{
  "status": "success",
  "data": {
    "response": "GLM 生成的回答",
    "model": "glm-4.5-air",
    "request_id": "请求唯一标识",
    "latency_ms": 1200
  }
}
```

**第六步：增加自动化测试**

在 `tests/` 中增加了配置、模型参数、历史消息顺序、缺少密钥和 API 响应格式测试。测试使用模拟回答，不会调用真实 GLM，也不会产生 API 费用。

执行命令：

```powershell
cd .\langchain
.\.venv\Scripts\python.exe -m pytest -q
```

当前测试结果：

```text
5 passed
```

#### 阶段一遇到的问题

1. **原全局 Python 环境损坏**
   - 现象：全局环境中的 `torch` 缺少有效版本元数据，LangChain 导入时触发 Transformers 检查并报错。
   - 处理：没有卸载或修改原训练环境，而是在 `langchain/.venv` 创建独立环境，避免影响现有蒸馏代码。

2. **系统使用 SOCKS 代理但缺少驱动**
   - 现象：`ChatOpenAI` 初始化 HTTP 客户端时报缺少 `socksio`。
   - 处理：在依赖中使用 `httpx[socks]`，补齐代理支持。

3. **API Key 已在聊天中暴露**
   - 风险：该密钥可能被他人使用并产生费用。
   - 处理：代码不保存该密钥，只从 `.env` 或环境变量读取；运行前必须在智谱控制台轮换密钥。

4. **当前没有安全的新密钥用于真实请求测试**
   - 处理：本阶段只进行了客户端初始化和模拟接口测试，没有调用真实 GLM，也没有消耗额度。

#### 阶段一运行方法

1. 复制环境变量示例：

```powershell
Copy-Item .\langchain\.env.example .\langchain\.env
```

2. 在 `langchain/.env` 中填入已经轮换的新 API Key。禁止使用已经出现在聊天记录或截图中的旧密钥。

3. 启动服务：

```powershell
.\langchain\.venv\Scripts\python.exe .\langchain\app.py
```

4. 检查服务状态：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/v1/health
```

5. 调用聊天接口：

```powershell
$body = @{
  prompt = "宏声桥当前拥挤，是否需要增发车辆？"
  history = @()
} | ConvertTo-Json

Invoke-RestMethod `
  -Uri http://127.0.0.1:8000/api/v1/chat `
  -Method Post `
  -ContentType "application/json" `
  -Body $body
```

#### 阶段一当前边界

- 已完成 LangChain 调用 GLM 的基础链路。
- 尚未加入知识库、Embedding、FAISS 和检索上下文。
- 尚未调用 Java 实时公交接口。
- 尚未修改 Vue 前端。
- 下一步进入阶段二：清洗问答数据并生成 LangChain `Document`。

### 阶段二：整理知识库数据（已完成）

首批数据来源：

- `蒸馏/出行问答训练/travel_advice_dataset_glm.csv`
- `蒸馏/出行问答训练/travel_advice_train_glm.jsonl`
- `蒸馏/database/bus_distillation_dataset.csv`
- 后续补充的公交调度规则和人工审核案例

数据处理要求：

1. 去除空回答、重复问题和明显低质量记录。
2. 将表格记录整理成自然语言场景，避免直接向量化整行原始数字。
3. 为每条 LangChain `Document` 保存 metadata：
   - `sample_id`
   - `line_id`、`line_name`
   - `station_id`、`station_name`
   - `weather`
   - `scene`
   - `advice_level`
   - `source`
4. 将训练数据划分为知识库数据和独立评测数据，避免使用同一批数据自问自答。

验收标准：能够生成可重复构建的文档集合，并能追踪每条文档的原始来源。

#### 阶段二实际操作流程

1. 创建 `retrieval/loader.py`，使用标准 CSV 解析器读取 GLM 问答数据和公交基础数据。
2. 对问答记录检查问题、回答、状态和重复键，过滤空回答、无效状态和重复场景。
3. 将每条问答案例转换为 LangChain `Document`，正文统一整理为“用户问题、公交场景、运行状态、分析依据、参考建议”。
4. 为文档保留 `sample_id`、线路、站点、天气、场景、提醒等级和来源等 metadata。
5. 对公交快照按 `line_id + station_id` 去重，生成线路站点基础文档。
6. 使用稳定 SHA-256 分组规则划分知识库与评测集，重复执行时结果不变。
7. 创建 `scripts/build_documents.py`，生成处理后的 JSONL 和统计清单。

构建命令：

```powershell
cd .\langchain
.\.venv\Scripts\python.exe .\scripts\build_documents.py
```

输出位于 `langchain/data/processed/`：

```text
knowledge_documents.jsonl
evaluation_documents.jsonl
manifest.json
```

生成结果：

```text
问答记录：1920
有效问答案例：1920
公交快照：10000
去重后的线路站点文档：706
知识库文档：2442
独立评测文档：184
```

自动化测试增加到 7 项，当前结果为 `7 passed`。

#### 阶段二遇到的问题

1. **文本字段包含换行符**
   - 现象：直接按文件行数统计会高估 CSV 记录数量。
   - 处理：使用 Python `csv.DictReader` 按 CSV 规范解析，最终确认真实问答记录为 1920 条。

2. **JSONL 与 GLM CSV 内容重复**
   - 现象：`travel_advice_train_glm.jsonl` 来自同一批问答，但缺少线路、站点等完整 metadata；同时导入会造成重复检索。
   - 处理：当前只使用信息更完整的 `travel_advice_dataset_glm.csv`，JSONL 暂不重复入库。

3. **公交基础数据存在大量重复快照**
   - 现象：10000 条记录包含相同线路站点在不同时间的快照，直接作为知识文档会产生大量重复内容。
   - 处理：只提取唯一的线路—站点关系，生成 706 条站点基础文档；实时数值留到阶段六从 Java API 获取。

4. **现有问答主要由 GLM 生成**
   - 风险：生成内容可能存在措辞相似或事实表达偏差。
   - 处理：保留独立评测集和原始 `sample_id`，后续评测时抽样人工审核，不把评测文档写入知识库。

### 阶段三：构建向量索引（已完成，本地 Embedding）

任务：

1. 选择并封装 Embedding 模型。
2. 将知识文档分批向量化。
3. 将 FAISS 索引持久化到 `langchain/data/vector_store/`。
4. 保存索引版本、文档数量、Embedding 模型名称和构建时间。
5. 提供 `python scripts/build_index.py` 重建命令。

验收标准：服务重启后可以直接加载索引，不需要重复调用 Embedding API。

#### 阶段三实际操作流程

1. 在配置层增加 Embedding 提供方、模型和批量大小配置。当前使用本地 `BAAI/bge-small-zh-v1.5`，不调用智谱 Embedding API。
2. 创建 `retrieval/vector_store.py`，负责读取阶段二生成的知识文档、调用 Embedding API、构建 FAISS、保存和重新加载索引。
3. 创建 `retrieval/indexer.py` 和 `scripts/build_index.py`，提供统一构建入口。
4. 索引构建后保存 `index.faiss`、`index.pkl` 和 `manifest.json`。
5. 清单记录文档数、Embedding 模型、构建时间、源文件 SHA-256 和索引文件 SHA-256。
6. 加载索引前校验文件哈希，校验失败时拒绝反序列化。
7. 使用 FastEmbed/ONNX 在本机完成 2442 条知识文档的向量化，不调用真实 API。

真实索引构建命令：

```powershell
cd .\langchain
.\.venv\Scripts\python.exe .\scripts\build_index.py
```

成功后索引位于：

```text
langchain/data/vector_store/index.faiss
langchain/data/vector_store/index.pkl
langchain/data/vector_store/manifest.json
```

当前自动化测试结果为 `8 passed`。

实际索引结果：

```text
向量数量：2442
Embedding 提供方：local
Embedding 模型：BAAI/bge-small-zh-v1.5
```

#### 阶段三遇到的问题

1. **当前没有配置安全的新 API Key**
   - 处理：Embedding 改为本地 `BAAI/bge-small-zh-v1.5`，正式索引已经生成，不需要智谱 Embedding 额度。API Key 后续只用于 GLM 回答。

2. **FAISS 加载需要反序列化本地 metadata 文件**
   - 风险：加载来源不可信的 `index.pkl` 存在安全风险。
   - 处理：构建时记录索引文件 SHA-256，加载前强制校验；索引目录同时不提交 Git。

3. **`langchain-community` 输出弃用提示**
   - 现象：当前 FAISS 集成仍可运行，但依赖提示未来应迁移到独立集成包。
   - 处理：本阶段保持可用实现并记录该问题；后续升级依赖时再迁移，避免现在扩大修改范围。

4. **FAISS 原生写入不支持当前中文路径**
   - 现象：向量计算完成后，FAISS 无法直接写入包含“毕设/研究生”的 Windows 路径。
   - 处理：改用 Python 字节流保存和加载 FAISS 索引，无需移动项目目录。

5. **纯向量检索对站点专名不够稳定**
   - 现象：使用“宏声桥”测试时，Top 结果不一定包含该名称；使用完整场景问题时能返回相似案例。
   - 处理：索引本身可用；第四阶段增加关键词检索和线路、站点 metadata 过滤，形成混合检索。

### 阶段四：实现检索器（已完成）

检索流程：

1. 从问题识别线路、站点、天气和调度场景。
2. 使用 metadata 对候选文档进行过滤。
3. 向量召回 Top 8 文档。
4. 对重复或高度相似文档去重。
5. 选择 Top 4 至 Top 5 文档进入 GLM 上下文。
6. 返回文档分数和来源，支持调试和答辩展示。

可在第二版加入 BM25 关键词检索和重排序模型，形成混合检索。

验收标准：测试问题的 Top 5 结果中能够找到对应线路、站点或相似调度场景。

#### 阶段四实际操作流程

1. 创建 `retrieval/retriever.py`，同时执行本地 FAISS 语义召回和中文关键词召回。
2. 从问题中识别已知的 `line_name`、`station_name` 和天气，并对 metadata 进行精确过滤。
3. 中文关键词使用二元、三元字符特征，并按照文档频率赋予稀有词更高权重，使站点、商圈等专名优先。
4. 使用倒数排名融合语义结果和关键词结果，关键词权重略高，返回向量排名、关键词排名和融合分数。
5. 在 FastAPI 中增加 `POST /api/v1/retrieve`，可直接查看识别出的过滤条件和 Top K 文档。

请求示例：

```json
{
  "query": "奥体中心127路小雨天气怎么出行？",
  "top_k": 3
}
```

实际验证结果：

- “奥体中心 + 127路 + 小雨”能够提取三个 metadata 条件，前三条全部满足过滤条件。
- “宏声桥客流拥挤”返回的前两条文档均包含“宏声桥”。
- 自动化测试结果为 `10 passed`。

#### 阶段四遇到的问题

1. **“宏声桥”不是当前数据中的正式站点 metadata**
   - 现象：它主要作为商圈名称出现在回答正文中，因此无法使用站点字段过滤。
   - 处理：通过稀有关键词权重召回，专名匹配结果优先于“客流、拥挤”等高频词。

2. **普通关键词重叠会压过专名**
   - 现象：初版按重叠数量评分时，不包含“宏声桥”的文档可能因为包含多个通用词而排在前面。
   - 处理：改为类似 IDF 的文档频率权重，低频专名获得更高分。

3. **metadata 精确过滤可能减少候选数量**
   - 现象：同时指定线路、站点和天气时，候选文档可能少于 Top K。
   - 处理：优先保证结果严格符合用户条件，不用不相关文档补满数量。

### 阶段五：构建 RAG 问答链（已完成）

回答约束：

1. 只能根据检索知识和实时数据回答。
2. 不得虚构客流、拥挤度、车辆数和等待时间。
3. 数据不足时必须明确说明缺少什么信息。
4. 调度回答采用“现状分析—风险判断—执行建议”结构。
5. 返回引用来源，说明建议依据。

RAG 输出至少包含：

```json
{
  "status": "success",
  "data": {
    "answer": "建议内容",
    "sources": [],
    "retrieved_documents": [],
    "model": "glm-4.5-air",
    "latency_ms": 0
  }
}
```

验收标准：相同事实条件下回答基本稳定，且能够展示建议引用了哪些知识。

#### 阶段五实际操作流程

1. 创建 `chains/prompts.py`，规定模型只能使用检索资料，不得虚构实时数字，并必须区分历史案例与实时状态。
2. 创建 `chains/chat_chain.py`，执行“混合检索—上下文组装—GLM 生成—来源整理”的完整流程。
3. 默认向 GLM 注入 Top 5 文档，最多允许 Top 10，控制提示词长度。
4. 没有检索到资料时直接返回“缺少依据”，不调用 GLM，避免无依据回答和额度浪费。
5. 扩展 `GLMService`，允许 RAG 链使用专用 system prompt，同时保留历史消息。
6. 将 `/api/v1/chat` 改为 RAG 接口，响应增加 `filters`、`sources` 和 `retrieved_documents`。

回答约束包括：

- 检索文档默认视为历史案例，不表述成当前实时状态。
- 缺少实时客流、车辆或天气时明确说明。
- 回答使用“情况分析、风险判断、行动建议”结构。
- 主要依据使用 `[来源1]`、`[来源2]` 标记。

自动化测试使用模拟 GLM，验证了上下文注入、来源返回和无证据保护，结果为 `12 passed`。

另外完成了一次真实 GLM 小请求：问题为“奥体中心127路小雨天气怎么出行？”。系统准确提取了 `127路`、`奥体中心`、`小雨` 三个过滤条件，检索 3 条历史案例，并由 GLM 返回带 `[来源1][来源2][来源3]` 的结构化回答。完整链路耗时约 18 秒。

#### 阶段五遇到的问题

1. **知识库数据是历史/合成案例，不是实时数据**
   - 风险：模型可能把 2024 年案例中的客流和车辆数描述为当前情况。
   - 处理：在 system prompt 中强制使用“历史案例显示”等表述；实时数据留到阶段六接入 Java API。

2. **API Key 没有写入项目配置**
   - 处理：真实测试只在单次进程中临时设置密钥，没有写入代码、`.env` 或索引文件。
   - 影响：后续常驻启动服务时仍需通过环境变量提供 `ZHIPUAI_API_KEY`。

3. **检索不到资料时模型容易自由发挥**
   - 处理：无检索结果时跳过 GLM，直接要求用户补充线路、站点、日期或实时信息。

4. **RAG 响应字段比原聊天接口更多**
   - 处理：保留原有 `data.response`，新增字段不破坏原响应正文；前端接入留到最后阶段。

### 阶段六：融合实时公交数据（代码完成，联调待 Java 服务启动）

向量库用于保存规则和历史案例，实时业务数字不写入向量库。LangChain 服务根据问题调用现有 Java API：

- `/api/system/dispatch`
- `/api/system/forecast`
- `/api/system/overview`
- `/api/system/gis/station`

实时上下文包括：

- 当前日期与天气
- 线路和站点客流
- 拥挤度及道路状态
- 发车间隔
- 在线车辆与备用运力
- 当前调度策略

验收标准：回答中出现的实时数字均可在 Java API 响应中找到对应字段。

#### 阶段六实际操作流程

1. 创建 `services/transit_service.py`，并发请求 Spring Boot 的 `dispatch`、`forecast` 和 `overview` 接口。
2. 只保留调度指标、备用运力、热点、天气、拥堵线路和告警等必要字段，避免把完整页面数据放入提示词。
3. 聊天请求增加可选 `date` 字段，并将日期传给 Java API。
4. 将实时数据放在提示词的“实时公交数据”区域，历史向量资料放在“历史检索资料”区域。
5. Java 服务不可用时返回降级说明，RAG 继续使用历史案例回答，不把历史数值伪装成实时数值。
6. 聊天响应增加 `realtime_available`、`realtime_data` 和 `realtime_error`。

当前测试使用模拟 Java API 验证实时数据压缩、提示词注入和降级逻辑，结果为 `13 passed`。

#### 阶段六遇到的问题

1. **本机 Spring Boot 服务当前未启动**
   - 现象：`http://127.0.0.1:8080/api/health` 无法连接。
   - 处理：完成代码与模拟接口测试；真实联调需要 MySQL、Redis 和 Spring Boot 服务启动后进行。

2. **完整页面数据过大会增加 GLM token 消耗**
   - 处理：仅提取必要字段，并限制策略、热点、拥堵线路和告警数量。

3. **实时来源与历史引用的编号可能冲突**
   - 现象：初版把实时 API 放在来源列表首位，导致 `[来源1]` 不再对应第一条历史文档。
   - 处理：历史文档保持原引用顺序，实时 API 作为附加来源放在列表末尾。

4. **实时服务异常不能阻断整个问答**
   - 处理：连接失败、超时或响应异常时自动降级，并在响应中明确返回错误原因。

### 阶段七：API 完善（已完成）

计划提供以下接口：

```text
GET  /api/v1/health
POST /api/v1/knowledge/rebuild
POST /api/v1/retrieve
POST /api/v1/chat
```

其中 `/api/v1/retrieve` 只执行检索、不调用 GLM，用于检查召回质量并降低调试成本。

建议的聊天请求：

```json
{
  "query": "宏声桥当前拥挤，是否需要增发车辆？",
  "date": "2026-06-18",
  "history": []
}
```

#### 阶段七实际操作流程

1. 完善 `GET /api/v1/health`，增加 FAISS 是否就绪、索引文档数和本地 Embedding 模型信息。
2. 新增 `POST /api/v1/knowledge/rebuild`，可重新清洗知识文档并重建本地 FAISS 索引。
3. 重建过程放入工作线程，避免阻塞 FastAPI 事件循环。
4. 增加重建锁；已有任务运行时再次请求返回 HTTP 409。
5. 重建成功后清除检索器和 RAG 链缓存，下一次请求自动加载新索引。
6. 保持 `/api/v1/retrieve` 和 `/api/v1/chat` 的请求、响应校验。

知识库重建请求：

```json
{
  "rebuild_documents": true
}
```

当前自动化测试结果为 `14 passed`。

#### 阶段七遇到的问题

1. **本地向量重建耗时较长**
   - 现象：2442 条文档在本机重新向量化约需 1 至 2 分钟。
   - 处理：使用工作线程和并发锁；当前请求会等待构建完成，后续需要时可改成后台任务。

2. **索引缓存可能继续引用旧文件**
   - 处理：重建成功后主动清除 `get_retriever` 和 `get_rag_chain` 缓存。

3. **重建接口目前没有管理认证**
   - 风险：若直接暴露到公网，其他人可反复触发高开销重建。
   - 处理：当前仅适合本地使用；部署前需要增加管理员密钥或内网访问控制。

4. **索引文件不提交 Git**
   - 影响：在新机器首次启动时，健康检查会显示索引未就绪。
   - 处理：先执行 `build_documents.py` 和 `build_index.py`，或调用知识库重建接口。

### 阶段八：评测与优化（离线检索评测已完成）

准备 50 至 100 道独立测试题，对比：

1. GLM 直接回答。
2. GLM + 静态知识 RAG。
3. GLM + 静态知识 + 实时数据混合 RAG。

评测指标：

- 检索 Top 5 命中率
- 回答事实正确率
- 实时数据引用正确率
- 无依据数字和幻觉出现率
- 来源可追溯率
- 平均响应时间
- 调度建议可执行性

#### 阶段八实际操作流程

1. 创建 `evaluation/evaluator.py`，从独立评测集稳定抽取 50 道题。
2. 创建 `scripts/evaluate_retrieval.py`，同时评测纯向量检索和关键词/metadata 混合检索。
3. 统计线路命中率、站点命中率、线路—站点联合命中率、MRR 和 metadata 识别覆盖率。
4. 将结果写入 `langchain/data/evaluation/retrieval_report.json`。

运行命令：

```powershell
cd .\langchain
.\.venv\Scripts\python.exe .\scripts\evaluate_retrieval.py
```

50 题默认 Top 8 实际结果：

```text
线路命中率：84%
站点命中率：100%
混合检索线路—站点联合命中率：80%
纯向量线路—站点联合命中率：26%
混合检索提升：54 个百分点
MRR：0.5672
metadata 条件识别率：100%
同时识别线路和站点的问题占比：54%
```

当前自动化测试结果为 `16 passed`。

#### 阶段八遇到的问题

1. **初始联合命中率只有约三成**
   - 原因：问题同时包含起点和终点时，初版按名称长度选择站点，可能误把终点当作当前站点。例如“从图书馆去洗墨路口”会错误过滤为洗墨路口。
   - 处理：改为选择问题中最先出现的站点作为起点。优化后站点命中率从 60% 提升到 100%。

2. **未明确线路的问题仍限制联合命中率**
   - 结果：补充终点 metadata 和软排序权重，并将默认召回调整为 Top 8 后，混合检索联合命中率为 80%，明显高于纯向量的 26%。Top 5 的联合命中率仍为 66%。
   - 后续方向：根据起点—终点关系推断候选线路，增加线路别名和重排序模型。

3. **评测数据与知识库来自同一类 GLM 合成数据**
   - 风险：当前指标只能说明检索一致性，不能完全代表真实用户问题上的效果。
   - 处理：后续加入人工编写问题和真实调度人员评分。

4. **尚未执行大规模 GLM 回答评测**
   - 原因：50 至 100 次生成会消耗真实 token，且 Java 实时服务当前未启动。
   - 处理：目前完成离线检索评测和一次真实 RAG 小请求；实时服务启动后再评测事实正确率和建议可执行性。

5. **本地中文重排序模型下载失败**
   - 计划：使用 `BAAI/bge-reranker-base` 对融合召回的 Top 20 候选进行二次排序。
   - 现象：模型约 1.04GB，Hugging Face 与镜像下载均被当前代理/SSL 连接中断。
   - 处理：重排序代码和测试已完成，但 `RERANKER_ENABLED` 默认设为 `false`，避免服务启动时阻塞。模型可用后改为 `true` 即可启用。

## 9. 检索优化迭代记录

本节记录每次优化的原因、方法、结果和是否保留，方便复盘和面试说明。所有指标均使用固定的 50 道独立评测题。

### 9.1 纯向量检索基线

方案：仅使用本地 `BAAI/bge-small-zh-v1.5` 和 FAISS 语义相似度。

结果：

```text
Top 5 线路—站点联合命中率：22%
```

问题：语义向量能够理解“拥挤、暴雨、增发车辆”等含义，但对线路编号、站点名称和商圈专名不够稳定。

结论：纯向量检索不能满足公交业务中对实体精确匹配的要求。

### 9.2 加入关键词和 metadata 混合检索

优化方法：

1. 增加中文二元、三元字符关键词召回。
2. 识别问题中的线路、站点和天气。
3. 使用 metadata 精确过滤。
4. 使用倒数排名融合向量结果与关键词结果。

结果：

```text
Top 5 线路命中率：76%
Top 5 站点命中率：60%
Top 5 联合命中率：36%
MRR：0.2817
```

提升：联合命中率从 22% 提升到 36%，增加 14 个百分点。

结论：混合检索有效，但站点识别仍存在明显错误。

### 9.3 修复起点与终点识别错误

发现的问题：问题同时包含起点和终点时，初版按照站点名称长度选择过滤条件，可能把终点误认为当前站点。例如：

```text
从图书馆去洗墨路口
```

初版可能选择“洗墨路口”，但知识文档的 `station_name` 表示起点“图书馆”。

优化方法：按站点在问题中出现的位置排序，选择最先出现的站点作为起点。

结果：

```text
Top 5 站点命中率：60% -> 100%
Top 5 联合命中率：36% -> 66%
MRR：0.2817 -> 0.5257
```

提升：联合命中率增加 30 个百分点，是当前收益最大的一次优化。

结论：RAG 检索问题不一定来自 Embedding，实体解析和 metadata 设计同样重要。

### 9.4 增加终点 metadata 和软排序

优化方法：

1. 为问答案例增加 `destination_station` metadata。
2. 重新生成 2442 条知识文档并重建 FAISS。
3. 当文档终点与问题终点一致时提高关键词排序分数。
4. 终点只做软加分，不做硬过滤，避免知识库没有完全相同 OD 组合时返回空结果。

结果：

```text
Top 5 线路命中率：70% -> 72%
Top 5 联合命中率：保持 66%
MRR：0.5257 -> 0.5457
```

提升：联合覆盖没有变化，但正确结果的平均排序位置有所提高。

结论：终点信息适合用于排序增强，不适合直接作为强制过滤条件。

### 9.5 调整默认 Top K

对比结果：

```text
Top 5 联合命中率：66%
Top 8 联合命中率：80%
Top 10 联合命中率：84%
```

最终选择 Top 8，原因：

- 相比 Top 5 提升 14 个百分点。
- 相比 Top 10 只少 4 个百分点，但少向 GLM 注入两条长文档。
- 在召回率、提示词长度、响应时间和 token 成本之间更均衡。

当前保留指标：

```text
Top 8 线路命中率：84%
Top 8 站点命中率：100%
Top 8 联合命中率：80%
Top 8 MRR：0.5672
纯向量 Top 8 联合命中率：26%
混合检索提升：54 个百分点
```

### 9.6 本地 Cross-Encoder 重排序尝试

计划：使用 `BAAI/bge-reranker-base` 对融合召回的 Top 20 候选逐对评分，再选 Top 8。

完成内容：

- 已实现可选重排序接口。
- 已实现候选重排逻辑和自动化测试。
- 可通过 `RERANKER_ENABLED=true` 启用。

遇到的问题：模型约 1.04GB，Hugging Face 和镜像均因当前代理 SSL 中断而下载失败，因此没有获得可靠评测结果。

决定：默认关闭重排序，避免服务启动时反复下载或阻塞。不能把“代码已经写好”等同于“效果已经验证”。

### 9.7 候选线路多样性尝试与回滚

思路：对于只给起点和终点、没有指定线路的问题，在结果中为每条可能线路保留一条文档。

第一次尝试结果：

```text
线路命中率：84% -> 88%
联合命中率：保持 80%
MRR：0.5672 -> 0.5322
```

问题：强行把候选线路放在结果前部，虽然增加线路覆盖，但降低了正确结果的平均排名。

第二次尝试：只替换 Top 8 尾部最多两条结果。

```text
线路命中率：82%
联合命中率：78%
MRR：0.5644
```

决定：两种方案都不如当前最佳版本，因此已回滚，不保留到正式检索器中。

这个回滚很重要：优化不能只观察某一个指标，也不能为了保留代码而接受整体效果下降。

## 10. 面试说明参考

可以按以下思路介绍检索优化过程：

> 项目最初使用本地 BGE Embedding 和 FAISS，纯向量 Top 5 的线路—站点联合命中率只有 22%。分析失败案例后，我发现公交问答包含大量线路编号和站点专名，单纯语义相似度不够，因此加入字符关键词、metadata 过滤和排名融合，命中率提升到 36%。随后通过错误样本分析发现起点、终点识别顺序有 bug，修复后 Top 5 联合命中率达到 66%，站点命中率达到 100%。之后增加终点软排序，并比较 Top 5、Top 8、Top 10 的召回率和上下文成本，最终选择 Top 8，联合命中率达到 80%，比纯向量 Top 8 的 26% 提升 54 个百分点。我也尝试过候选线路多样性和 Cross-Encoder 重排序，但前者降低 MRR 后被回滚，后者因模型下载受限默认关闭。整个过程采用固定评测集和可重复脚本验证，而不是凭主观观察调整参数。

面试中可强调的技术点：

- Embedding 负责语义召回，metadata 负责业务实体精确约束。
- Hit@K 衡量目标是否进入候选集，MRR 衡量正确结果排得是否靠前。
- Top K 越大召回率通常越高，但会增加上下文长度、延迟和 token 成本。
- 软排序比硬过滤更适合不完整或不确定的终点信息。
- 每次优化必须使用相同评测集对比，并保留失败实验和回滚记录。

## 11. 模糊地点与确定性路线查询

### 11.1 问题来源

用户输入“我现在在高山湾，我想去奥体中心”时，旧系统只识别到“奥体中心”，没有把简称“高山湾”映射到正式站名“高山湾枢纽站”。因此系统把终点误当起点，并从历史问答案例中猜测线路和等待时间，产生方向相反、来源无关的回答。

### 11.2 优化流程

1. 根据正式站点表自动生成简称，例如：

```text
高山湾枢纽站 -> 高山湾
杨柳站 -> 杨柳
```

2. 按地点在问题中的出现顺序识别起点和终点，并在响应中返回别名映射。
3. 新增 Java 接口：

```text
GET /api/system/route-plan?origin=高山湾枢纽站&destination=奥体中心
```

4. Java 后端查询 MySQL 的 `new_route_station`、`new_station` 和 `ods_route_full`，要求同一线路、同一方向且终点序号大于起点序号。
5. 返回线路名称、方向、途经站数、首班时间和末班时间。
6. 路线拓扑结果优先级高于 RAG 历史案例。拓扑查询成功时不再向 GLM 注入历史案例，避免生成无关等待时间。
7. 如果只识别到一个地点，系统不猜测路线，而是要求用户补充完整起点和终点。

### 11.3 实际验证

输入：

```text
我现在在高山湾，我想去奥体中心
```

地点解析：

```text
起点：高山湾枢纽站
终点：奥体中心
别名：高山湾 -> 高山湾枢纽站
```

MySQL 拓扑查询返回三条直达候选：

```text
101路：8站
115路：12站
127路：13站
```

当前 Python 自动化测试为 `21 passed`，Java Maven 编译和 Vue 生产构建均通过。

### 11.4 本轮遇到的问题

- `bus_distillation_dataset.csv` 是训练快照，不能作为可靠线路规划拓扑；正式路线查询改为使用 MySQL 站序表。
- Java 控制器首次加入接口后，Service 方法没有实际落盘，Maven 编译及时发现并补齐。
- 系统 PATH 没有 Maven，最终使用用户 `.m2/wrapper/dists` 中已有的 Maven 3.9.11 完成构建。
- PowerShell 终端显示中文 JSON 时出现乱码，但 HTTP 返回的数据和浏览器 UTF-8 解码不受影响。

## 5. 前端接入（已完成基础改造）

已完成：

1. 移除“模型正在训练中”的固定回答和提前 `return`。
2. 调用新的 `/api/v1/chat`，发送最近 10 条历史消息、当前页面日期和 `top_k=8`。
3. 兼容新的 `data.response` 嵌套响应格式。
4. 在 AI 回答下方展示历史案例和实时 API 来源标签。
5. 显示后端返回的具体错误，并提示检查 LangChain 服务。
6. 保留原有加载动画和逐字输出效果。

前端生产构建已经通过：

```text
2182 modules transformed
vite build success
```

遇到的问题：

- 原前端在发送请求前固定返回占位文本，导致真实接口代码永远不可达，现已移除。
- 后端响应正文位于 `data.response`，原前端只读取顶层字段，现已修正。
- Vite 构建提示部分公共 chunk 超过 500KB；这不影响 RAG 功能，属于现有 Element Plus/ECharts 等依赖的打包优化问题，本阶段未扩大范围处理。
- 尚未进行浏览器端真实 GLM 联调；运行时需要先配置 `ZHIPUAI_API_KEY` 并启动 `langchain/app.py`。

## 6. 推荐实施顺序

```text
GLM 服务迁移
  -> 知识数据清洗
  -> FAISS 索引构建
  -> /retrieve 检索接口
  -> 静态 RAG /chat
  -> Java 实时数据融合
  -> 自动化评测
  -> Vue 前端接入
```

预计第一版静态 RAG 需要 2 至 3 天，加入实时数据、评测和前端联调后总计约 5 至 7 天。

## 7. 安全要求

- 已经在聊天、日志或截图中暴露的 API Key 必须立即轮换。
- API Key 只允许写入 `.env` 或系统环境变量。
- `.env`、向量索引和运行日志不得提交到 Git。
- 健康检查只能返回是否配置成功，不返回密钥内容。
- 对 GLM 请求增加超时、重试和调用次数限制，避免异常消耗额度。
