<template>
  <header class="header">
    <div class="header__rail"></div>
    <div class="header__side header__side--left"></div>
    <div class="header__center">
      <div class="header__title-wrap">
        <h1>基于图增强模型的公交下车站点预测系统</h1>
        <div class="header__title-core"></div>
      </div>
    </div>
    <div class="header__side header__side--right">
      <span class="header__meta-label">{{ currentDate }}</span>
      <strong class="header__meta-time">{{ currentTime }}</strong>
    </div>
  </header>
</template>

<script setup>
defineProps({
  currentDate: {
    type: String,
    default: ''
  },
  currentTime: {
    type: String,
    default: ''
  }
})
</script>
