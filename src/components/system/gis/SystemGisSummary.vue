<template>
  <section class="gis-summary panel-card">
    <div class="gis-summary__head">
      <div>
        <p class="gis-kicker">GIS 管理</p>
        <h2>公交基础台账与线路 GIS 管理</h2>
        <p class="gis-desc">
          统一查看线路资产、站点空间位置、空间编码结果与运营预警，帮助运营、调度和 GIS 运维协同判断。
        </p>
      </div>
      <div class="gis-summary__date">
        <div class="gis-date-chip">
          <span>业务日期</span>
          <strong>{{ selectedDate || '2024-04-07' }}</strong>
        </div>
      </div>
    </div>
    <div class="gis-summary__metrics">
      <article v-for="metric in overviewMetrics" :key="metric.label" class="gis-metric">
        <span>{{ metric.label }}</span>
        <strong>{{ metric.value }}</strong>
        <small>{{ metric.note }}</small>
      </article>
    </div>
  </section>
</template>

<script setup>
defineProps({
  overviewMetrics: { type: Array, required: true },
  selectedDate: { type: String, default: '' }
})
</script>
