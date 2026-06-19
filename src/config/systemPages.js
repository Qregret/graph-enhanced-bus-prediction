import { defineAsyncComponent } from 'vue'

const SystemOverviewPage = defineAsyncComponent(() => import('../views/system/SystemOverviewPage.vue'))
const SystemForecastPage = defineAsyncComponent(() => import('../views/system/SystemForecastPage.vue'))
const SystemDispatchPage = defineAsyncComponent(() => import('../views/system/SystemDispatchPage.vue'))
const SystemAnalyticsPage = defineAsyncComponent(() => import('../views/system/SystemAnalyticsPage.vue'))
const SystemGisPage = defineAsyncComponent(() => import('../views/system/SystemGisPage.vue'))
const SystemLaunchpadPage = defineAsyncComponent(() => import('../views/system/SystemLaunchpadPage.vue'))

export const systemPageRegistry = {
  overview: {
    title: '综合监控大盘',
    description: '围绕实时运营状态、路网告警和重点指标的统一工作台。',
    component: SystemOverviewPage
  },
  forecast: {
    title: '公交客流OD预测与调度研判',
    description: '基于刷卡流水、车辆GPS、站点拓扑与本地Qwen3模型，对未来客流转移趋势进行预测。',
    component: SystemForecastPage
  },
  dispatch: {
    title: '智能调度辅助方案',
    description: '供需调配、运力修正与线路发车策略的一体化调度面板。',
    component: SystemDispatchPage
  },
  analytics: {
    title: '历史数据与算法评估',
    description: '对预测误差、推理时延与运行状态进行简洁评估。',
    component: SystemAnalyticsPage
  },
  gis: {
    title: '公交基础台账与线路 GIS 管理',
    description: '统一查看线路资产、站点空间位置、空间编码结果与运营预警。',
    component: SystemGisPage
  },
  launchpad: {
    title: '基于图增强模型的公交下车站点预测系统',
    description: '面向全屏大屏可视化的统一入口页，可从这里快速切入监测驾驶舱。',
    component: SystemLaunchpadPage
  }
}

export function getSystemPageFromLocation() {
  const params = new URLSearchParams(window.location.search)
  const page = params.get('page')
  return page && systemPageRegistry[page] ? page : 'launchpad'
}
