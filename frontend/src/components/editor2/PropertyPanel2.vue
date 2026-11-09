<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useMapEditor2Store } from '@/stores/mapEditor2'
import {
  CONN_TYPE_LABELS,
  WAYPOINT_TYPE_LABELS,
  ZONE_TYPE_LABELS,
  type ConnType,
  type PathSegmentDTO,
  type WaypointType,
  type ZoneType,
} from '@/types/map2'

/** 右侧属性面板：当前选中实体的表单编辑（保存 = 调对应 v2 接口）。 */
const emit = defineEmits<{
  (e: 'locate-point', xy: { x: number; y: number }): void
  (e: 'locate-bounds', pts: { x: number; y: number }[]): void
}>()

const store = useMapEditor2Store()

const kind = computed(() => store.selection?.kind ?? null)

/** 定位到当前选中实体（点位=飞到点；区域/货架/路线=飞到包围盒）。 */
function locateSelected() {
  const waypoint = store.selectedWaypoint
  if (waypoint) {
    emit('locate-point', { x: waypoint.x, y: waypoint.y })
    return
  }
  const pts: { x: number; y: number }[] = []
  for (const v of store.selectedZone?.vertices ?? []) pts.push({ x: v[0], y: v[1] })
  for (const v of store.selectedShelf?.vertices ?? []) pts.push({ x: v[0], y: v[1] })
  for (const segment of store.selectedPath?.segments ?? []) {
    const source = store.waypointById.get(segment.source_waypoint_id)
    const target = store.waypointById.get(segment.target_waypoint_id)
    if (source) pts.push({ x: source.x, y: source.y })
    if (target) pts.push({ x: target.x, y: target.y })
  }
  if (pts.length === 1) emit('locate-point', pts[0])
  else if (pts.length > 1) emit('locate-bounds', pts)
}

// ---------- 点位 ----------

const waypointForm = reactive({
  name: '',
  wp_type: 'path' as WaypointType,
  x: 0,
  y: 0,
  yaw: 0,
  remark: '',
})

// ---------- 区域 ----------

const zoneForm = reactive({
  name: '',
  zone_type: 'slowDown' as ZoneType,
  priority: 2,
  max_speed: 0.3,
  height: null as number | null,
  description: '',
  ramp: { slope: 8, direction: 'up', start_elevation: 0, end_elevation: 1.2 },
})

// ---------- 货架 ----------

const shelfForm = reactive({
  name: '',
  height: 1.8,
  layers: [] as {
    layer_index: number
    elevation: number
    access_edge: number
    slots: { slot_index: number; position: number[]; size: number[]; product_id: string | null; product_name: string | null }[]
  }[],
})

// ---------- 路线 ----------

const pathForm = reactive({
  name: '',
  is_bidirectional: true,
  max_speed: null as number | null,
  enable_obstacle_avoidance: true,
  status: 'DRAFT' as 'DRAFT' | 'DEPLOYED' | 'DISABLED',
  segments: [] as PathSegmentDTO[],
})

// ---------- 连接 ----------

const connForm = reactive({
  name: '',
  conn_type: 'elevator' as ConnType,
  connections: [] as { floor_id: string; waypoint_id: string }[],
  capacity: null as number | null,
  max_speed: null as number | null,
  ramp: { slope: null as number | null, length: null as number | null, surface: 'concrete' },
})

