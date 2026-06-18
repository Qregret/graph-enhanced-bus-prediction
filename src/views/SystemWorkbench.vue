<template>
  <div class="workbench">
    <aside class="wb-sidebar">
      <div class="wb-brand">
        <h1>基于图增强模型的公交下车站点预测系统</h1>
        <p>Transit Alighting Prediction</p>
      </div>

      <nav class="wb-nav">
        <button
          v-for="item in navItems"
          :key="item.key"
          type="button"
          class="wb-nav__item"
          :class="{ 'is-active': item.key === activeNav }"
          @click="activeNav = item.key"
        >
          <span class="wb-nav__icon">{{ item.icon }}</span>
          <span>{{ item.label }}</span>
        </button>
      </nav>

      <div class="wb-user">
        <div class="wb-user__avatar">TA</div>
        <div>
          <strong>Transit System Admin</strong>
          <p>Level 4 Access</p>
        </div>
      </div>
    </aside>

    <main class="wb-main">
      <header class="wb-topbar">
        <div class="wb-topbar__title">
          <h2>智慧公交态势监测与预测指挥舱</h2>
          <p>基于隐马尔可夫模型（HMM）构建的城市公交客流智能预测系统</p>
        </div>
        <div class="wb-topbar__meta">
          <div class="wb-pill">数据引擎已连接</div>
          <div class="wb-search">全链路监测工作台</div>
        </div>
      </header>

      <section class="wb-content">
        <section class="wb-hero">
          <div class="wb-hero__copy">
            <span class="wb-kicker">CORE SYSTEM</span>
            <h3>智慧公交态势监测与预测指挥舱</h3>
            <p>
              基于隐马尔可夫模型（HMM）构建的城市公交客流智能预测系统，提供全路网监控、
              OD 流向分析与拥堵预警的沉浸式可视化体验。
            </p>

            <div class="wb-stats">
              <article class="wb-stat wb-stat--wide">
                <span>系统当前接入站点数</span>
                <strong>23,019</strong>
              </article>
              <article class="wb-stat">
                <span>今日预测总客流</span>
                <strong>100,529</strong>
              </article>
              <article class="wb-stat">
                <span>算法实时置信度</span>
                <strong>98.4%</strong>
              </article>
            </div>

            <button type="button" class="wb-launch" @click="openDashboard">
              <span class="wb-launch__icon">✦</span>
              <span>在新窗口打开全屏大屏</span>
            </button>
          </div>

          <div class="wb-hero__visual">
            <div class="wb-status-card">
              <h4>实时运行状态</h4>
              <div class="wb-status-row">
                <span>数据库连接</span>
                <strong class="is-online">正常</strong>
              </div>
              <div class="wb-status-row">
                <span>HMM 模型加载</span>
                <strong>14.2%</strong>
              </div>
              <div class="wb-status-row">
                <span>地理编码解析</span>
                <strong>正常</strong>
              </div>
            </div>

            <div class="wb-notes-card">
              <h4>最近更新</h4>
              <ul>
                <li>优化了区域 OD 流向算法</li>
                <li>新增 2,400+ 实时传感器节点</li>
              </ul>
            </div>

            <div class="wb-display">
              <div class="wb-display__chrome">
                <span></span>
                <span></span>
                <span></span>
              </div>
              <div class="wb-display__screen">
                <div class="wb-display__grid"></div>
                <div class="wb-display__beam"></div>
                <div class="wb-display__hud"></div>
              </div>
            </div>
          </div>
        </section>
      </section>
    </main>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const activeNav = ref('guide')

const navItems = [
  { key: 'overview', label: '综合监控', icon: '◫' },
  { key: 'forecast', label: '客流预测', icon: '↗' },
  { key: 'dispatch', label: '智能调度', icon: '◔' },
  { key: 'evaluate', label: '算法评估', icon: '▣' },
  { key: 'gis', label: 'GIS 管理', icon: '⌖' },
  { key: 'guide', label: '大屏引导', icon: '✦' }
]

function openDashboard() {
  const url = `${window.location.origin}${window.location.pathname}?screen=dashboard`
  window.open(url, '_blank', 'noopener,noreferrer')
}
</script>

