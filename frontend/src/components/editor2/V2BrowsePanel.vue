<script setup lang="ts">
import { useMapEditor2Store } from '@/stores/mapEditor2'
import { WAYPOINT_TYPE_LABELS, ZONE_TYPE_LABELS, CONN_TYPE_LABELS } from '@/types/map2'

/** 主页右侧浏览面板（v2 地图）：楼层切换 + 点位/路线/区域/货架/连接列表，点击定位。 */
const store = useMapEditor2Store()

const emit = defineEmits<{
  (e: 'locate-point', xy: { x: number; y: number }): void
  (e: 'locate-bounds', pts: { x: number; y: number }[]): void
}>()

function selectAndLocate(kind: 'waypoint' | 'zone' | 'shelf' | 'path' | 'conn', id: number) {
  store.select(kind, id)
  if (kind === 'waypoint') {
    const w = store.waypointById.get(id)
    if (w) emit('locate-point', { x: w.x, y: w.y })
  } else if (kind === 'zone' || kind === 'shelf') {
    const entity = kind === 'zone'
      ? store.zones.find((z) => z.id === id)
      : store.shelves.find((s) => s.id === id)
    const pts = (entity?.vertices ?? []).map((v) => ({ x: v[0], y: v[1] }))
    if (pts.length === 1) emit('locate-point', pts[0])
    else if (pts.length > 1) emit('locate-bounds', pts)
  } else if (kind === 'path') {
    const path = store.paths.find((p) => p.id === id)
    const pts: { x: number; y: number }[] = []
    for (const segment of path?.segments ?? []) {
      const source = store.waypointById.get(segment.source_waypoint_id)
      const target = store.waypointById.get(segment.target_waypoint_id)
      if (source) pts.push({ x: source.x, y: source.y })
      if (target) pts.push({ x: target.x, y: target.y })
    }
    if (pts.length > 1) emit('locate-bounds', pts)
  }
}
</script>

<template>
  <div class="v2-browse">
    <div class="floor-row">
      <span class="floor-label">楼层</span>
      <el-select
        :model-value="store.currentFloorId"
        size="small"
        style="flex: 1"
        @update:model-value="(v: number) => store.selectFloor(v)"
      >
        <el-option
          v-for="bundle in store.floors"
          :key="bundle.floor.id"
          :value="bundle.floor.id"
          :label="`${bundle.floor.floor_code} · ${bundle.floor.name}`"
        />
      </el-select>
    </div>

    <div class="section">
      <div class="section-title">点位（{{ store.waypoints.length }}）</div>
      <div
        v-for="w in store.waypoints"
        :key="w.id"
        class="row"
        :class="{ active: store.selection?.kind === 'waypoint' && store.selection.id === w.id }"
        @click="selectAndLocate('waypoint', w.id)"
      >
        <span class="row-title">{{ w.waypoint_code }}</span>
        <span class="row-sub">{{ WAYPOINT_TYPE_LABELS[w.wp_type] ?? w.wp_type }}</span>
      </div>
      <div v-if="store.waypoints.length === 0" class="empty">暂无点位</div>
    </div>

    <div class="section">
      <div class="section-title">路线（{{ store.paths.length }}）</div>
      <div
        v-for="p in store.paths"
        :key="p.id"
        class="row"
        :class="{ active: store.selection?.kind === 'path' && store.selection.id === p.id }"
        @click="selectAndLocate('path', p.id)"
      >
        <span class="row-title">{{ p.path_code }}</span>
        <span class="row-sub">{{ p.segments?.length ?? 0 }} 段</span>
      </div>
      <div v-if="store.paths.length === 0" class="empty">暂无路线</div>
    </div>

    <div class="section">
      <div class="section-title">区域（{{ store.zones.length }}）</div>
      <div
        v-for="z in store.zones"
        :key="z.id"
        class="row"
        :class="{ active: store.selection?.kind === 'zone' && store.selection.id === z.id }"
        @click="selectAndLocate('zone', z.id)"
      >
        <span class="row-title">{{ z.name }}</span>
        <span class="row-sub">{{ ZONE_TYPE_LABELS[z.zone_type] ?? z.zone_type }}</span>
      </div>
      <div v-if="store.zones.length === 0" class="empty">暂无区域</div>
    </div>

    <div class="section">
      <div class="section-title">货架（{{ store.shelves.length }}）</div>
      <div
        v-for="s in store.shelves"
        :key="s.id"
        class="row"
        :class="{ active: store.selection?.kind === 'shelf' && store.selection.id === s.id }"
        @click="selectAndLocate('shelf', s.id)"
      >
        <span class="row-title">{{ s.name }}</span>
        <span class="row-sub">{{ s.layers?.length ?? 0 }} 层</span>
      </div>
      <div v-if="store.shelves.length === 0" class="empty">暂无货架</div>
    </div>

    <div class="section">
      <div class="section-title">楼层间连接（{{ store.connections.length }}）</div>
      <div
        v-for="c in store.connections"
        :key="c.id"
        class="row"
        :class="{ active: store.selection?.kind === 'conn' && store.selection.id === c.id }"
        @click="selectAndLocate('conn', c.id)"
      >
        <span class="row-title">{{ c.name }}</span>
        <span class="row-sub">{{ CONN_TYPE_LABELS[c.conn_type] ?? c.conn_type }}</span>
      </div>
      <div v-if="store.connections.length === 0" class="empty">暂无连接</div>
    </div>
  </div>
</template>

<style scoped>
.v2-browse {
  height: 100%;
  overflow-y: auto;
  padding: 10px;
}

.floor-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.floor-label {
  font-size: 13px;
  color: #909399;
}

.section {
  margin-bottom: 12px;
}

.section-title {
  font-size: 12.5px;
  font-weight: 600;
  color: #909399;
  margin-bottom: 4px;
  padding: 0 4px;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 5px 8px;
  border-radius: 4px;
  font-size: 13px;
  color: #303133;
  cursor: pointer;
}

.row:hover {
  background: #f5f7fa;
}

.row.active {
  background: #ecf5ff;
  color: #409eff;
}

.row-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-sub {
  font-size: 12px;
  color: #909399;
  flex-shrink: 0;
}

.empty {
  font-size: 12px;
  color: #c0c4cc;
  padding: 4px 8px;
}
</style>
