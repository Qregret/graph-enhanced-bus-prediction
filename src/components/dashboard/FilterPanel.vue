<template>
  <section class="panel glass-panel panel--top">
    <div class="panel__title">线路筛选</div>
    <div class="route-head">
      <div>
        <div class="route-head__title">{{ lineLabel || '--' }} {{ currentDirection }}</div>
        <div class="route-head__sub">{{ scenarioState }}</div>
      </div>
      <div class="route-head__date">{{ scenarioDate }}</div>
    </div>

    <div class="control-grid">
      <label class="field">
        <span>公交线路</span>
        <select v-model="lineModel">
          <option
            v-for="item in lineOptions"
            :key="item.lineId || item.line"
            :value="item.lineId || item.line"
          >
            {{ item.lineName || item.line }}
          </option>
        </select>
      </label>

      <div class="field">
        <span>方向切换</span>
        <div class="direction-switch">
          <button
            v-for="direction in directionOptions"
            :key="direction"
            type="button"
            class="direction-btn"
            :class="{ active: currentDirection === direction }"
            @click="$emit('update:currentDirection', direction)"
          >
            {{ direction }}
          </button>
        </div>
      </div>

      <label class="field field--pair">
        <span>查询日期</span>
        <select v-model="dateModel">
          <option v-for="date in availableDates" :key="date" :value="date">
            {{ date }}
          </option>
        </select>
      </label>

      <label class="field field--pair">
        <span>站点选择</span>
        <select v-model="stationModel">
          <option v-for="station in stations" :key="station.id" :value="station.name">
            {{ station.name }}
          </option>
        </select>
      </label>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  lineLabel: {
    type: String,
    default: ''
  },
  currentDirection: {
    type: String,
    default: '上行'
  },
  scenarioState: {
    type: String,
    default: ''
  },
  scenarioDate: {
    type: String,
    default: '--'
  },
  lineOptions: {
    type: Array,
    default: () => []
  },
  directionOptions: {
    type: Array,
    default: () => []
  },
  currentLine: {
    type: String,
    default: ''
  },
  selectedDashboardDate: {
    type: String,
    default: ''
  },
  availableDates: {
    type: Array,
    default: () => []
  },
  selectedStationName: {
    type: String,
    default: ''
  },
  stations: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits([
  'update:currentLine',
  'update:currentDirection',
  'update:selectedDashboardDate',
  'update:selectedStationName'
])

const lineModel = computed({
  get: () => props.currentLine,
  set: value => emit('update:currentLine', value)
})

const dateModel = computed({
  get: () => props.selectedDashboardDate,
  set: value => emit('update:selectedDashboardDate', value)
})

const stationModel = computed({
  get: () => props.selectedStationName,
  set: value => emit('update:selectedStationName', value)
})
</script>
