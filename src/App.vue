<template>
  <DashboardScreen v-if="isDashboardMode" />
  <SystemWorkspace v-else />
</template>

<script setup>
import { computed, defineAsyncComponent, watchEffect } from 'vue'

const DashboardScreen = defineAsyncComponent(() => import('./views/DashboardScreen.vue'))
const SystemWorkspace = defineAsyncComponent(() => import('./views/SystemWorkspace.vue'))

const isDashboardMode = computed(() => {
  const params = new URLSearchParams(window.location.search)
  return params.get('screen') === 'dashboard'
})

watchEffect(() => {
  document.body.dataset.appMode = isDashboardMode.value ? 'dashboard' : 'system'
})
</script>
