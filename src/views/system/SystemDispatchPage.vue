<template>
  <div class="dispatch-page">
    <section class="dispatch-left">
      <div style="text-align: right; font-size: 12px; color: #888; margin-bottom: 8px;">演示数据 / 模拟运行</div>
      <div class="dispatch-kpi-grid">
        <article class="metric-card">
          <div class="metric-card__label">推荐加班运力</div>
          <div class="metric-card__value">{{ dispatchMetrics.recommendedExtraVehicles }} 台</div>
          <div class="metric-card__meta">覆盖待处理线路 {{ dispatchTickets.length }} 条</div>
        </article>

        <article class="metric-card">
          <div class="metric-card__label">节约等待时间</div>
          <div class="metric-card__value">{{ dispatchMetrics.savedWaitMinutes }} 分钟</div>
          <div class="metric-card__trend metric-card__trend--good">
            <span>↓</span>
            基于后端调度场景估算
          </div>
        </article>

        <article class="metric-card">
          <div class="metric-card__label">路口联动</div>
          <div class="metric-card__value">{{ dispatchMetrics.coordinatedIntersections }} 次</div>
          <div class="metric-card__meta">已同步 {{ dispatchMetrics.signalPlanCount }} 组信号方案</div>
        </article>

        <article class="metric-card metric-card--adoption">
          <div class="metric-card__adoption-head">
            <div>
              <div class="metric-card__label">调度采纳率</div>
              <div class="metric-card__value metric-card__value--percent">{{ gaugeValue }}%</div>
            </div>
            <span class="metric-card__status-tag" :class="statusInfo.tagClass">
              {{ statusInfo.tagText }}
            </span>
          </div>

          <div class="metric-card__adoption-bar">
            <span :style="{ width: `${gaugeValue}%` }"></span>
          </div>

          <div class="metric-card__gauge-meta" :class="statusInfo.metaClass">
            {{ statusInfo.metaText }}
          </div>
        </article>
      </div>

      <div class="dispatch-lower-grid">
        <section class="dispatch-panel resource-panel">
          <div class="panel-head">
            <div>
              <h3>可用资源池</h3>
              <p>动态观察运力、司机与信号联动的即时可用性</p>
            </div>
          </div>

          <div class="resource-stack">
            <article
              v-for="item in resourceCards"
              :key="item.title"
              class="resource-card"
            >
              <div class="resource-card__head">
                <div class="resource-card__title-wrap">
                  <div class="resource-card__icon" :class="`is-${item.icon}`">
                    <svg v-if="item.icon === 'bus'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                      <rect x="4" y="5" width="16" height="10" rx="2"></rect>
                      <path d="M7 15v3M17 15v3M4 10h16M8 18h8"></path>
                      <circle cx="8" cy="18" r="1.2" fill="currentColor" stroke="none"></circle>
                      <circle cx="16" cy="18" r="1.2" fill="currentColor" stroke="none"></circle>
                    </svg>
                    <svg v-else-if="item.icon === 'driver'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                      <circle cx="12" cy="8" r="3.2"></circle>
                      <path d="M6.5 19a5.5 5.5 0 0 1 11 0"></path>
                    </svg>
                    <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                      <path d="M12 3v18M3 12h18"></path>
                      <path d="M7 7l10 10M17 7L7 17"></path>
                    </svg>
                  </div>
                  <div>
                    <h4>{{ item.title }}</h4>
                    <p>{{ item.subtitle }}</p>
                  </div>
                </div>
                <strong>{{ item.value }}</strong>
              </div>

              <el-progress
                :percentage="item.percent"
                :show-text="false"
                :stroke-width="6"
                :color="item.color"
              />
            </article>
          </div>
        </section>

        <section class="dispatch-panel queue-panel">
          <div class="panel-head">
            <div>
              <h3>发车策略队列</h3>
              <p>等待管理员确认的一线调度工单</p>
            </div>
            <el-tag type="info" round effect="light">实时刷新</el-tag>
          </div>

          <div class="queue-list">
            <article
              v-for="ticket in dispatchTickets"
              :key="ticket.id"
              class="ticket-card"
            >
              <div class="ticket-card__rail" :class="`is-${ticket.tagType}`"></div>
              <div class="ticket-card__main">
                <div class="ticket-card__top">
                  <div class="ticket-card__headline">
                    <el-tag
                      round
                      effect="light"
                      :type="ticket.tagType"
                      class="ticket-card__tag"
                    >
                      {{ ticket.tag }}
                    </el-tag>
                    <span class="ticket-card__line">{{ ticket.line }}</span>
                  </div>
                  <span class="ticket-card__time">{{ ticket.time }}</span>
                </div>

                <h4>{{ ticket.title }}</h4>
                <p>{{ ticket.reason }}</p>
                <div class="ticket-card__meta-row">
                  <span>{{ ticket.impact }}</span>
                  <span>{{ ticket.resource }}</span>
                  <span>{{ ticket.priority }}</span>
                </div>
                <div class="ticket-card__footer">
                  <span class="ticket-card__hint">待确认调度工单</span>
                  <div class="ticket-card__actions">
                    <el-button type="success" size="small" round>一键下发</el-button>
                    <el-button size="small" round>忽略</el-button>
                  </div>
                </div>
              </div>
            </article>
          </div>
        </section>
      </div>
    </section>

    <section class="dispatch-right">
      <section class="dispatch-panel brain-panel">
        <div class="brain-header">
          <div>
            <div class="brain-title">
              <span class="brain-status-dot"></span>
              <h3>AI 辅助分析</h3>
            </div>
            <p>基于 LangChain RAG 调度助手，融合实时客流、车辆资源、天气状态、历史调度案例和公交规则，为线路拥堵、站点客流异常和备用运力调配提供可追溯的智能建议。</p>
          </div>
        </div>

        <div class="quick-tags">
          <button
            v-for="item in quickPrompts"
            :key="item"
            type="button"
            class="quick-tags__item"
            @click="sendMessage(item)"
          >
            {{ item }}
          </button>
        </div>

        <el-scrollbar ref="chatScrollbarRef" class="chat-scrollbar">
          <div class="chat-list">
            <div
              v-for="message in messages"
              :key="message.id"
              class="chat-item"
              :class="message.role === 'user' ? 'is-user' : 'is-assistant'"
            >
              <div class="chat-bubble">
                <div class="chat-bubble__label">
                  {{ message.role === 'user' ? '调度员' : 'AI 调度助手' }}
                </div>
                <p>{{ message.content }}</p>
              </div>
            </div>

            <div v-if="isLoading" class="chat-item is-assistant">
              <div class="chat-bubble chat-bubble--loading">
                <div class="chat-bubble__label">AI 调度助手</div>
                <div class="typing-dots">
                  <span></span>
                  <span></span>
                  <span></span>
                </div>
              </div>
            </div>
          </div>
        </el-scrollbar>

        <div class="chat-input">
          <el-input
            v-model="draft"
            type="textarea"
            :rows="3"
            resize="none"
            placeholder="请输入线路、站点或调度问题，例如：127路奥体中心小雨天气是否需要调整发车间隔？"
            @keydown.enter.exact.prevent="sendMessage()"
          />
          <div class="chat-input__actions">
            <div class="chat-input__hint" style="display: flex; gap: 12px; align-items: center; font-size: 12px;">
              <span style="display: flex; align-items: center; gap: 4px; color: #67c23a;">
                <span style="width: 6px; height: 6px; background-color: #67c23a; border-radius: 50%;"></span>
                服务状态：已连接
              </span>
              <span style="color: #909399;">知识库：2442 条</span>
              <span style="color: #909399;">检索模式：混合检索</span>
            </div>
            <el-button
              type="primary"
              :loading="isLoading"
              class="send-button"
              @click="sendMessage()"
            >
              发送
            </el-button>
          </div>
        </div>
      </section>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { ElButton, ElInput, ElProgress, ElScrollbar, ElTag } from 'element-plus'

