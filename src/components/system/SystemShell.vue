<template>
  <div class="sys-shell">
    <aside class="sys-sidebar">
      <div class="sys-brand">
        <h1>基于图增强模型的公交下车站点预测系统</h1>
        <p>Transit Alighting Prediction</p>
      </div>

      <nav class="sys-nav">
        <button
          v-for="item in navItems"
          :key="item.key"
          type="button"
          class="sys-nav__item"
          :class="{ 'is-active': item.key === activePage }"
          @click="$emit('navigate', item.key)"
        >
          <span class="sys-nav__icon">{{ item.icon }}</span>
          <span>{{ item.label }}</span>
        </button>
      </nav>

      <div class="sys-user">
        <div class="sys-user__avatar">TA</div>
        <div>
          <strong>公交系统管理员</strong>
          <p>四级站内权限</p>
        </div>
      </div>
    </aside>

    <main class="sys-main">
      <header
        class="sys-topbar"
        :class="{
          'sys-topbar--launchpad': activePage === 'launchpad'
        }"
      >
        <div class="sys-topbar__title">
          <p class="sys-topbar__kicker">{{ currentNavLabel }}</p>
          <h2>{{ pageTitle }}</h2>
          <span>{{ pageDescription }}</span>
        </div>
        <div class="sys-topbar__meta">
          <label v-if="availableDates.length" class="sys-chip sys-chip--select">
            <span>业务日期</span>
            <select :value="selectedDate" @change="$emit('update:selected-date', $event.target.value)">
              <option v-for="date in availableDates" :key="date" :value="date">{{ date }}</option>
            </select>
          </label>
          <div class="sys-chip sys-chip--date">{{ currentDate }}</div>
          <div class="sys-chip sys-chip--time">{{ currentTime }}</div>
        </div>
      </header>

      <section
        class="sys-content"
        :class="{
          'sys-content--fixed': ['gis', 'overview', 'forecast', 'dispatch', 'analytics', 'launchpad'].includes(activePage),
          'sys-content--analytics': activePage === 'analytics',
          'sys-content--launchpad': activePage === 'launchpad'
        }"
      >
        <slot />
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  activePage: {
    type: String,
    required: true
  },
  pageTitle: {
    type: String,
    required: true
  },
  pageDescription: {
    type: String,
    default: ''
  },
  currentDate: {
    type: String,
    required: true
  },
  currentTime: {
    type: String,
    required: true
  },
  selectedDate: {
    type: String,
    default: ''
  },
  availableDates: {
    type: Array,
    default: () => []
  }
})

defineEmits(['navigate', 'update:selected-date'])

const navItems = [
  { key: 'overview', label: '综合监控', icon: '监' },
  { key: 'forecast', label: '客流预测', icon: '测' },
  { key: 'dispatch', label: '智能调度', icon: '调' },
  { key: 'analytics', label: '算法评估', icon: '评' },
  { key: 'gis', label: 'GIS 管理', icon: '图' },
  { key: 'launchpad', label: '大屏引导', icon: '屏' }
]

const currentNavLabel = computed(() => {
  return navItems.find((item) => item.key === props.activePage)?.label ?? '基于图增强模型的公交下车站点预测系统'
})
</script>

<style scoped>
.sys-shell {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  width: 100%;
  height: 100vh;
  background:
    radial-gradient(circle at top left, rgba(55, 192, 222, 0.12), transparent 26%),
    linear-gradient(180deg, #f4f7fb 0%, #eef3f8 100%);
  color: #18222d;
  overflow: hidden;
}

.sys-sidebar {
  display: flex;
  flex-direction: column;
  gap: 28px;
  padding: 28px 18px 18px;
  background: rgba(247, 249, 252, 0.92);
  border-right: 1px solid rgba(18, 85, 106, 0.08);
}

.sys-brand h1 {
  margin: 0;
  color: #0e839d;
  font-size: 16px;
  font-weight: 900;
  line-height: 1.28;
  letter-spacing: 0;
}

.sys-brand p {
  margin: 4px 0 0;
  color: #7d8791;
  font-size: 12px;
  font-weight: 600;
}

.sys-nav {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
}

.sys-nav__item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 74px;
  padding: 18px 18px;
  border: 0;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.54);
  color: #6c7783;
  text-align: left;
  font-size: 16px;
  font-weight: 900;
  cursor: pointer;
  transition: all 0.22s ease;
  box-shadow: 0 10px 24px rgba(31, 58, 80, 0.04);
}

.sys-nav__item:hover {
  background: rgba(255, 255, 255, 0.86);
  color: #25596c;
}

