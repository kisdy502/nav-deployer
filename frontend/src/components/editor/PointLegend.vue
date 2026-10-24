<script setup lang="ts">
import { ref } from 'vue'
import { POINT_STYLES } from '@/utils/pointStyle'

/** 点位类型图例：每次进入页面一律折叠（不占画布）；会话内可点击展开/收起，不记忆状态。 */
const expanded = ref(false)

function toggle() {
  expanded.value = !expanded.value
}
</script>

<template>
  <div class="point-legend" aria-label="点位类型图例">
    <template v-if="expanded">
      <span v-for="(style, type) in POINT_STYLES" :key="type" class="legend-item">
        <svg viewBox="-1 -1 2 2" aria-hidden="true">
          <circle r="1" :fill="style.color" />
          <polygon points="1,0 0,-1 0,1" fill="#52667a" />
          <polygon
            v-if="style.outline.length > 0"
            :points="style.outline.map(([x, y]) => (x * 0.5 - 0.5) + ',' + -y * 0.5).join(' ')"
            fill="#52667a"
          />
        </svg>
        {{ style.label }}
      </span>
      <span class="direction">➤ 朝向</span>
      <button class="legend-toggle" title="收起图例" @click="toggle">收起 ▾</button>
    </template>
    <button v-else class="legend-toggle" title="展开点位类型图例" @click="toggle">▸ 点位图例</button>
  </div>
</template>

<style scoped>
.point-legend { position: absolute; bottom: 32px; left: 10px; z-index: 10; display: flex; flex-wrap: wrap; gap: 12px; max-width: calc(100% - 40px); padding: 7px 10px; background: rgba(255,255,255,.94); border: 1px solid #dcdfe6; border-radius: 6px; color: #303133; font-size: 12px; pointer-events: auto; }
.legend-item { display: inline-flex; align-items: center; gap: 5px; }
svg { width: 26px; height: 26px; }
.direction { align-self: center; color: #172b4d; }
.legend-toggle { border: none; background: transparent; color: #409eff; font-size: 12px; cursor: pointer; padding: 0 2px; white-space: nowrap; }
.legend-toggle:hover { text-decoration: underline; }
</style>
