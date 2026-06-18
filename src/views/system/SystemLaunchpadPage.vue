<template>
  <section class="launchpad">
    <div class="launchpad__content">
      <div class="launchpad__hero">
        <img class="launchpad__hero-image" :src="launchpadGuideBg" alt="" draggable="false" />
        <div class="launchpad__copy launchpad__copy--overlay">
          <el-button class="launchpad__cta" size="large" @click="$emit('open-dashboard')">
            <span class="launchpad__cta-content">
              <span class="launchpad__cta-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none">
                  <path
                    d="M4.75 6.25a1.5 1.5 0 0 1 1.5-1.5h11.5a1.5 1.5 0 0 1 1.5 1.5v7.5a1.5 1.5 0 0 1-1.5 1.5H6.25a1.5 1.5 0 0 1-1.5-1.5z"
                    stroke="currentColor"
                    stroke-width="1.8"
                    stroke-linejoin="round"
                  />
                  <path
                    d="M9 18.25h6M12 15.25v3"
                    stroke="currentColor"
                    stroke-width="1.8"
                    stroke-linecap="round"
                  />
                  <path
                    d="M14.25 9.25h3.5m0 0-1.5-1.5m1.5 1.5-1.5 1.5"
                    stroke="currentColor"
                    stroke-width="1.8"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </span>
              <span class="launchpad__cta-text">启动全屏大屏</span>
            </span>
          </el-button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { ElButton } from 'element-plus'
import launchpadGuideBg from '../../assets/launchpad-guide-composite.png'
import '../../styles/system-launchpad.css'

defineEmits(['open-dashboard'])
</script>