.sys-nav__item.is-active {
  background: #ffffff;
  color: #0b819d;
  box-shadow: 0 18px 32px rgba(20, 35, 52, 0.1);
}

.sys-nav__item.is-active::before {
  position: absolute;
  left: 0;
  top: 18px;
  bottom: 18px;
  width: 4px;
  border-radius: 999px;
  background: linear-gradient(180deg, #0ea5e9, #22d3ee);
  content: '';
}

.sys-nav__icon {
  width: 34px;
  height: 34px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: rgba(11, 129, 157, 0.1);
  font-size: 18px;
  font-weight: 900;
}

.sys-user {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border-radius: 16px;
  background: rgba(211, 220, 229, 0.35);
}

.sys-user__avatar {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #0a758f, #19b8d6);
  color: #fff;
  font-size: 13px;
  font-weight: 900;
}

.sys-user strong {
  display: block;
  font-size: 13px;
}

.sys-user p {
  margin: 3px 0 0;
  color: #7d8791;
  font-size: 11px;
}

.sys-main {
  --launchpad-frame-width: min(100%, calc((100vh - 138px) * 1.777));
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: calc(100vh - 30px);
  padding: 14px 16px 16px 0;
  overflow: hidden;
}

.sys-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-sizing: border-box;
  flex: 0 0 104px;
  min-height: 104px;
  gap: 16px;
  margin-left: 10px;
  padding: 16px 28px 14px;
  border-radius: 28px 28px 0 0;
  border-bottom: 1px solid rgba(197, 219, 232, 0.45);
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(18px);
}

.sys-topbar--launchpad {
  margin-left: 10px;
  margin-right: 0;
}

.sys-topbar__kicker {
  margin: 0 0 4px;
  color: #0e839d;
  font-size: 12px;
  line-height: 1.2;
  font-weight: 900;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.sys-topbar__title {
  min-width: 0;
  max-width: min(760px, 58vw);
}

.sys-topbar__title h2 {
  margin: 0;
  overflow: hidden;
  color: #14213d;
  font-size: 22px;
  line-height: 1.16;
  font-weight: 900;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sys-topbar__title span {
  display: block;
  margin-top: 5px;
  overflow: hidden;
  color: #758492;
  font-size: 13px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sys-topbar__meta {
  display: grid;
  grid-template-columns: auto 128px 132px;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
}

.sys-chip {
  padding: 10px 14px;
  border-radius: 999px;
  background: #ffffff;
  color: #234a57;
  font-size: 13px;
  font-weight: 800;
}

.sys-chip--select {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sys-chip--select span {
  color: #6f7a85;
  font-size: 12px;
  font-weight: 800;
}

.sys-chip--select select {
  border: 0;
  outline: 0;
  background: transparent;
  color: #234a57;
  font-size: 13px;
  font-weight: 900;
  cursor: pointer;
}

.sys-chip--muted {
  background: rgba(226, 234, 241, 0.92);
  color: #60707d;
}

.sys-chip--date,
.sys-chip--time {
  justify-content: center;
  text-align: center;
  white-space: nowrap;
}

.sys-chip--date {
  min-width: 128px;
}

.sys-chip--time {
  min-width: 132px;
  font-variant-numeric: tabular-nums;
}

.sys-content {
  box-sizing: border-box;
  flex: 1 1 auto;
  min-height: 0;
  margin-left: 0px;
  padding: 0 14px 14px 15px;
  border-radius: 0 0 32px 32px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.92) 0%, rgba(243, 247, 251, 0.94) 100%);
  box-shadow: 0px 28px 56px 0px rgba(18, 35, 52, 0.09);
  overflow: auto;
  overscroll-behavior: contain;
  margin-bottom: 0px;
  vertical-align: middle;
  margin-left: 10px;
  margin-right: 0;

}

.sys-content--fixed {
  overflow: hidden;
}

.sys-content--analytics {
  padding-bottom: 14px;
}

.sys-content--launchpad {
  align-self: stretch;
  margin-left: 10px;
  margin-right: 0;
  padding: 0 14px 14px 15px;
  background: linear-gradient(135deg, #ffffff 0%, #f7fafc 52%, #f3f7fb 100%);
  box-shadow: none;
}

@media (max-width: 1280px) {
  .sys-shell {
    grid-template-columns: 208px minmax(0, 1fr);
  }

  .sys-topbar {
    flex-basis: 100px;
    min-height: 100px;
    padding: 15px 22px 13px;
  }

  .sys-topbar__title h2 {
    font-size: 21px;
  }
}
</style>