const props = defineProps({
  pageData: {
    type: Object,
    default: () => ({})
  }
})

const quickPrompts = [
  '分析拥堵原因',
  '评估备用运力',
  '生成调度建议',
  '查看引用依据'
]

const MODEL_TRAINING_REPLY = '模型正在训练中，敬请期待。'

const fallbackDispatchTickets = [
  {
    id: 't1',
    line: '105路',
    title: '建议高峰增发 1 班补车',
    reason: '宏声桥溢出 120 人，间隔 14 分钟。',
    impact: '影响站点 3 个',
    resource: '可调车辆 2 台',
    priority: '建议 10 分钟内确认',
    tag: '防拥堵预警',
    tagType: 'warning',
    time: '17:20'
  },
  {
    id: 't2',
    line: '202路',
    title: '建议压缩发车间隔至 8 分钟',
    reason: '滨江路方向客流抬升，站台积压。',
    impact: '预计疏散 260 人',
    resource: '需联动车辆 3 台',
    priority: '优先级高',
    tag: '紧急增派',
    tagType: 'danger',
    time: '17:32'
  },
  {
    id: 't3',
    line: '108路',
    title: '建议启用备用司机轮换',
    reason: '备用司机可覆盖 2 条支援线。',
    impact: '覆盖支援线 2 条',
    resource: '备用司机 4 名',
    priority: '可排入下一批',
    tag: '调度优化',
    tagType: 'success',
    time: '17:45'
  },
  {
    id: 't4',
    line: '301路',
    title: '建议联动信号优先放行',
    reason: '连续两个路口拥堵，车辆周转效率下降。',
    impact: '预计压缩 4 分钟',
    resource: '联动信号 2 组',
    priority: '持续观察',
    tag: '信号协同',
    tagType: 'success',
    time: '17:56'
  }
]

