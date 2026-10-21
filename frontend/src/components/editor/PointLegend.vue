<script setup lang="ts">
import { POINT_STYLES } from '@/utils/pointStyle'
import { POINT_TYPE_LABEL } from '@/utils/format'
</script>

<template>
  <div class="point-legend" aria-label="点位类型图例">
    <span v-for="(style, type) in POINT_STYLES" :key="type" class="legend-item">
      <svg viewBox="-1 -1 2 2" aria-hidden="true">
        <circle r="1" :fill="style.color" />
        <polygon points="1,0 0,-1 0,1" fill="#172b4d" />
        <polygon :points="style.outline.map(([x, y]) => (x * 0.36 - 0.48) + ',' + -y * 0.36).join(' ')" fill="#172b4d" />
      </svg>
      {{ POINT_TYPE_LABEL[type] }}
    </span>
    <span class="direction">➤ 朝向</span>
  </div>
</template>

<style scoped>
.point-legend { position: absolute; bottom: 32px; left: 10px; z-index: 10; display: flex; flex-wrap: wrap; gap: 12px; max-width: calc(100% - 40px); padding: 7px 10px; background: rgba(255,255,255,.94); border: 1px solid #dcdfe6; border-radius: 6px; color: #303133; font-size: 12px; pointer-events: none; }
.legend-item { display: inline-flex; align-items: center; gap: 5px; }
svg { width: 26px; height: 26px; }
.direction { align-self: center; color: #172b4d; }
</style>