// 仅在"选中的实体 ID"变化时重置表单（切换选中项时填充）；
// 不监听实体数据变化——patchWaypoint 更新 store 后不能重置表单，否则未保存的编辑被覆盖
watch(
  () => [store.selection?.kind, store.selection?.id],
  () => {
    const waypoint = store.selectedWaypoint
    if (waypoint) {
      Object.assign(waypointForm, {
        name: waypoint.name,
        wp_type: waypoint.wp_type,
        x: waypoint.x,
        y: waypoint.y,
        yaw: waypoint.yaw,
        remark: waypoint.remark ?? '',
      })
    }
    const zone = store.selectedZone
    if (zone) {
      Object.assign(zoneForm, {
        name: zone.name,
        zone_type: zone.zone_type,
        priority: zone.priority,
        max_speed: zone.max_speed,
        height: zone.height,
        description: zone.description ?? '',
        ramp: {
          slope: zone.ramp_info?.slope ?? 8,
          direction: zone.ramp_info?.direction ?? 'up',
          start_elevation: zone.ramp_info?.start_elevation ?? 0,
          end_elevation: zone.ramp_info?.end_elevation ?? 1.2,
        },
      })
    }
    const shelf = store.selectedShelf
    if (shelf) {
      Object.assign(shelfForm, {
        name: shelf.name,
        height: shelf.height,
        layers: (shelf.layers ?? []).map((layer) => ({
          layer_index: layer.layer_index,
          elevation: layer.elevation,
          access_edge: layer.access_edge,
          slots: (layer.slots ?? []).map((slot) => ({
            slot_index: slot.slot_index,
            position: [...slot.position],
            size: [...slot.size],
            product_id: slot.product_id,
            product_name: slot.product_name,
          })),
        })),
      })
    }
    const conn = store.selectedConn
    if (conn) {
      Object.assign(connForm, {
        name: conn.name,
        conn_type: conn.conn_type,
        connections: conn.connections.map((endpoint) => ({ ...endpoint })),
        capacity: conn.capacity,
        max_speed: conn.max_speed,
        ramp: {
          slope: conn.ramp_info?.slope ?? null,
          length: conn.ramp_info?.length ?? null,
          surface: conn.ramp_info?.surface ?? 'concrete',
        },
      })
    }
  },
  { immediate: true },
)

// 路线表单专用：监听 selectedPath 对象引用（选中切换 / 画布拖拽保存 patchPath / reloadMap 都会替换引用）。
// 若只在选中 ID 变化时同步，画布上拖好的贝塞尔曲线会被表单里的旧快照（STRAIGHT）在点「保存」时覆盖回直线。
watch(
  () => store.selectedPath,
  (path) => {
    if (!path) return
    Object.assign(pathForm, {
      name: path.name,
      is_bidirectional: path.is_bidirectional,
      max_speed: path.max_speed,
      enable_obstacle_avoidance: path.enable_obstacle_avoidance,
      status: path.status,
      segments: (path.segments ?? []).map((segment) => ({
        source_waypoint_id: segment.source_waypoint_id,
        target_waypoint_id: segment.target_waypoint_id,
        segment_type: segment.segment_type,
        control_points: segment.control_points?.map((cp) => [...cp]) ?? [],
        max_speed: segment.max_speed,
        back_up: segment.back_up,
        reverse: segment.reverse,
      })),
    })
  },
  { immediate: true },
)

// ---------- 保存动作 ----------

async function saveWaypoint() {
  const waypoint = store.selectedWaypoint
  if (!waypoint) return
  // 前端预校验编码格式
  const code = waypointForm.name.trim()
  if (code && !/^[A-Za-z0-9_]+$/.test(code)) {
    ElMessage.warning('编码只能包含字母、数字、下划线')
    return
  }
  await store.patchWaypoint(waypoint.id, {
    name: code, // name 即编码/显示名（后端改 code 并级联更新引用）
    wp_type: waypointForm.wp_type,
    x: waypointForm.x,
    y: waypointForm.y,
    yaw: waypointForm.yaw,
    remark: waypointForm.remark || undefined,
    modified_by: store.modifiedBy,
  })
  // 改名后刷新全图（路线段/连接中的引用编码已级联更新）
  await store.reloadMap()
  ElMessage.success('点位已保存')
}

async function saveZone() {
  const zone = store.selectedZone
  if (!zone) return
  await store.patchZone(zone.id, {
    name: zoneForm.name,
    zone_type: zoneForm.zone_type,
    priority: zoneForm.priority,
    max_speed: zoneForm.zone_type === 'forbidden' ? 0 : zoneForm.max_speed,
    height: zoneForm.height,
    ramp_info: zoneForm.zone_type === 'ramp'
      ? { slope: zoneForm.ramp.slope, direction: zoneForm.ramp.direction, start_elevation: zoneForm.ramp.start_elevation, end_elevation: zoneForm.ramp.end_elevation }
      : null,
    description: zoneForm.description || null,
    modified_by: store.modifiedBy,
  })
  await store.reloadMap()
  ElMessage.success('区域已保存')
}

function addLayer() {
  shelfForm.layers.push({
    layer_index: shelfForm.layers.length,
    elevation: 0.2 + shelfForm.layers.length * 0.6,
    access_edge: 0,
    slots: [],
  })
}

function removeLayer(index: number) {
  shelfForm.layers.splice(index, 1)
  shelfForm.layers.forEach((layer, i) => (layer.layer_index = i))
}

function addSlot(layer: (typeof shelfForm.layers)[number]) {
  layer.slots.push({
    slot_index: layer.slots.length,
    position: [0, 0, 0],
    size: [0.4, 0.6, 0.3],
    product_id: null,
    product_name: null,
  })
}

