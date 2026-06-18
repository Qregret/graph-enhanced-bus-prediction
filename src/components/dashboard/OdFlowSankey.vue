<template>
  <div ref="rootRef" class="od-flow-widget" @mouseleave="clearHover">
    <div class="od-flow-toolbar">
      <label class="od-flow-filter">
        <span>起点</span>
        <select v-model="selectedOrigin">
          <option value="">所有起点</option>
          <option v-for="origin in origins" :key="origin" :value="origin">{{ origin }}</option>
        </select>
      </label>
    </div>

    <div class="od-flow-canvas">
      <svg class="od-flow-svg" :viewBox="`0 0 ${viewBox.width} ${viewBox.height}`" role="img" aria-label="OD passenger flow Sankey">
        <defs>
          <filter id="odFlowGlow" x="-30%" y="-30%" width="160%" height="160%">
            <feGaussianBlur stdDeviation="3" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>

        <g class="od-flow-grid">
          <line v-for="tick in gridTicks" :key="tick" :x1="tick" y1="22" :x2="tick" :y2="viewBox.height - 20" />
        </g>

        <g class="od-flow-links">
          <path
            v-for="link in layout.links"
            :key="link.id"
            class="od-flow-link"
            :class="{ 'is-active': isLinkActive(link), 'is-dimmed': isDimmed(link) }"
            :d="link.path"
            :stroke="link.color"
            :stroke-width="link.width"
            @mouseenter="setHover('link', link)"
            @mousemove="moveTooltip"
          />
        </g>

        <g class="od-flow-nodes">
          <g
            v-for="node in layout.nodes"
            :key="node.id"
            class="od-flow-node"
            :class="{ 'is-active': isNodeActive(node), 'is-dimmed': isDimmed(node) }"
            :transform="`translate(${node.x}, ${node.y})`"
            @mouseenter="setHover('node', node)"
            @mousemove="moveTooltip"
          >
            <rect
              class="od-flow-node__body"
              :width="node.width"
              :height="node.height"
              rx="7"
              :fill="node.fill"
            />
            <rect
              class="od-flow-node__shine"
              :width="node.width"
              :height="Math.min(16, node.height)"
              rx="7"
            />
            <text
              class="od-flow-node__label"
              :x="node.side === 'origin' ? node.width + 10 : -10"
              :y="node.height / 2 - 4"
              :text-anchor="node.side === 'origin' ? 'start' : 'end'"
            >
              {{ node.name }}
            </text>
            <text
              class="od-flow-node__value"
              :x="node.side === 'origin' ? node.width + 10 : -10"
              :y="node.height / 2 + 10"
              :text-anchor="node.side === 'origin' ? 'start' : 'end'"
            >
              {{ node.total }} 人次
            </text>
          </g>
        </g>
      </svg>

      <div v-if="tooltip.visible" class="od-flow-tooltip" :style="tooltipStyle">
        <strong>{{ tooltip.title }}</strong>
        <span v-for="line in tooltip.lines" :key="line">{{ line }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'

const rawFlows = [
  { source: '高笋塘(新世纪)', target: '高笋塘(新世纪)', value: 45 },
  { source: '高笋塘(新世纪)', target: '实验中学', value: 25 },
  { source: '高笋塘(新世纪)', target: '洗墨路口', value: 15 },
  { source: '人武部', target: '高笋塘(新世纪)', value: 20 },
  { source: '人武部', target: '实验中学', value: 35 },
  { source: '人武部', target: '洗墨路口', value: 20 },
  { source: '行政服务中心', target: '高笋塘(新世纪)', value: 15 },
  { source: '行政服务中心', target: '实验中学', value: 20 },
  { source: '行政服务中心', target: '洗墨路口', value: 30 }
]

const originColors = {
  '高笋塘(新世纪)': '#00E5FF',
  人武部: '#FFB020',
  行政服务中心: '#7CFF6B'
}

const viewBox = { width: 380, height: 190 }
const nodeWidth = 14
const rootRef = ref(null)
const selectedOrigin = ref('')
const hovered = ref(null)
const tooltip = reactive({
  visible: false,
  x: 0,
  y: 0,
  title: '',
  lines: []
})

const origins = computed(() => [...new Set(rawFlows.map((item) => item.source))])
const destinations = computed(() => [...new Set(rawFlows.map((item) => item.target))])
const filteredFlows = computed(() => selectedOrigin.value ? rawFlows.filter((item) => item.source === selectedOrigin.value) : rawFlows)
const gridTicks = [94, 158, 222, 286]

const layout = computed(() => {
  const flows = filteredFlows.value
  const originNames = selectedOrigin.value ? [selectedOrigin.value] : origins.value
  const destinationNames = destinations.value
  const originTotals = totalBy(flows, 'source')
  const destinationTotals = totalBy(flows, 'target')
  const maxTotal = Math.max(...originNames.map((name) => originTotals.get(name) || 0), ...destinationNames.map((name) => destinationTotals.get(name) || 0), 1)

  const originNodes = buildNodes(originNames, originTotals, 'origin', 28, maxTotal)
  const destinationNodes = buildNodes(destinationNames, destinationTotals, 'destination', 322, maxTotal)
  const nodes = [...originNodes, ...destinationNodes]
  const nodeMap = new Map(nodes.map((node) => [node.id, node]))
  const sourceOffsets = new Map(originNames.map((name) => [name, 0]))
  const targetOffsets = new Map(destinationNames.map((name) => [name, 0]))
  const maxFlow = Math.max(...flows.map((flow) => flow.value), 1)

  const links = flows.map((flow) => {
    const source = nodeMap.get(`origin:${flow.source}`)
    const target = nodeMap.get(`destination:${flow.target}`)
    const sourceSlot = scaleFlow(flow.value, source.total, source.height)
    const targetSlot = scaleFlow(flow.value, target.total, target.height)
    const sourceOffset = sourceOffsets.get(flow.source) || 0
    const targetOffset = targetOffsets.get(flow.target) || 0
    const y1 = source.y + sourceOffset + sourceSlot / 2
    const y2 = target.y + targetOffset + targetSlot / 2
    const x1 = source.x + source.width
    const x2 = target.x
    const curve = Math.max(90, (x2 - x1) * 0.54)

    sourceOffsets.set(flow.source, sourceOffset + sourceSlot)
    targetOffsets.set(flow.target, targetOffset + targetSlot)

    return {
      ...flow,
      id: `${flow.source}->${flow.target}`,
      color: originColors[flow.source] || '#5EEAD4',
      width: 4 + (flow.value / maxFlow) * 13,
      path: `M ${x1} ${y1} C ${x1 + curve} ${y1}, ${x2 - curve} ${y2}, ${x2} ${y2}`
    }
  })

  return { nodes, links }
})

const tooltipStyle = computed(() => ({
  transform: `translate(${tooltip.x}px, ${tooltip.y}px)`
}))

function totalBy(flows, key) {
  return flows.reduce((map, flow) => {
    map.set(flow[key], (map.get(flow[key]) || 0) + flow.value)
    return map
  }, new Map())
}

function buildNodes(names, totals, side, x, maxTotal) {
  const minHeight = 24
  const maxHeight = 44
  const gap = names.length > 1 ? 18 : 0
  const heights = names.map((name) => minHeight + ((totals.get(name) || 0) / maxTotal) * (maxHeight - minHeight))
  const totalHeight = heights.reduce((sum, height) => sum + height, 0) + gap * Math.max(0, names.length - 1)
  let y = (viewBox.height - totalHeight) / 2

  return names.map((name, index) => {
    const height = heights[index]
    const node = {
      id: `${side}:${name}`,
      name,
      side,
      x,
      y,
      width: nodeWidth,
      height,
      total: totals.get(name) || 0,
      fill: side === 'origin' ? originColors[name] || '#5EEAD4' : '#38BDF8'
    }
    y += height + gap
    return node
  })
}

function scaleFlow(value, total, nodeHeight) {
  if (!total) return 0
  return Math.max(5, (value / total) * nodeHeight)
}

function setHover(type, item) {
  hovered.value = { type, item }
  tooltip.visible = true
  if (type === 'link') {
    tooltip.title = `${item.source} → ${item.target}`
    tooltip.lines = [`客流量：${item.value} 人次`]
    return
  }

  const related = rawFlows.filter((flow) => item.side === 'origin' ? flow.source === item.name : flow.target === item.name)
  tooltip.title = item.side === 'origin' ? `起点：${item.name}` : `终点：${item.name}`
  tooltip.lines = related.map((flow) => `${flow.source} → ${flow.target}：${flow.value} 人次`)
}

function moveTooltip(event) {
  const tooltipWidth = 260
  const tooltipHeight = 150
  const edge = 14
  tooltip.x = Math.min(event.clientX + 16, window.innerWidth - tooltipWidth - edge)
  tooltip.y = Math.max(edge, Math.min(event.clientY - 20, window.innerHeight - tooltipHeight - edge))
}

function clearHover() {
  hovered.value = null
  tooltip.visible = false
}

function isLinkActive(link) {
  const current = hovered.value
  if (!current) return false
  if (current.type === 'link') return current.item.id === link.id
  return current.item.side === 'origin' ? current.item.name === link.source : current.item.name === link.target
}

function isNodeActive(node) {
  const current = hovered.value
  if (!current) return false
  if (current.type === 'node') return current.item.id === node.id
  return node.name === current.item.source || node.name === current.item.target
}

function isDimmed(item) {
  if (!hovered.value) return false
  return item.path ? !isLinkActive(item) : !isNodeActive(item)
}
</script>

<style scoped>
.od-flow-widget {
  position: relative;
  flex: 1 1 0;
  height: auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: visible;
  border-radius: 14px;
  background:
    linear-gradient(135deg, rgba(7, 18, 42, 0.58), rgba(5, 12, 28, 0.46)),
    radial-gradient(circle at 18% 8%, rgba(0, 229, 255, 0.14), transparent 34%);
  border: 1px solid rgba(80, 220, 255, 0.18);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

.od-flow-toolbar {
  position: absolute;
  right: 0;
  top: -44px;
  z-index: 8;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0;
  width: 86px;
  height: 30px;
  padding: 0;
}

.od-flow-filter {
  display: inline-flex;
  align-items: center;
  gap: 0;
  padding: 5px 7px;
  border-radius: 8px;
  background: rgba(2, 12, 28, 0.72);
  border: 1px solid rgba(102, 217, 255, 0.2);
  color: rgba(226, 244, 255, 0.82);
  font-size: 10px;
  flex: 0 0 auto;
}

.od-flow-filter > span {
  display: none;
}

.od-flow-filter select {
  width: 74px;
  border: 0;
  outline: 0;
  color: #effcff;
  background: transparent;
  font-size: 10px;
  font-weight: 700;
}

.od-flow-filter option {
  color: #0f172a;
  background: #f8fbff;
}

.od-flow-canvas {
  position: relative;
  flex: 1;
  min-height: 0;
  padding: 0 0 2px;
  overflow: visible;
}

.od-flow-svg {
  width: 100%;
  height: 100%;
  display: block;
}

.od-flow-grid line {
  stroke: rgba(148, 221, 255, 0.12);
  stroke-width: 1;
}

.od-flow-link {
  fill: none;
  stroke-linecap: round;
  opacity: 0.72;
  mix-blend-mode: screen;
  transition: opacity 0.16s ease, stroke-width 0.16s ease, filter 0.16s ease;
}

.od-flow-link.is-active {
  opacity: 1;
  filter: url(#odFlowGlow);
}

.od-flow-link.is-dimmed {
  opacity: 0.08;
}

.od-flow-node {
  cursor: default;
  transition: opacity 0.16s ease, filter 0.16s ease;
}

.od-flow-node.is-active {
  filter: url(#odFlowGlow);
}

.od-flow-node.is-dimmed {
  opacity: 0.16;
}

.od-flow-node__body {
  stroke: rgba(255, 255, 255, 0.5);
  stroke-width: 1;
}

.od-flow-node__shine {
  fill: rgba(255, 255, 255, 0.22);
}

.od-flow-node__label {
  fill: #f4feff;
  font-size: 16px;
  font-weight: 800;
  paint-order: stroke;
  stroke: rgba(3, 8, 20, 0.84);
  stroke-width: 3px;
  stroke-linejoin: round;
}

.od-flow-node__value {
  fill: rgba(192, 232, 255, 0.74);
  font-size: 12px;
  font-weight: 700;
  paint-order: stroke;
  stroke: rgba(3, 8, 20, 0.84);
  stroke-width: 3px;
  stroke-linejoin: round;
}

.od-flow-tooltip {
  position: fixed;
  left: 0;
  top: 0;
  z-index: 9999;
  width: 260px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(3, 9, 22, 0.94);
  border: 1px solid rgba(91, 218, 255, 0.42);
  box-shadow: 0 12px 30px rgba(0, 0, 0, 0.38), 0 0 24px rgba(0, 229, 255, 0.14);
  pointer-events: none;
}

.od-flow-tooltip strong {
  display: block;
  margin-bottom: 7px;
  color: #ffffff;
  font-size: 13px;
  line-height: 1.45;
}

.od-flow-tooltip span {
  display: block;
  color: rgba(221, 245, 255, 0.88);
  font-size: 12px;
  line-height: 1.55;
}
</style>
