<template>
  <div class="screen" :class="{ 'map-focus': isMapFocusMode }">
    <div ref="mapContainerRef" class="map-container"></div>
    <div class="map-vignette"></div>

    <DashboardHeader :current-date="currentDate" :current-time="currentTime" />

    <aside class="panel-stack panel-stack--left">
      <FilterPanel
        :line-label="currentLineLabel"
        :current-direction="currentDirection"
        :scenario-state="currentScenarioState"
        :scenario-date="currentScenarioDate"
        :line-options="lineOptions"
        :direction-options="directionOptions"
        :current-line="currentLine"
        :selected-dashboard-date="selectedDashboardDate"
        :available-dates="availableDates"
        :selected-station-name="selectedStationName"
        :stations="currentStations"
        @update:current-line="currentLine = $event"
        @update:current-direction="currentDirection = $event"
        @update:selected-dashboard-date="selectedDashboardDate = $event"
        @update:selected-station-name="selectedStationName = $event"
      />

      <ChartPanel title="车速与拥堵指数趋势" panel-class="panel--middle">
        <div ref="trendChartRef" class="chart"></div>
      </ChartPanel>

      <ChartPanel title="今日小时车流量" panel-class="panel--bottom">
        <div ref="hourlyChartRef" class="chart"></div>
      </ChartPanel>
    </aside>

    <CenterHud
      :line-label="currentLineLabel"
      :current-direction="currentDirection"
      :station-name="selectedStation.name"
      :station-count="stationCount"
    />

    <StationInfoCard
      v-if="selectedStation.name"
      :station="selectedStation"
      :line-label="currentLineLabel"
      :current-direction="currentDirection"
      :scenario-date="currentScenarioDate"
    />

    <aside class="panel-stack panel-stack--right">
      <ChartPanel title="OD 客流转移流向" :panel-class="['panel--top', 'panel--od-flow']">
        <OdFlowSankey />
      </ChartPanel>

      <ChartPanel title="热门站点 TOP5" panel-class="panel--middle">
        <div ref="topChartRef" class="chart"></div>
      </ChartPanel>

      <ChartPanel title="全线路预测上下车对比" panel-class="panel--bottom">
        <div ref="mirrorChartRef" class="chart"></div>
      </ChartPanel>
    </aside>

    <div v-if="loading" class="floating-status glass-panel">数据加载中...</div>
    <div v-if="errorMessage" class="floating-error glass-panel">{{ errorMessage }}</div>
  </div>
</template>

<script setup>
import DashboardHeader from '../components/dashboard/DashboardHeader.vue'
import FilterPanel from '../components/dashboard/FilterPanel.vue'
import ChartPanel from '../components/dashboard/ChartPanel.vue'
import CenterHud from '../components/dashboard/CenterHud.vue'
import OdFlowSankey from '../components/dashboard/OdFlowSankey.vue'
import StationInfoCard from '../components/dashboard/StationInfoCard.vue'
import { useDashboardScreen } from '../composables/useDashboardScreen'
import '../styles/dashboard.css'

const {
  availableDates,
  currentDate,
  currentDirection,
  currentLine,
  currentLineLabel,
  currentScenarioDate,
  currentScenarioState,
  currentStations,
  currentTime,
  directionOptions,
  errorMessage,
  hourlyChartRef,
  isMapFocusMode,
  lineOptions,
  loading,
  mapContainerRef,
  mirrorChartRef,
  selectedDashboardDate,
  selectedStation,
  selectedStationName,
  stationCount,
  topChartRef,
  trendChartRef
} = useDashboardScreen()
</script>