const fallbackResourceCards = [
  {
    title: '车辆待命资源',
    subtitle: '待命 69 / 总计 2800',
    value: '2.5%',
    percent: 18,
    color: '#63c7ff',
    icon: 'bus'
  },
  {
    title: '备用司机储备',
    subtitle: '备用 259 名',
    value: '62%',
    percent: 62,
    color: '#52c2a3',
    icon: 'driver'
  },
  {
    title: '信号联动方案',
    subtitle: '可执行方案 21 组',
    value: '75%',
    percent: 75,
    color: '#7c9cff',
    icon: 'signal'
  },
  {
    title: '充电补能窗口',
    subtitle: '可用桩位 18 个',
    value: '41%',
    percent: 41,
    color: '#38bdf8',
    icon: 'power'
  },
  {
    title: '站务联动人员',
    subtitle: '重点站待命 36 人',
    value: '86%',
    percent: 86,
    color: '#f59e0b',
    icon: 'staff'
  },
  {
    title: '司机余量',
    subtitle: '可补充 21 名',
    value: '21名',
    percent: 72,
    color: '#22c55e',
    icon: 'driver'
  }
]

function createMessageId() {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID()
  }

  return `dispatch-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

const draft = ref('')
const isLoading = ref(false)
const messages = ref([
  {
    id: createMessageId(),
    role: 'assistant',
    content: '您好，我已接入调度分析服务。您可以让我分析拥堵原因、评估备用运力或生成线路调度建议。'
  }
])

const chatScrollbarRef = ref(null)
let typewriterTimer = null

function numberOf(value, fallback = 0) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function textOf(value, fallback = '') {
  if (value === undefined || value === null || value === '') {
    return fallback
  }

  return String(value)
}

const dispatchMetrics = computed(() => {
  const metrics = props.pageData?.metrics || {}
  const resources = props.pageData?.resourcePool || {}
  const strategies = Array.isArray(props.pageData?.strategies) ? props.pageData.strategies : []

  return {
    recommendedExtraVehicles: numberOf(metrics.recommendedExtraVehicles, 4),
    savedWaitMinutes: numberOf(metrics.savedWaitMinutes, 12),
    coordinatedIntersections: numberOf(metrics.coordinatedIntersections, 3),
    dispatchAdoptionRate: numberOf(metrics.dispatchAdoptionRate, 82),
    signalPlanCount: numberOf(resources.signalPlans, strategies.length)
  }
})

const dispatchTickets = computed(() => {
  const strategies = Array.isArray(props.pageData?.strategies) ? props.pageData.strategies : []
  if (!strategies.length) {
    return fallbackDispatchTickets
  }

  const tickets = strategies.map((item, index) => ({
    id: `strategy-${index + 1}`,
    line: textOf(item.line, `线路${index + 1}`),
    title: textOf(item.action, '建议调整发车策略'),
    reason: textOf(item.reason, '后端调度模型识别到客流压力变化。'),
    impact: textOf(item.impact, `预计疏散 ${numberOf(item.expectedRelief, 0)} 人`),
    resource: textOf(item.resource, `建议补车 ${numberOf(item.extraVehicles, 1)} 台`),
    priority: textOf(item.priority, index === 0 ? '优先级高' : '可排入下一批'),
    tag: textOf(item.tag, index === 0 ? '防拥堵预警' : '调度优化'),
    tagType: textOf(item.tagType, index === 0 ? 'warning' : 'success'),
    time: textOf(item.time, `17:${20 + index * 12}`)
  }))

  if (tickets.length < 4) {
    tickets.push({
      id: 'strategy-signal-linkage',
      line: textOf(tickets[0]?.line, '重点线路'),
      title: '建议联动信号优先放行',
      reason: '高峰方向车辆周转承压，建议同步释放路口通行能力。',
      impact: '预计压缩 4 分钟',
      resource: `联动信号 ${dispatchMetrics.value.signalPlanCount} 组`,
      priority: '可排入下一批',
      tag: '信号协同',
      tagType: 'success',
      time: '17:56'
    })
  }

  return tickets.slice(0, 4)
})

const resourceCards = computed(() => {
  const resources = props.pageData?.resourcePool || {}
  if (!Object.keys(resources).length) {
    return fallbackResourceCards
  }

  const activeVehicles = numberOf(resources.activeVehicles, 0)
  const standbyVehicles = numberOf(resources.standbyVehicles, 0)
  const backupDrivers = numberOf(resources.backupDrivers, 0)
  const signalPlans = numberOf(resources.signalPlans, 0)
  const vehicleDemand = Math.max(1, dispatchMetrics.value.recommendedExtraVehicles)
  const standbyPercent = Math.min(100, Math.round((standbyVehicles / vehicleDemand) * 100))
  const driverDemand = Math.max(1, dispatchTickets.value.length * 2)
  const driverPercent = Math.min(100, Math.round((backupDrivers / driverDemand) * 100))
  const vehicleCoverage = Math.min(100, Math.round((standbyVehicles / vehicleDemand) * 100))
  const driverMargin = Math.max(0, backupDrivers - driverDemand)
  const signalPercent = Math.min(100, signalPlans * 4)

  return [
    {
      title: '车辆待命资源',
      subtitle: `待命 ${standbyVehicles} / 推荐需求 ${vehicleDemand}`,
      value: `${standbyPercent}%`,
      percent: Math.max(0, Math.min(100, standbyPercent)),
      color: '#63c7ff',
      icon: 'bus'
    },
    {
      title: '备用司机储备',
      subtitle: `备用 ${backupDrivers} 名 / 需求 ${driverDemand} 名`,
      value: `${driverPercent}%`,
      percent: driverPercent,
      color: '#52c2a3',
      icon: 'driver'
    },
    {
      title: '信号联动方案',
      subtitle: `可执行方案 ${signalPlans} 组`,
      value: `${signalPercent}%`,
      percent: signalPercent,
      color: '#7c9cff',
      icon: 'signal'
    },
    {
      title: '车辆覆盖',
      subtitle: `可覆盖 ${Math.min(standbyVehicles, vehicleDemand)} / ${vehicleDemand} 台`,
      value: `${vehicleCoverage}%`,
      percent: vehicleCoverage,
      color: '#38bdf8',
      icon: 'bus'
    },
    {
      title: '司机余量',
      subtitle: `备用司机 ${backupDrivers} 名`,
      value: `${Math.max(0, backupDrivers - dispatchTickets.value.length * 2)} 名`,
      percent: Math.min(100, Math.round((driverMargin / Math.max(1, backupDrivers)) * 100)),
      color: '#22c55e',
      icon: 'driver'
    },
    {
      title: '路口联动',
      subtitle: `已联动 ${dispatchMetrics.value.coordinatedIntersections} 次`,
      value: `${signalPlans} 组`,
      percent: signalPercent,
      color: '#f59e0b',
      icon: 'signal'
    }
  ]
})

const gaugeValue = computed(() => Math.max(0, Math.min(100, numberOf(dispatchMetrics.value.dispatchAdoptionRate, 0))))
const gaugeRadius = 44
const gaugeCircumference = 2 * Math.PI * gaugeRadius
const gaugeOffset = computed(() => gaugeCircumference * (1 - gaugeValue.value / 100))
const statusInfo = computed(() => {
  if (gaugeValue.value >= 85) {
    return {
      tagText: '稳定',
      tagClass: 'is-stable',
      strokeClass: 'is-stable',
      metaClass: 'is-stable',
      metaText: '策略反馈健康，执行闭环稳定'
    }
  }

  if (gaugeValue.value >= 70) {
    return {
      tagText: '注意',
      tagClass: 'is-warning',
      strokeClass: 'is-warning',
      metaClass: 'is-warning',
      metaText: '部分策略被人工干预，建议复盘'
    }
  }

  return {
    tagText: '风险',
    tagClass: 'is-risk',
    strokeClass: 'is-risk',
    metaClass: 'is-risk',
    metaText: '采纳率偏低，请仔细检查算法模型'
  }
})

function scrollToBottom() {
  nextTick(() => {
    const wrap = chatScrollbarRef.value?.wrapRef
    if (wrap) {
      wrap.scrollTop = wrap.scrollHeight
    }
  })
}

function pushUserMessage(content) {
  messages.value.push({
    id: createMessageId(),
    role: 'user',
    content
  })
  scrollToBottom()
}

function typeAssistantMessage(fullText) {
  return new Promise((resolve) => {
    const target = {
      id: createMessageId(),
      role: 'assistant',
      content: ''
    }
    messages.value.push(target)
    scrollToBottom()

    let index = 0
    clearInterval(typewriterTimer)
    typewriterTimer = setInterval(() => {
      index += 1
      target.content = fullText.slice(0, index)
      scrollToBottom()
      if (index >= fullText.length) {
        clearInterval(typewriterTimer)
        typewriterTimer = null
        resolve()
      }
    }, 16)
  })
}

async function sendMessage(preset = '') {
  const prompt = (preset || draft.value).trim()
  if (!prompt || isLoading.value) return

  if (!preset) {
    draft.value = ''
  }

  pushUserMessage(prompt)
  isLoading.value = true

  await typeAssistantMessage(MODEL_TRAINING_REPLY)
  isLoading.value = false
  return

  try {
    const response = await fetch('/api/v1/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ prompt })
    })

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }

    const data = await response.json()
    const answer =
      data?.response ||
      data?.answer ||
      data?.message ||
      '调度服务已响应，但未返回有效文本。'

    await typeAssistantMessage(answer)
  } catch (error) {
    await typeAssistantMessage(`当前调度服务暂时不可用：${error.message}。请先确认本地 OD 客服微服务已启动。`)
  } finally {
    isLoading.value = false
  }
}

onBeforeUnmount(() => {
  clearInterval(typewriterTimer)
})
</script>

<style scoped>
:global(*) {
  box-sizing: border-box;
}

.dispatch-page {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(340px, 0.85fr);
  gap: 13px;
  height: 100%;
  max-height: 100%;
  min-height: 0;
  padding: 13px;
  border: 1px solid rgba(14, 129, 157, 0.14);
  border-radius: 26px;
  background:
    linear-gradient(135deg, rgba(238, 247, 252, 0.8), rgba(255, 255, 255, 0.9)),
    radial-gradient(circle at 72% 18%, rgba(45, 183, 222, 0.1), transparent 34%);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    0 18px 38px rgba(24, 83, 116, 0.09);
  overflow: hidden;
  text-align: left;
}

.dispatch-left,
.dispatch-right {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 13px;
  overflow: hidden;
}

.dispatch-kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  flex: 0 0 auto;
}

.dispatch-panel,
.metric-card {
  border-radius: 24px;
  background: #ffffff;
  box-shadow:
    0 14px 30px rgba(31, 76, 105, 0.1),
    0 1px 0 rgba(255, 255, 255, 0.94) inset;
  border: 1px solid rgba(190, 213, 228, 0.78);
  overflow: hidden;
}

.metric-card {
  position: relative;
  display: flex;
  min-height: 116px;
  padding: 15px 16px 13px;
  flex-direction: column;
  justify-content: space-between;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.98) 0%, rgba(247, 251, 255, 0.92) 100%);
}

.metric-card::before {
  content: "";
  position: absolute;
  top: 0;
  left: 14px;
  right: 14px;
  height: 3px;
  border-radius: 0 0 999px 999px;
  background: linear-gradient(90deg, #26b9e9, #51d3ec);
}

.metric-card:nth-child(2)::before {
  background: linear-gradient(90deg, #16c784, #66d7a8);
}

.metric-card:nth-child(3)::before {
  background: linear-gradient(90deg, #6d6af7, #8bb4ff);
}

.metric-card:nth-child(4)::before {
  background: linear-gradient(90deg, #20b9dd, #21c978);
}

.metric-card--adoption {
  min-height: 116px;
  padding: 15px 16px 13px;
  background:
    radial-gradient(circle at top right, rgba(39, 195, 159, 0.16), transparent 36%),
    linear-gradient(180deg, #ffffff 0%, #f7fbff 100%);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.metric-card--adoption::after {
  content: none;
}

.metric-card__adoption-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.metric-card__adoption-head .metric-card__label {
  color: #374151;
  font-size: 14px;
  font-weight: 800;
}

.metric-card__status-tag {
  height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  white-space: nowrap;
  border: 1px solid transparent;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.03em;
  flex: 0 0 auto;
}

.metric-card__status-tag.is-stable {
  color: #059669;
  background: #ecfdf5;
  border-color: #bbf7d0;
}

.metric-card__status-tag.is-warning {
  color: #d97706;
  background: #fffbeb;
  border-color: #fde68a;
}

.metric-card__status-tag.is-risk {
  color: #e11d48;
  background: #fff1f2;
  border-color: #fecdd3;
}

.metric-card__gauge-meta {
  margin-top: 0;
  padding: 5px 8px;
  border-radius: 10px;
  background: rgba(238, 246, 250, 0.82);
  color: #6b7280;
  font-size: 11.5px;
  line-height: 1.3;
  text-align: left;
}

.metric-card__gauge-meta.is-warning {
  color: #92400e;
}

.metric-card__gauge-meta.is-risk {
  color: #e11d48;
}

.metric-card__label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #52697a;
  font-size: 12px;
  font-weight: 800;
  white-space: nowrap;
}

.metric-card__label::before {
  content: "";
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #26b9e9;
  box-shadow: 0 0 0 4px rgba(38, 185, 233, 0.1);
  flex: 0 0 auto;
}

.metric-card:nth-child(2) .metric-card__label::before {
  background: #16c784;
  box-shadow: 0 0 0 4px rgba(22, 199, 132, 0.1);
}

.metric-card:nth-child(3) .metric-card__label::before {
  background: #6d6af7;
  box-shadow: 0 0 0 4px rgba(109, 106, 247, 0.1);
}

.metric-card:nth-child(4) .metric-card__label::before {
  background: #20b9dd;
  box-shadow: 0 0 0 4px rgba(32, 185, 221, 0.1);
}

.metric-card__value {
  margin-top: 8px;
  color: #0f172a;
  font-size: 27px;
  line-height: 1.05;
  font-weight: 800;
  white-space: nowrap;
}

.metric-card__value--percent {
  font-size: 26px;
  color: #0f4260;
}

.metric-card__meta {
  width: max-content;
  max-width: 100%;
  margin-top: 10px;
  padding: 5px 8px;
  border-radius: 999px;
  background: rgba(238, 246, 250, 0.9);
  color: #6f8290;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.metric-card__gauge-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 96px;
  align-items: center;
  gap: 12px;
  height: 100%;
  min-width: 0;
}

.metric-card__gauge-layout > div:first-child {
  min-width: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.metric-card__trend {
  width: max-content;
  max-width: 100%;
  margin-top: 10px;
  padding: 5px 8px;
  border-radius: 999px;
  background: rgba(236, 253, 245, 0.9);
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.metric-card__trend span {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #dcfce7;
  color: #059669;
  flex: 0 0 auto;
}

.metric-card__adoption-bar {
  height: 8px;
  border-radius: 999px;
  background: #e4edf5;
  overflow: hidden;
}

.metric-card__adoption-bar span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #12b7e8 0%, #22c55e 100%);
  box-shadow: 0 6px 14px rgba(34, 197, 94, 0.24);
}

.metric-card__trend--good {
  color: #0f9f6e;
}

.dispatch-gauge {
  position: relative;
  width: 132px;
  height: 132px;
  display: grid;
  place-items: center;
}

.dispatch-gauge--compact {
  width: 74px;
  height: 74px;
  flex: 0 0 74px;
}

.dispatch-gauge--compact .dispatch-gauge__svg {
  width: 74px;
  height: 74px;
}

.dispatch-gauge--compact .dispatch-gauge__content strong {
  font-size: 16px;
}

.dispatch-gauge--compact .dispatch-gauge__content span {
  margin-top: 1px;
  font-size: 10px;
  letter-spacing: 0.04em;
}

.dispatch-gauge__svg {
  width: 132px;
  height: 132px;
  transform: rotate(-90deg);
}

.dispatch-gauge__track {
  fill: none;
  stroke: #dbe8f3;
  stroke-width: 10;
}

.dispatch-gauge__progress {
  fill: none;
  stroke: #27c39f;
  stroke-width: 10;
  stroke-linecap: round;
  transition: stroke-dashoffset 0.4s ease;
}

.dispatch-gauge__progress.is-stable {
  stroke: #27c39f;
}

.dispatch-gauge__progress.is-warning {
  stroke: #f59e0b;
}

.dispatch-gauge__progress.is-risk {
  stroke: #f43f5e;
}

.dispatch-gauge__content {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.dispatch-gauge__content strong {
  color: #0f172a;
  font-size: 28px;
  font-weight: 800;
}

.dispatch-gauge__content span {
  margin-top: 4px;
  color: #6b7280;
  font-size: 12px;
  font-weight: 600;
}

.resource-panel,
.queue-panel,
.brain-panel {
  padding: 15px 16px;
}

.dispatch-lower-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 13px;
  min-height: 0;
  flex: 1;
  align-items: stretch;
}

.resource-panel,
.queue-panel {
  min-height: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
}

.resource-panel .panel-head,
.queue-panel .panel-head {
  align-items: center;
}

.resource-panel .panel-head p,
.queue-panel .panel-head p {
  display: none;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.panel-head h3,
.brain-title h3 {
  margin: 0;
  color: #0f172a;
  font-size: 20px;
  font-weight: 800;
}

.panel-head p,
.brain-header p {
  margin: 6px 0 0;
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.45;
}

.resource-stack {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: repeat(6, minmax(0, 1fr));
  gap: 8px;
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
  padding-right: 0;
}

.resource-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
  margin-top: 8px;
}

.resource-summary__item {
  min-width: 0;
  padding: 7px 9px;
  border-radius: 12px;
  background: linear-gradient(180deg, #f8fbff 0%, #f2f8fd 100%);
  border: 1px solid rgba(219, 232, 242, 0.94);
}

.resource-summary__item span,
.resource-summary__item small {
  display: block;
  overflow: hidden;
  color: #7c8fa1;
  font-size: 9.5px;
  font-weight: 800;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-summary__item strong {
  display: block;
  margin: 3px 0;
  color: #0f172a;
  font-size: 15px;
  line-height: 1;
  font-weight: 900;
}

.resource-actions {
  flex: 1;
  min-height: 0;
  margin-top: 8px;
  padding: 9px 11px;
  border-radius: 14px;
  background:
    radial-gradient(circle at 92% 8%, rgba(56, 189, 248, 0.16), transparent 38%),
    linear-gradient(180deg, #fbfdff 0%, #f5fafe 100%);
  border: 1px solid rgba(207, 225, 238, 0.92);
}

.resource-actions__title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #0f172a;
  font-size: 12px;
  font-weight: 900;
}

.resource-actions__title span {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #16c2ff;
  box-shadow: 0 0 0 4px rgba(22, 194, 255, 0.12);
}

.resource-actions ul {
  display: grid;
  gap: 4px;
  margin: 7px 0 0;
  padding: 0;
  list-style: none;
}

.resource-actions li {
  position: relative;
  padding-left: 14px;
  color: #60758a;
  font-size: 11px;
  font-weight: 760;
  line-height: 1.34;
}

.resource-actions li::before {
  position: absolute;
  top: 0.62em;
  left: 0;
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: #35c4d9;
  content: "";
}

.resource-card {
  min-height: 0;
  padding: 11px 13px;
  border-radius: 15px;
  background: linear-gradient(180deg, #fbfdff 0%, #f5fafe 100%);
  border: 1px solid rgba(207, 225, 238, 0.92);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.94),
    0 8px 18px rgba(32, 84, 117, 0.045);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.resource-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.resource-card__title-wrap {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.resource-card__icon {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  color: #fff;
}

.resource-card__icon svg {
  width: 15px;
  height: 15px;
}

.resource-card__icon.is-bus {
  background: linear-gradient(135deg, #16c2ff, #2f7dff);
}

.resource-card__icon.is-driver {
  background: linear-gradient(135deg, #22c55e, #0ea5a8);
}

.resource-card__icon.is-signal {
  background: linear-gradient(135deg, #818cf8, #4f46e5);
}

.resource-card__icon.is-power {
  background: linear-gradient(135deg, #38bdf8, #0ea5e9);
}

.resource-card__icon.is-staff {
  background: linear-gradient(135deg, #fbbf24, #f97316);
}

.resource-card h4 {
  margin: 0;
  color: #0f172a;
  font-size: 13px;
  font-weight: 800;
  white-space: nowrap;
}

.resource-card p {
  margin: 2px 0 0;
  color: #94a3b8;
  font-size: 10.5px;
  line-height: 1.22;
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
}

.resource-card__head strong {
  color: #0f172a;
  font-size: 16px;
  font-weight: 900;
  white-space: nowrap;
}

.dispatch-right {
  gap: 0;
  min-height: 0;
  height: 100%;
}

.queue-list {
  display: grid;
  grid-template-rows: none;
  grid-auto-rows: minmax(174px, max-content);
  gap: 14px;
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 10px 14px 2px;
  scrollbar-width: thin;
}

.ticket-card {
  display: flex;
  align-items: stretch;
  gap: 0;
  min-height: 174px;
  padding: 0;
  border-radius: 17px;
  background: linear-gradient(180deg, #fbfdff 0%, #f4f8fc 100%);
  border: 1px solid rgba(227, 234, 242, 0.95);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.04);
  overflow: hidden;
}

.ticket-card__main {
  min-width: 0;
  flex: 1;
  padding: 14px 18px;
  display: flex;
  flex-direction: column;
  gap: 7px;
  justify-content: flex-start;
}

.ticket-card__top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.ticket-card__headline {
  min-width: 0;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 10px;
}

.ticket-card__line {
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.ticket-card__time {
  color: #94a3b8;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.ticket-card h4 {
  margin: 2px 0 0;
  color: #0f172a;
  font-size: 15px;
  font-weight: 900;
  line-height: 1.35;
}

.ticket-card p {
  display: inline-flex;
  align-items: center;
  align-self: flex-start;
  max-width: 100%;
  min-height: 24px;
  margin: 0;
  padding: 3px 8px;
  border-radius: 8px;
  background: rgba(248, 251, 254, 0.92);
  border: 1px solid rgba(226, 235, 244, 0.95);
  color: #66788c;
  font-size: 11.5px;
  font-weight: 750;
  line-height: 1.25;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ticket-card__meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 3px;
  padding-top: 0;
}

.ticket-card__meta-row span {
  min-width: 0;
  max-width: 100%;
  padding: 5px 8px;
  border-radius: 999px;
  background: rgba(239, 246, 255, 0.9);
  border: 1px solid rgba(191, 219, 254, 0.78);
  color: #456179;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.1;
  white-space: nowrap;
}

.ticket-card__footer {
  display: none;
  min-height: 26px;
  margin-top: auto;
  padding-top: 2px;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.ticket-card__hint {
  min-width: 0;
  overflow: hidden;
  color: #94a3b8;
  font-size: 11px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ticket-card__actions {
  display: flex;
  flex-direction: row;
  gap: 6px;
  flex: 0 0 auto;
  min-width: 0;
}

.ticket-card__actions :deep(.el-button) {
  min-width: 46px;
  height: 22px;
  padding: 2px 8px;
  font-size: 10.5px;
}

.ticket-card__rail {
  width: 4px;
  flex: 0 0 4px;
  background: #94a3b8;
}

.ticket-card__rail.is-warning {
  background: linear-gradient(180deg, #f59e0b 0%, #fbbf24 100%);
}

.ticket-card__rail.is-danger {
  background: linear-gradient(180deg, #fb7185 0%, #ef4444 100%);
}

.ticket-card__rail.is-success {
  background: linear-gradient(180deg, #34d399 0%, #10b981 100%);
}

@media (max-width: 1100px) {
  .ticket-card__footer {
    flex-direction: column;
    align-items: flex-start;
  }

  .ticket-card__actions {
    width: 100%;
  }

  .ticket-card__actions :deep(.el-button) {
    flex: 1 1 0;
    min-width: 0;
  }
}

.brain-panel {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.brain-header {
  margin-bottom: 10px;
}

.brain-title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brain-status-dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  background: #22c55e;
  box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.5);
  animation: pulse 1.8s infinite;
}

.quick-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.quick-tags__item {
  padding: 7px 11px;
  border: 1px solid rgba(191, 219, 254, 0.95);
  border-radius: 999px;
  background: #eff8ff;
  color: #0284c7;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}

.quick-tags__item:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 20px rgba(14, 165, 233, 0.12);
}

.chat-scrollbar {
  flex: 1;
  min-height: 0;
  padding-right: 4px;
}

.chat-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 2px;
}

.chat-item {
  display: flex;
}

.chat-item.is-user {
  justify-content: flex-end;
}

.chat-item.is-assistant {
  justify-content: flex-start;
}

.chat-bubble {
  max-width: 88%;
  padding: 10px 12px;
  border-radius: 18px;
  background: #f8fbff;
  border: 1px solid rgba(227, 234, 242, 0.95);
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.03);
}

.chat-item.is-user .chat-bubble {
  background: linear-gradient(135deg, #12c7f6, #1379ff);
  color: #fff;
  border-color: transparent;
}

.chat-bubble__label {
  margin-bottom: 6px;
  font-size: 11px;
  font-weight: 800;
  color: #94a3b8;
}

.chat-item.is-user .chat-bubble__label {
  color: rgba(255, 255, 255, 0.8);
}

.chat-bubble p {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.55;
  font-size: 12px;
}

.chat-bubble--loading {
  min-width: 120px;
}

.typing-dots {
  display: inline-flex;
  gap: 6px;
}

.typing-dots span {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #60a5fa;
  animation: bounce 1.2s infinite ease-in-out;
}

.typing-dots span:nth-child(2) {
  animation-delay: 0.16s;
}

.typing-dots span:nth-child(3) {
  animation-delay: 0.32s;
}

.chat-input {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid rgba(226, 232, 240, 0.9);
}

.chat-input__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
}

.chat-input :deep(.el-textarea__inner) {
  min-height: 76px !important;
}

.chat-input__hint {
  color: #94a3b8;
  font-size: 12px;
}

.send-button {
  min-width: 96px;
}

@keyframes pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.45);
  }
  70% {
    box-shadow: 0 0 0 10px rgba(34, 197, 94, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(34, 197, 94, 0);
  }
}

@keyframes bounce {
  0%, 80%, 100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  40% {
    transform: translateY(-4px);
    opacity: 1;
  }
}

@media (max-width: 1440px) {
  .dispatch-page {
    grid-template-columns: minmax(0, 1.58fr) minmax(340px, 0.82fr);
  }

  .dispatch-kpi-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .metric-card__label,
  .metric-card__adoption-head .metric-card__label {
    font-size: 12px;
  }

  .metric-card__value {
    font-size: 26px;
  }

  .metric-card__value--percent {
    font-size: 25px;
  }

  .metric-card__meta,
  .metric-card__trend {
    font-size: 11px;
  }
}

@media (max-width: 1700px) {
  .ticket-card__footer {
    display: none;
  }

  .ticket-card__main {
    justify-content: flex-start;
    padding: 14px 17px;
  }

  .queue-list {
    gap: 14px;
  }
}

@media (max-width: 1100px) {
  .dispatch-page {
    grid-template-columns: 1fr;
    height: auto;
    max-height: none;
  }

  .dispatch-lower-grid,
  .dispatch-kpi-grid {
    grid-template-columns: 1fr;
  }
}
</style>
