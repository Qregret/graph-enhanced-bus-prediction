<template>
  <section class="station-info glass-panel">
    <div class="station-info__title">{{ station.name }}</div>
    <div class="station-info__meta">{{ lineLabel || '--' }} · {{ currentDirection }} · {{ scenarioDate }}</div>
    <div class="station-info__grid">
      <div class="station-info__item">
        <span>预测上车</span>
        <strong>{{ station.predictedBoardings ?? 0 }}</strong>
      </div>
      <div class="station-info__item">
        <span>预测下车</span>
        <strong>{{ station.predictedAlightings ?? 0 }}</strong>
      </div>
    </div>
  </section>
</template>

<script setup>
defineProps({
  station: {
    type: Object,
    required: true
  },
  lineLabel: {
    type: String,
    default: ''
  },
  currentDirection: {
    type: String,
    default: '上行'
  },
  scenarioDate: {
    type: String,
    default: '--'
  }
})
</script>