async function saveShelf() {
  const shelf = store.selectedShelf
  if (!shelf) return
  await store.patchShelf(shelf.id, {
    name: shelfForm.name,
    height: shelfForm.height,
    layers: shelfForm.layers,
    modified_by: store.modifiedBy,
  })
  await store.reloadMap()
  ElMessage.success('货架已保存')
}

async function savePath() {
  const path = store.selectedPath
  if (!path) return
  // 始终保存全部控制点（渲染层根据 CP2 是否在默认 3/4 位置决定二阶/三阶）
  await store.patchPath(path.id, {
    name: pathForm.name,
    is_bidirectional: pathForm.is_bidirectional,
    max_speed: pathForm.max_speed,
    enable_obstacle_avoidance: pathForm.enable_obstacle_avoidance,
    status: pathForm.status,
    waypoint_ids: path.waypoint_ids,
    segments: pathForm.segments,
    modified_by: store.modifiedBy,
  })
  await store.reloadMap()
  ElMessage.success('路线已保存')
}

/** 部署/停用快捷操作（v1 同款），同步表单里的状态值。 */
async function deployFromPanel() {
  const path = store.selectedPath
  if (!path) return
  await store.setPathStatus(path.id, 'DEPLOYED')
  pathForm.status = 'DEPLOYED'
  ElMessage.success('路线已部署')
}

async function disableFromPanel() {
  const path = store.selectedPath
  if (!path) return
  await store.setPathStatus(path.id, 'DISABLED')
  pathForm.status = 'DISABLED'
  ElMessage.success('路线已停用')
}

function toggleSegmentType(segment: PathSegmentDTO) {
  if (segment.segment_type === 'CURVE') {
    segment.segment_type = 'STRAIGHT'
    segment.control_points = []
  } else {
    segment.segment_type = 'CURVE'
    // 切为曲线：在段的 1/4 和 3/4 位置生成两个控制点（均在直线上，视觉仍为直线）
    // 用户拖动其中一个 → 保存时只留该点（二阶贝塞尔）；两个都拖 → 保存两点（三阶贝塞尔）
    const source = store.waypointById.get(segment.source_waypoint_id)
    const target = store.waypointById.get(segment.target_waypoint_id)
    if (source && target) {
      const cp1x = source.x + (target.x - source.x) * 0.25
      const cp1y = source.y + (target.y - source.y) * 0.25
      const cp2x = source.x + (target.x - source.x) * 0.75
      const cp2y = source.y + (target.y - source.y) * 0.75
      segment.control_points = [
        [+cp1x.toFixed(4), +cp1y.toFixed(4)],
        [+cp2x.toFixed(4), +cp2y.toFixed(4)],
      ]
    } else {
      segment.control_points = [[0, 0], [0, 0]]
    }
  }
}

function waypointCode(id: number) {
  const w = store.waypointById.get(id)
  return (w?.name || w?.waypoint_code) ?? id
}

function addConnEndpoint() {
  connForm.connections.push({ floor_id: store.floors[0]?.floor.floor_code ?? '', waypoint_id: '' })
}

function removeConnEndpoint(index: number) {
  connForm.connections.splice(index, 1)
}

async function saveConn() {
  const conn = store.selectedConn
  if (!conn) return
  await store.patchConn(conn.id, {
    name: connForm.name,
    conn_type: connForm.conn_type,
    connections: connForm.connections,
    capacity: connForm.capacity,
    max_speed: connForm.max_speed,
    ramp_info: connForm.conn_type === 'ramp' ? connForm.ramp : null,
    modified_by: store.modifiedBy,
  })
  await store.reloadMap()
  ElMessage.success('连接已保存')
}

async function remove(kind: string, id: number) {
  if (kind === 'waypoint') await store.removeWaypoint(id)
  else if (kind === 'zone') await store.removeZone(id)
  else if (kind === 'shelf') await store.removeShelf(id)
  else if (kind === 'path') await store.removePath(id)
  else if (kind === 'conn') await store.removeConn(id)
}

function floorWaypoints(floorCode: string) {
  const bundle = store.floors.find((b) => b.floor.floor_code === floorCode)
  return bundle?.waypoints ?? []
}
</script>

