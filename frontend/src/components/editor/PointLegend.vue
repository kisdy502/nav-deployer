<script setup lang="ts">
import { computed, ref } from 'vue'
import { POINT_STYLES, POINT_UNIFIED_COLOR, outlineSvgPoints } from '@/utils/pointStyle'

/** 点位类型图例 + 地图比例尺：每次进入页面一律折叠（不占画布）；会话内可点击展开/收起，不记忆状态。 */
const expanded = ref(false)

/** 当前每米对应的屏幕像素数（由画布视野变化驱动，缩放/适配/resize 时更新） */
const props = defineProps<{ pxPerMeter?: number }>()

/** 当前生效网格档位（与 MapScene 网格 LOD 同步：≥10px/m 细格 1m；≥1.5px/m 粗格 10m） */
const gridStepMeters = computed(() => {
  const pxPerMeter = props.pxPerMeter ?? 0
  if (pxPerMeter >= 10) return 1
  if (pxPerMeter >= 1.5) return 10
  return 10
})

/** 比例尺 = 一格的真实长度：线段像素宽度量化到 4px 步进，避免缩放过程中逐像素抖动 */
const scaleBar = computed(() => {
  const pxPerMeter = props.pxPerMeter ?? 0
  if (pxPerMeter <= 0) return null
  const rawPixels = gridStepMeters.value * pxPerMeter
  const pixels = Math.max(8, Math.round(rawPixels / 4) * 4)
  return { pixels, label: `1 格 = ${gridStepMeters.value} m` }
})

function toggle() {
  expanded.value = !expanded.value
}
</script>

<template>
  <div class="point-legend" aria-label="点位类型图例">
    <template v-if="expanded">
      <span v-for="(style, type) in POINT_STYLES" :key="type" class="legend-item">
        <svg viewBox="-1 -1 2 2" aria-hidden="true">
          <circle r="1" :fill="POINT_UNIFIED_COLOR" />
          <polygon points="1,0 0,-1 0,1" fill="var(--el-text-color-secondary)" />
          <polygon
            v-if="style.outline.length > 0"
            :points="outlineSvgPoints(style)"
            :fill="style.color"
            fill-rule="evenodd"
          />
        </svg>
        {{ style.label }}
      </span>
      <span class="direction">➤ 朝向</span>
      <button class="legend-toggle" title="收起图例" @click="toggle">收起 ▾</button>
    </template>
    <button v-else class="legend-toggle" title="展开点位类型图例" @click="toggle">▸ 点位图例</button>
  </div>

  <!-- 比例尺：图例正下方、左对齐；线段长度 = 一格网格的真实像素换算（CSS 过渡平滑缩放跳变） -->
  <div v-if="scaleBar" class="scale-bar" aria-label="地图比例尺">
    <div class="bar" :style="{ width: scaleBar.pixels + 'px' }" />
    <span class="scale-label">{{ scaleBar.label }}</span>
  </div>
</template>

<style scoped>
.point-legend { position: absolute; bottom: 44px; left: 10px; z-index: 10; display: flex; flex-wrap: wrap; gap: 12px; max-width: calc(100% - 40px); padding: 7px 10px; background: var(--el-bg-color-overlay); border: 1px solid var(--el-border-color); border-radius: 6px; color: var(--el-text-color-primary); font-size: 12px; pointer-events: auto; }
.legend-item { display: inline-flex; align-items: center; gap: 5px; }
svg { width: 26px; height: 26px; }
.direction { align-self: center; color: var(--el-text-color-primary); }
.legend-toggle { border: none; background: transparent; color: var(--el-color-primary); font-size: 12px; cursor: pointer; padding: 0 2px; white-space: nowrap; }
.legend-toggle:hover { text-decoration: underline; }

.scale-bar { position: absolute; bottom: 10px; left: 10px; z-index: 10; display: flex; align-items: flex-end; gap: 8px; padding: 4px 10px; background: var(--el-bg-color-overlay); border: 1px solid var(--el-border-color); border-radius: 6px; color: var(--el-text-color-primary); font-size: 12px; pointer-events: none; }
/* ┌─┐ 形比例尺：三边 2px 整数 border（两端竖线+底线），宽度随缩放换算并以过渡平滑 */
.bar { height: 8px; border-left: 2px solid var(--el-text-color-primary); border-right: 2px solid var(--el-text-color-primary); border-bottom: 2px solid var(--el-text-color-primary); transition: width .15s ease-out; }
.scale-label { font-weight: 600; white-space: nowrap; line-height: 1; padding-bottom: 1px; }
</style>
