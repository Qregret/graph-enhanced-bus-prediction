<template>
  <section class="panel glass-panel" :class="panelClass">
    <div class="panel__title">{{ title }}</div>
    <slot />
  </section>
</template>

<script setup>
defineProps({
  title: {
    type: String,
    required: true
  },
  panelClass: {
    type: [String, Array, Object],
    default: ''
  }
})
</script>