<style scoped>
.workbench {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  width: 100%;
  height: 100%;
  background:
    radial-gradient(circle at top left, rgba(32, 197, 255, 0.08), transparent 24%),
    linear-gradient(180deg, #f5f8fc 0%, #eef3f8 100%);
  color: #1d2733;
}

.wb-sidebar {
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding: 28px 16px 16px;
  background: rgba(247, 250, 253, 0.92);
  border-right: 1px solid rgba(18, 85, 106, 0.08);
  box-shadow: inset -1px 0 0 rgba(255, 255, 255, 0.6);
}

.wb-brand h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 800;
  color: #0b819d;
}

.wb-brand p {
  margin: 4px 0 0;
  color: #7b8794;
  font-size: 12px;
}

.wb-nav {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
}

.wb-nav__item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 14px;
  border: 0;
  border-radius: 14px;
  background: transparent;
  color: #6f7d88;
  text-align: left;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.22s ease;
}

.wb-nav__item:hover {
  background: rgba(11, 129, 157, 0.08);
  color: #245769;
}

.wb-nav__item.is-active {
  background: #ffffff;
  color: #0b819d;
  box-shadow: 0 12px 24px rgba(20, 35, 52, 0.08);
}

.wb-nav__icon {
  width: 18px;
  text-align: center;
}

.wb-user {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border-radius: 14px;
  background: rgba(210, 220, 230, 0.35);
}

.wb-user__avatar {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background: linear-gradient(135deg, #0a758f, #12accd);
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 800;
}

.wb-user strong {
  display: block;
  font-size: 13px;
}

.wb-user p {
  margin: 3px 0 0;
  font-size: 11px;
  color: #7d8791;
}

.wb-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 20px 22px 22px 0;
}

.wb-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-left: 18px;
  padding: 18px 22px;
  border-radius: 22px 22px 0 0;
  background: rgba(255, 255, 255, 0.68);
  backdrop-filter: blur(20px);
}

.wb-topbar__title h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 800;
}

.wb-topbar__title p {
  margin: 6px 0 0;
  color: #748391;
  font-size: 13px;
}

.wb-topbar__meta {
  display: flex;
  align-items: center;
  gap: 12px;
}

.wb-pill,
.wb-search {
  padding: 10px 14px;
  border-radius: 999px;
  background: rgba(233, 239, 245, 0.9);
  color: #4a5a67;
  font-size: 13px;
  font-weight: 700;
}

.wb-content {
  min-height: 0;
  flex: 1;
  margin-left: 18px;
  padding: 18px;
  border-radius: 0 0 24px 24px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.9), rgba(244, 248, 252, 0.92));
  box-shadow: 0 24px 48px rgba(18, 35, 52, 0.08);
}

.wb-hero {
  display: grid;
  grid-template-columns: minmax(360px, 0.92fr) minmax(320px, 0.78fr);
  gap: 28px;
  height: 100%;
  align-items: stretch;
}

.wb-hero__copy {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 32px;
  border-radius: 28px;
  background: rgba(255, 255, 255, 0.86);
  box-shadow: 0 24px 56px rgba(14, 35, 52, 0.07);
}

.wb-kicker {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: #1086a4;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: 1.6px;
}

