<template>
  <section class="gis-bottom-grid">
    <article class="gis-bottom-card">
      <header class="gis-bottom-card__head">
        <div>
          <h3>客流与运营趋势</h3>
          <p>按站段顺序观察当前线路的客流压力与拥挤变化。</p>
        </div>
        <el-tag round type="primary">{{ activeRoute.routeName }}</el-tag>
      </header>
      <div class="gis-trend-bars">
        <div v-for="point in activeRoute.trend" :key="point.label" class="gis-trend-bars__item">
          <span>{{ point.label }}</span>
          <div class="gis-trend-bars__track">
            <i :style="{ width: `${point.value}%`, background: routeGradient(point.value) }"></i>
          </div>
          <strong>{{ point.value }}</strong>
        </div>
      </div>
    </article>

    <article class="gis-bottom-card">
      <header class="gis-bottom-card__head">
        <div>
          <h3>空间编码异常</h3>
          <p>集中处理站点编码偏移、缺失与坐标落点异常。</p>
        </div>
        <el-tag round type="warning">{{ codingIssues.length }} 条</el-tag>
      </header>
      <ul class="gis-issue-list">
        <li v-for="issue in codingIssues" :key="issue.id">
          <strong>{{ issue.routeName }} · {{ issue.stationName }}</strong>
          <span>{{ issue.detail }}</span>
        </li>
      </ul>
    </article>

    <article class="gis-bottom-card">
      <header class="gis-bottom-card__head">
        <div>
          <h3>最近运营记录</h3>
          <p>同步展示线路资产、客流预警与 GIS 运维动作。</p>
        </div>
        <el-tag round type="info">今日</el-tag>
      </header>
      <ul class="gis-log-list">
        <li v-for="log in operationLogs" :key="`${log.time}-${log.title}`">
          <span>{{ log.time }}</span>
          <div>
            <strong>{{ log.title }}</strong>
            <p>{{ log.detail }}</p>
          </div>
        </li>
      </ul>
    </article>
  </section>
</template>

<script setup>
defineProps({
  activeRoute: { type: Object, required: true },
  codingIssues: { type: Array, required: true },
  operationLogs: { type: Array, required: true },
  routeGradient: { type: Function, required: true }
})
</script>
