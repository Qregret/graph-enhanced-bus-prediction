<template>
  <div class="page-grid--overview">
    <section class="hero-card overview-hero">
      <div class="hero-copy">
        <span class="hero-kicker">LIVE OPS</span>
        <h3>综合监控工作台</h3>
        <p>从线路客流、运力分布、异常预警到天气约束，统一呈现当日全网态势。</p>
      </div>
      <div class="metric-grid">
        <article class="metric-card"><span>今日总客流</span><strong>{{ formatNumber(metrics.totalPassengers) }}</strong></article>
        <article class="metric-card"><span>晚高峰窗口</span><strong>{{ metrics.peakWindow || '-' }}</strong></article>
        <article class="metric-card"><span>在线车辆</span><strong>{{ formatVehicles }}</strong></article>
        <article class="metric-card"><span>拥堵站点</span><strong>{{ metrics.congestedStations ?? 0 }}</strong></article>
      </div>
    </section>

    <section class="panel-card panel-card--large overview-trend">
      <div class="panel-head"><h4>客流趋势监控</h4><span>预测客流 / 实际客流</span></div>
      <div ref="trendChartRef" class="trend-mock trend-chart"></div>
      <div class="trend-legend">
        <span><i class="legend-dot legend-dot--cyan"></i>预测客流</span>
        <span><i class="legend-dot legend-dot--blue"></i>实际客流</span>
      </div>
    </section>

    <section class="panel-card overview-rank">
      <div class="panel-head"><h4>全网最拥堵线路排名</h4><span>Top 5</span></div>
      <div class="rank-list rank-list--values">
        <div v-for="item in topCongestedRoutes" :key="`${item.lineId}-${item.direction}`">
          <span>{{ item.name }} {{ item.direction }}</span>
          <b :style="{ width: `${Math.max(24, Math.min(100, item.congestion * 48))}%` }"></b>
          <strong>{{ item.congestion }}</strong>
        </div>
      </div>
      <div class="rank-summary">
        <article>
          <span>最高指数</span>
          <strong>{{ congestionSummary.max }}</strong>
        </article>
        <article>
          <span>预警线路</span>
          <strong>{{ congestionSummary.warningRoutes }}</strong>
        </article>
        <article>
          <span>重点方向</span>
          <strong>{{ congestionSummary.directions }}</strong>
        </article>
      </div>
    </section>

    <section class="panel-card panel-card--feed overview-feed">
      <div class="panel-head"><h4>全网运行动态</h4><span>Live Operation Feed</span></div>
      <el-scrollbar class="live-feed-scrollbar">
        <div class="live-feed">
          <article
            v-for="(item, index) in liveFeed.slice(0, 6)"
            :key="`${item.time}-${item.summary}-${index}`"
            class="live-feed__item"
            :class="`is-${item.type || 'system'}`"
            :style="{ animationDelay: `${index * 70}ms` }"
          >
            <span class="live-feed__bar"></span>
            <div class="live-feed__body">
              <div class="live-feed__row">
                <div class="live-feed__title">
                  <component :is="resolveFeedIcon(item.type)" class="live-feed__icon" />
                  <strong>{{ item.summary || '-' }}</strong>
                </div>
                <time>{{ formatFeedTime(item.time) }}</time>
              </div>
              <div class="live-feed__subrow">
                <p>{{ item.target || '-' }}</p>
                <span class="live-feed__tag">{{ typeLabel(item.type) }}</span>
              </div>
            </div>
          </article>
        </div>
      </el-scrollbar>
      <div class="feed-insight-grid">
        <article>
          <span>在线车辆</span>
          <strong>{{ formatVehicles }}</strong>
        </article>
        <article>
          <span>拥堵站点</span>
          <strong>{{ metrics.congestedStations ?? 0 }}</strong>
        </article>
        <article>
          <span>动态事件</span>
          <strong>{{ liveFeed.length }}</strong>
        </article>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ElScrollbar } from 'element-plus'
import '../../styles/system-overview.css'
import { useSystemOverviewPage } from '../../composables/useSystemOverviewPage'

const props = defineProps({
  pageData: {
    type: Object,
    default: () => ({})
  }
})

const {
  metrics,
  liveFeed,
  topCongestedRoutes,
  congestionSummary,
  trendChartRef,
  formatVehicles,
  formatFeedTime,
  resolveFeedIcon,
  typeLabel,
  formatNumber
} = useSystemOverviewPage(props)
</script>