.wb-kicker::before {
  content: '';
  width: 32px;
  height: 2px;
  background: linear-gradient(90deg, #12accd, transparent);
}

.wb-hero__copy h3 {
  margin: 0;
  font-size: clamp(36px, 3vw, 58px);
  line-height: 1.02;
  font-weight: 900;
  letter-spacing: -0.04em;
}

.wb-hero__copy p {
  margin: 0;
  max-width: 520px;
  color: #5a6773;
  font-size: 15px;
  line-height: 1.9;
}

.wb-stats {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.wb-stat {
  padding: 18px 18px 16px;
  border-radius: 18px;
  background: #f3f6fa;
}

.wb-stat--wide {
  grid-column: 1 / -1;
}

.wb-stat span {
  display: block;
  color: #7d8791;
  font-size: 12px;
  margin-bottom: 8px;
}

.wb-stat strong {
  font-size: 18px;
  color: #263340;
}

.wb-launch {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: auto;
  height: 72px;
  border: 0;
  border-radius: 18px;
  background: linear-gradient(135deg, #0f8ead 0%, #22c6df 100%);
  color: #fff;
  font-size: 20px;
  font-weight: 800;
  box-shadow: 0 22px 40px rgba(25, 174, 204, 0.28);
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.wb-launch:hover {
  transform: translateY(-2px);
  box-shadow: 0 26px 48px rgba(25, 174, 204, 0.36);
}

.wb-launch__icon {
  font-size: 22px;
}

.wb-hero__visual {
  position: relative;
  min-height: 0;
  border-radius: 30px;
  background: linear-gradient(180deg, rgba(250, 252, 255, 0.95), rgba(242, 247, 251, 0.92));
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.65);
  overflow: hidden;
}

.wb-status-card,
.wb-notes-card {
  position: absolute;
  right: 22px;
  width: 220px;
  border-radius: 22px;
  padding: 18px 18px 16px;
  background: rgba(255, 255, 255, 0.88);
  box-shadow: 0 18px 38px rgba(21, 40, 58, 0.08);
}

.wb-status-card {
  top: 22px;
}

.wb-notes-card {
  top: 178px;
}

.wb-status-card h4,
.wb-notes-card h4 {
  margin: 0 0 14px;
  font-size: 14px;
  font-weight: 800;
}

.wb-status-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  border-top: 1px solid rgba(11, 81, 101, 0.08);
  font-size: 13px;
}

.wb-status-row:first-of-type {
  border-top: 0;
  padding-top: 0;
}

.wb-status-row strong.is-online {
  color: #11b39f;
}

.wb-notes-card ul {
  margin: 0;
  padding-left: 18px;
  color: #5d6975;
  font-size: 13px;
  line-height: 1.8;
}

.wb-display {
  position: absolute;
  left: 50%;
  bottom: 42px;
  transform: translateX(-18%);
  width: min(420px, 52%);
  aspect-ratio: 1.48;
  padding: 12px;
  border-radius: 22px;
  background: linear-gradient(180deg, #1e2840 0%, #0d1321 100%);
  box-shadow:
    0 30px 60px rgba(14, 25, 42, 0.25),
    inset 0 0 0 1px rgba(255, 255, 255, 0.06);
}

.wb-display__chrome {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 10px;
}

.wb-display__chrome span {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.wb-display__chrome span:nth-child(1) { background: #ff6257; }
.wb-display__chrome span:nth-child(2) { background: #ffbf44; }
.wb-display__chrome span:nth-child(3) { background: #27c93f; }

.wb-display__screen {
  position: relative;
  height: calc(100% - 24px);
  border-radius: 16px;
  overflow: hidden;
  background:
    radial-gradient(circle at center, rgba(39, 227, 255, 0.2), transparent 36%),
    linear-gradient(180deg, #071423 0%, #07111e 100%);
}

.wb-display__grid {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(rgba(70, 219, 255, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(70, 219, 255, 0.08) 1px, transparent 1px);
  background-size: 24px 24px;
  opacity: 0.45;
}

.wb-display__beam {
  position: absolute;
  left: -12%;
  top: 50%;
  width: 124%;
  height: 14px;
  transform: rotate(-7deg);
  background: linear-gradient(90deg, transparent, rgba(101, 255, 252, 0.92), transparent);
  box-shadow: 0 0 24px rgba(77, 235, 255, 0.85);
}

.wb-display__hud {
  position: absolute;
  inset: 16px;
  border: 1px solid rgba(106, 244, 255, 0.26);
  border-radius: 14px;
}

@media (max-width: 1280px) {
  .workbench {
    grid-template-columns: 208px minmax(0, 1fr);
  }

  .wb-hero {
    grid-template-columns: 1fr;
  }

  .wb-display {
    position: relative;
    left: auto;
    bottom: auto;
    transform: none;
    width: min(440px, 100%);
    margin: 220px auto 28px;
  }
}
</style>
