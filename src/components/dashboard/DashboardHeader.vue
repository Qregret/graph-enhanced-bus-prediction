<template>
  <header class="header">
    <div class="header__rail"></div>
    <div class="header__side header__side--left"></div>
    <div class="header__center">
      <div class="header__title-wrap">
        <h1>基于图增强与 RAG 的智慧公交调度系统</h1>
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
