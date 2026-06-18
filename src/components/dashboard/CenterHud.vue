<template>
  <section class="center-hud glass-panel">
    <div class="center-hud__item">
      <span>当前线路</span>
      <strong>{{ lineLabel || '--' }}</strong>
    </div>
    <div class="center-hud__item">
      <span>方向</span>
      <strong>{{ currentDirection }}</strong>
    </div>
    <div class="center-hud__item">
      <span>选中站点</span>
      <strong>{{ stationName || '--' }}</strong>
    </div>
    <div class="center-hud__item">
      <span>线路站点数</span>
      <strong>{{ stationCount }}</strong>
    </div>
  </section>
</template>

<script setup>
defineProps({
  lineLabel: {
    type: String,
    default: ''
  },
  currentDirection: {
    type: String,
    default: '上行'
  },
  stationName: {
    type: String,
    default: ''
  },
  stationCount: {
    type: Number,
    default: 0
  }
})
</script>