<template>
  <div class="prop-panel">
    <template v-if="kind === 'waypoint' && store.selectedWaypoint">
      <div class="panel-head">
        <el-button size="small" text type="primary" class="locate-btn" @click="locateSelected">定位</el-button>
      </div>
      <el-form label-width="72px" size="small">
        <el-form-item label="编码/名称">
          <el-input v-model="waypointForm.name" maxlength="64" placeholder="字母/数字/下划线，图内唯一" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="waypointForm.wp_type">
            <el-option v-for="(label, value) in WAYPOINT_TYPE_LABELS" :key="value" :value="value" :label="label" />
          </el-select>
        </el-form-item>
        <el-form-item label="X (m)"><el-input-number v-model="waypointForm.x" :step="0.1" :controls="false" /></el-form-item>
        <el-form-item label="Y (m)"><el-input-number v-model="waypointForm.y" :step="0.1" :controls="false" /></el-form-item>
        <el-form-item label="朝向 (rad)"><el-input-number v-model="waypointForm.yaw" :step="0.1" :controls="false" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="waypointForm.remark" /></el-form-item>
      </el-form>
      <div class="prop-actions">
        <el-button type="primary" size="small" @click="saveWaypoint">保存</el-button>
        <el-button type="danger" size="small" @click="remove('waypoint', store.selectedWaypoint!.id)">删除</el-button>
      </div>
    </template>

    <template v-else-if="kind === 'zone' && store.selectedZone">
      <div class="panel-head">
        <h4>区域 · {{ store.selectedZone.zone_code }}</h4>
        <el-button size="small" text type="primary" class="locate-btn" @click="locateSelected">定位</el-button>
      </div>
      <el-form label-width="72px" size="small">
        <el-form-item label="名称"><el-input v-model="zoneForm.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="zoneForm.zone_type">
            <el-option v-for="(label, value) in ZONE_TYPE_LABELS" :key="value" :value="value" :label="label" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级"><el-input-number v-model="zoneForm.priority" :min="1" :max="9" :controls="false" /></el-form-item>
        <el-form-item label="限速 m/s">
          <el-input-number v-model="zoneForm.max_speed" :min="0" :max="2" :step="0.1" :controls="false" :disabled="zoneForm.zone_type === 'forbidden'" />
        </el-form-item>
        <el-form-item label="高度 (m)"><el-input-number v-model="zoneForm.height" :min="0" :step="0.1" :controls="false" /></el-form-item>
        <template v-if="zoneForm.zone_type === 'ramp'">
          <el-divider content-position="left">坡道信息</el-divider>
          <el-form-item label="坡度°"><el-input-number v-model="zoneForm.ramp.slope" :controls="false" /></el-form-item>
          <el-form-item label="方向">
            <el-select v-model="zoneForm.ramp.direction">
              <el-option value="up" label="上坡" />
              <el-option value="down" label="下坡" />
            </el-select>
          </el-form-item>
          <el-form-item label="起始高"><el-input-number v-model="zoneForm.ramp.start_elevation" :controls="false" /></el-form-item>
          <el-form-item label="终止高"><el-input-number v-model="zoneForm.ramp.end_elevation" :controls="false" /></el-form-item>
        </template>
        <el-form-item label="描述"><el-input v-model="zoneForm.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="顶点"><span>{{ store.selectedZone.vertices?.length ?? 0 }} 个 · 选中后可在画布拖拽顶点手柄调整轮廓，拖中心手柄整体移动</span></el-form-item>
      </el-form>
      <div class="prop-actions">
        <el-button type="primary" size="small" @click="saveZone">保存</el-button>
        <el-button type="danger" size="small" @click="remove('zone', store.selectedZone!.id)">删除</el-button>
      </div>
    </template>

    <template v-else-if="kind === 'shelf' && store.selectedShelf">
      <div class="panel-head">
        <h4>货架 · {{ store.selectedShelf.shelf_code }}</h4>
        <el-button size="small" text type="primary" class="locate-btn" @click="locateSelected">定位</el-button>
      </div>
      <el-form label-width="72px" size="small">
        <el-form-item label="名称"><el-input v-model="shelfForm.name" /></el-form-item>
        <el-form-item label="总高 (m)"><el-input-number v-model="shelfForm.height" :min="0" :step="0.1" :controls="false" /></el-form-item>
      </el-form>
      <el-divider content-position="left">层 / 格位</el-divider>
      <el-collapse>
        <el-collapse-item v-for="(layer, li) in shelfForm.layers" :key="li" :title="`第 ${layer.layer_index} 层（${layer.slots.length} 格位）`">
          <el-form label-width="64px" size="small" inline-message>
            <el-form-item label="高度偏移"><el-input-number v-model="layer.elevation" :step="0.1" :controls="false" /></el-form-item>
            <el-form-item label="存取边"><el-input-number v-model="layer.access_edge" :min="0" :controls="false" /></el-form-item>
          </el-form>
          <el-table :data="layer.slots" size="small" border>
            <el-table-column label="格位" width="50">
              <template #default="{ row }">{{ row.slot_index }}</template>
            </el-table-column>
            <el-table-column label="位置 x,y,z (m)">
              <template #default="{ row }">
                <div class="slot-xyz">
                  <el-input-number v-model="row.position[0]" :step="0.1" :controls="false" size="small" />
                  <el-input-number v-model="row.position[1]" :step="0.1" :controls="false" size="small" />
                  <el-input-number v-model="row.position[2]" :step="0.1" :controls="false" size="small" />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="商品编码" width="120">
              <template #default="{ row }"><el-input v-model="row.product_id" size="small" placeholder="空位留空" /></template>
            </el-table-column>
            <el-table-column label="商品名称" width="120">
              <template #default="{ row }"><el-input v-model="row.product_name" size="small" /></template>
            </el-table-column>
          </el-table>
          <div class="layer-actions">
            <el-button size="small" @click="addSlot(layer)">加格位</el-button>
            <el-button size="small" type="danger" @click="layer.slots.pop()">删末格位</el-button>
            <el-button size="small" type="danger" plain @click="removeLayer(li)">删层</el-button>
          </div>
        </el-collapse-item>
      </el-collapse>
      <div class="layer-actions">
        <el-button size="small" @click="addLayer">加层</el-button>
      </div>
      <div class="prop-actions">
        <el-button type="primary" size="small" @click="saveShelf">保存整树</el-button>
        <el-button type="danger" size="small" @click="remove('shelf', store.selectedShelf!.id)">删除货架</el-button>
      </div>
    </template>

    <template v-else-if="kind === 'path' && store.selectedPath">
      <div class="panel-head">
        <h4>路线 · {{ store.selectedPath.path_code }}</h4>
        <el-button size="small" text type="primary" class="locate-btn" @click="locateSelected">定位</el-button>
      </div>
      <el-form label-width="80px" size="small">
        <el-form-item label="名称"><el-input v-model="pathForm.name" /></el-form-item>
        <el-form-item label="双向"><el-switch v-model="pathForm.is_bidirectional" /></el-form-item>
        <el-form-item label="限速 m/s"><el-input-number v-model="pathForm.max_speed" :min="0.05" :max="2" :step="0.05" :controls="false" placeholder="楼层默认" /></el-form-item>
        <el-form-item label="动态避障"><el-switch v-model="pathForm.enable_obstacle_avoidance" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="pathForm.status">
            <el-option value="DRAFT" label="草稿" />
            <el-option value="DEPLOYED" label="已部署" />
            <el-option value="DISABLED" label="停用" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-divider content-position="left">段（{{ pathForm.segments.length }}，曲线=贝塞尔）</el-divider>
      <div v-for="(segment, si) in pathForm.segments" :key="si" class="segment-card">
        <div class="segment-head">
          <span>{{ si + 1 }}. {{ waypointCode(segment.source_waypoint_id) }} → {{ waypointCode(segment.target_waypoint_id) }}</span>
          <el-button size="small" text :type="segment.segment_type === 'CURVE' ? 'warning' : 'info'" @click="toggleSegmentType(segment)">
            {{ segment.segment_type === 'CURVE' ? '曲线' : '直线' }}
          </el-button>
        </div>
        <template v-if="segment.segment_type === 'CURVE'">
          <div v-for="(cp, ci) in segment.control_points" :key="ci" class="cp-row">
            <span class="cp-label">CP{{ ci + 1 }}</span>
            <el-input-number v-model="cp[0]" :step="0.1" :controls="false" size="small" />
            <el-input-number v-model="cp[1]" :step="0.1" :controls="false" size="small" />
            <el-button size="small" text type="danger" @click="segment.control_points!.splice(ci, 1)">×</el-button>
          </div>
          <div class="cp-hint">
            切曲线时自动在 1/4、3/4 处生成两个控制点：只拖一个 = 二阶贝塞尔，两个都拖 = 三阶贝塞尔（保存时自动识别）
          </div>
        </template>
        <div class="cp-row">
          <span class="cp-label">段限速</span>
          <el-input-number v-model="segment.max_speed" :min="0.05" :max="2" :step="0.05" :controls="false" size="small" placeholder="默认" />
          <el-checkbox v-model="segment.back_up" size="small">倒退</el-checkbox>
          <el-checkbox v-model="segment.reverse" size="small">倒车行驶</el-checkbox>
        </div>
      </div>
      <div class="prop-actions">
        <el-button
          v-if="store.selectedPath.status !== 'DEPLOYED'"
          type="success"
          size="small"
          @click="deployFromPanel"
        >部署</el-button>
        <el-button
          v-else
          type="warning"
          size="small"
          plain
          @click="disableFromPanel"
        >停用</el-button>
        <el-button type="primary" size="small" @click="savePath">保存</el-button>
        <el-button type="danger" size="small" @click="remove('path', store.selectedPath!.id)">删除路线</el-button>
      </div>
    </template>

    <template v-else-if="kind === 'conn' && store.selectedConn">
      <h4>楼层连接 · {{ store.selectedConn.conn_code }}</h4>
      <el-form label-width="72px" size="small">
        <el-form-item label="名称"><el-input v-model="connForm.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="connForm.conn_type">
            <el-option v-for="(label, value) in CONN_TYPE_LABELS" :key="value" :value="value" :label="label" />
          </el-select>
        </el-form-item>
        <el-form-item label="载重 kg"><el-input-number v-model="connForm.capacity" :controls="false" placeholder="不限" /></el-form-item>
        <el-form-item label="限速 m/s"><el-input-number v-model="connForm.max_speed" :step="0.1" :controls="false" /></el-form-item>
        <template v-if="connForm.conn_type === 'ramp'">
          <el-form-item label="坡度°"><el-input-number v-model="connForm.ramp.slope" :controls="false" /></el-form-item>
          <el-form-item label="长度 m"><el-input-number v-model="connForm.ramp.length" :controls="false" /></el-form-item>
          <el-form-item label="表面"><el-input v-model="connForm.ramp.surface" /></el-form-item>
        </template>
      </el-form>
      <el-divider content-position="left">端点（楼层 → 点位编码）</el-divider>
      <div v-for="(endpoint, i) in connForm.connections" :key="i" class="cp-row">
        <el-select v-model="endpoint.floor_id" size="small" style="width: 90px">
          <el-option v-for="bundle in store.floors" :key="bundle.floor.id" :value="bundle.floor.floor_code" :label="bundle.floor.floor_code" />
        </el-select>
        <el-select v-model="endpoint.waypoint_id" size="small" style="width: 130px" filterable>
          <el-option v-for="w in floorWaypoints(endpoint.floor_id)" :key="w.id" :value="w.waypoint_code" :label="w.waypoint_code" />
        </el-select>
        <el-button size="small" text type="danger" @click="removeConnEndpoint(i)">×</el-button>
      </div>
      <el-button size="small" text type="primary" @click="addConnEndpoint">+ 端点</el-button>
      <div class="prop-actions">
        <el-button type="primary" size="small" @click="saveConn">保存</el-button>
        <el-button type="danger" size="small" @click="remove('conn', store.selectedConn!.id)">删除连接</el-button>
      </div>
    </template>

    <el-empty v-else description="点击画布中的元素查看属性" :image-size="60" />
  </div>
</template>

<style scoped>
.prop-panel {
  padding: 10px;
  overflow-y: auto;
  height: 100%;
  background: #fff;
}

.prop-panel h4 {
  margin: 4px 0 12px;
  color: #303133;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  margin-bottom: 4px;
}

.panel-head h4 {
  margin: 4px 0 0;
  color: #303133;
}

.head-info {
  flex: 1;
  min-width: 0;
}

.head-code {
  display: block;
  font-size: 11px;
  color: #c0c4cc;
  font-family: monospace;
  margin-bottom: 8px;
}

.prop-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.segment-card {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 8px;
  margin-bottom: 8px;
  background: #fafafa;
}

.segment-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
  font-size: 13px;
  color: #303133;
}

.cp-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 4px 0;
  flex-wrap: wrap;
}

.cp-label {
  font-size: 11px;
  color: #909399;
  min-width: 32px;
}

.cp-hint {
  font-size: 11px;
  color: #c0c4cc;
  line-height: 1.5;
  padding: 2px 0;
}

.layer-actions {
  display: flex;
  gap: 8px;
  margin: 8px 0;
}

.slot-xyz {
  display: flex;
  gap: 4px;
}
</style>
