<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ArrowRight, Box, Connection, Crop, Location, Odometer, Operation, Position, Share, Switch, VideoPlay } from '@element-plus/icons-vue'
import EditorToolbar2 from '@/components/editor2/EditorToolbar2.vue'
import MapCanvas2 from '@/components/editor2/MapCanvas2.vue'
import PropertyPanel2 from '@/components/editor2/PropertyPanel2.vue'
import MappingPanel2 from '@/components/editor2/MappingPanel2.vue'
import MapsV2ManageDialog from '@/components/editor2/MapsV2ManageDialog.vue'
import TeleopPanel from '@/components/editor/TeleopPanel.vue'
import RobotStatusBar from '@/components/editor/RobotStatusBar.vue'
import { useMapEditor2Store } from '@/stores/mapEditor2'
import { useRobotStore } from '@/stores/robot'
import { WAYPOINT_TYPE_LABELS, ZONE_TYPE_LABELS, CONN_TYPE_LABELS, type ConnType } from '@/types/map2'
import { pointStyle } from '@/utils/pointStyle'

/** v2 多楼层地图编辑器：布局对齐 v1 EditorView（工具栏/左列表/画布/右属性/状态栏/浮动按钮），并整合主页地图功能（建图/遥控/重定位/测量）。 */
const props = defineProps<{ id: string }>()

const route = useRoute()
const router = useRouter()
const store = useMapEditor2Store()
const robot = useRobotStore()

const mapCanvasRef = ref<InstanceType<typeof MapCanvas2>>()
const mappingVisible = ref(false)
const teleopVisible = ref(false)
const manageVisible = ref(false)
const loadingLocal = ref(false)
const activeTab = ref('waypoints')
/** 左侧列表是否展开（图标导航栏常驻，点击图标展开/收起对应列表） */
const leftExpanded = ref(true)
/** 右侧属性面板是否显示（画布右缘手柄折叠） */
const rightPanelVisible = ref(true)

/** 左侧竖排图标导航：点位/路线/区域/货架/连接 */
const leftTabs = [
  { id: 'waypoints', label: '点位', icon: Location },
  { id: 'paths', label: '路线', icon: Share },
  { id: 'zones', label: '区域', icon: Crop },
  { id: 'shelves', label: '货架', icon: Box },
  { id: 'conns', label: '连接', icon: Connection },
] as const

function leftTabCount(id: string): number {
  if (id === 'waypoints') return store.waypoints.length
  if (id === 'paths') return store.paths.length
  if (id === 'zones') return store.zones.length
  if (id === 'shelves') return store.shelves.length
  return store.connections.length
}

/** 点击图标：始终展开并切到该类（收起只走列表头部「«」按钮，行为确定不跳变） */
function onRailTab(id: string) {
  activeTab.value = id
  leftExpanded.value = true
}

const mapId = computed(() => Number(props.id ?? route.params.id))

// 机器人处于建图模式时自动打开建图面板（刷新页面后恢复建图上下文，与 v1 一致）
watch(
  () => robot.status?.mode,
  (mode) => {
    if (mode === 'MAPPING') mappingVisible.value = true
  },
  { immediate: true },
)

// ---------- 楼层弹窗 ----------
const floorDialog = reactive({
  visible: false,
  editingId: null as number | null,
  form: { floor_code: '', name: '', elevation: 0, default_max_speed: 1.0, sort_order: 0 },
})

function openFloorCreate() {
  floorDialog.editingId = null
  const seq = store.floors.length + 1
  floorDialog.form = {
    floor_code: `F${seq}`,
    name: `Floor ${seq}`,
    elevation: (seq - 1) * 3.5,
    default_max_speed: 1.0,
    sort_order: seq - 1,
  }
  floorDialog.visible = true
}

function openFloorEdit() {
  const floor = store.currentFloor
  if (!floor) return
  floorDialog.editingId = floor.id
  floorDialog.form = {
    floor_code: floor.floor_code,
    name: floor.name,
    elevation: floor.elevation,
    default_max_speed: floor.default_max_speed,
    sort_order: floor.sort_order,
  }
  floorDialog.visible = true
}

async function saveFloor() {
  if (floorDialog.editingId) {
    if (!floorDialog.form.name.trim()) return ElMessage.warning('楼层名称必填')
    await store.saveFloor(
      {
        name: floorDialog.form.name,
        elevation: floorDialog.form.elevation,
        default_max_speed: floorDialog.form.default_max_speed,
        sort_order: floorDialog.form.sort_order,
        modified_by: store.modifiedBy,
      },
      floorDialog.editingId,
    )
  } else {
    if (!floorDialog.form.floor_code.trim()) return ElMessage.warning('楼层标识必填')
    await store.saveFloor({
      map_id: mapId.value,
      floor_code: floorDialog.form.floor_code,
      name: floorDialog.form.name,
      elevation: floorDialog.form.elevation,
      default_max_speed: floorDialog.form.default_max_speed,
      sort_order: floorDialog.form.sort_order,
      modified_by: store.modifiedBy,
    })
  }
  floorDialog.visible = false
}

async function removeFloor() {
  const floor = store.currentFloor
  if (!floor) return
  if (store.floors.length <= 1) return ElMessage.warning('至少保留一个楼层')
  await ElMessageBox.confirm(`删除楼层 ${floor.floor_code}？其区域/货架将一并删除（有点位/路线时会被拒绝）`, '删除楼层', { type: 'warning' })
  await store.removeFloor(floor.id)
}

// ---------- 楼层间连接新建 ----------
const connDialog = reactive({
  visible: false,
  form: {
    name: '',
    conn_type: 'elevator' as ConnType,
    capacity: null as number | null,
    max_speed: 0.5 as number | null,
    connections: [
      { floor_id: '', waypoint_id: '' },
      { floor_id: '', waypoint_id: '' },
    ],
  },
})

function openConnCreate() {
  connDialog.form = {
    name: '',
    conn_type: 'elevator',
    capacity: null,
    max_speed: 0.5,
    connections: [
      { floor_id: store.floors[0]?.floor.floor_code ?? '', waypoint_id: '' },
      { floor_id: store.floors[1]?.floor.floor_code ?? '', waypoint_id: '' },
    ],
  }
  connDialog.visible = true
}

async function saveConn() {
  const endpoints = connDialog.form.connections.filter((e) => e.floor_id && e.waypoint_id)
  if (endpoints.length < 2) return ElMessage.warning('至少选择 2 个端点')
  await store.addConn({
    name: connDialog.form.name || connDialog.form.conn_type,
    conn_type: connDialog.form.conn_type,
    connections: endpoints,
    capacity: connDialog.form.capacity,
    max_speed: connDialog.form.max_speed,
  })
  connDialog.visible = false
}

function floorWaypoints(floorCode: string) {
  return store.floors.find((b) => b.floor.floor_code === floorCode)?.waypoints ?? []
}

function selectListItem(kind: 'waypoint' | 'zone' | 'shelf' | 'path' | 'conn', id: number) {
  store.setTool('select')
  store.select(kind, id)
}

async function deployPath(id: number) {
  await store.setPathStatus(id, 'DEPLOYED')
  ElMessage.success('路线已部署')
}

async function disablePath(id: number) {
  await store.setPathStatus(id, 'DISABLED')
  ElMessage.success('路线已停用')
}

function onMapCreated(id: number) {
  mappingVisible.value = false
  if (id !== mapId.value) {
    void router.push({ name: 'map2-editor', params: { id: String(id) } })
  }
}

function onLocatePoint(xy: { x: number; y: number }) {
  mapCanvasRef.value?.flyToPoint(xy.x, xy.y)
}

function onLocateBounds(pts: { x: number; y: number }[]) {
  mapCanvasRef.value?.flyToBounds(pts)
}

// ---------- 生命周期 ----------
onMounted(async () => {
  const id = mapId.value
  if (!Number.isFinite(id) || id <= 0) {
    router.replace('/')
    return
  }
  loadingLocal.value = true
  try {
    await store.loadMap(id)
  } catch {
    router.replace('/')
    return
  } finally {
    loadingLocal.value = false
  }
})

onBeforeUnmount(() => {
  store.stopLivePreview()
  // 退出编辑：复位工具与草稿，避免绘制工具/草稿状态带回主页浏览
  store.setTool('select')
  store.cancelDraft()
})
</script>

<template>
  <div class="editor" v-loading="loadingLocal">
    <el-header height="50px" class="editor-header">
      <EditorToolbar2
        @open-floor-create="openFloorCreate"
        @open-floor-edit="openFloorEdit"
        @remove-floor="removeFloor"
        @open-manage="manageVisible = true"
      />
    </el-header>

    <el-container class="editor-body">
      <!-- 左侧：竖排图标导航（常驻窄栏，点击展开对应列表，再点收起） -->
      <aside class="editor-left" :class="{ 'rail-only': !leftExpanded }">
        <div class="left-rail">
          <button
            v-for="t in leftTabs"
            :key="t.id"
            class="rail-tab"
            :class="{ on: leftExpanded && activeTab === t.id }"
            :title="t.label + '（' + leftTabCount(t.id) + '）'"
            @click="onRailTab(t.id)"
          >
            <el-icon :size="17"><component :is="t.icon" /></el-icon>
            <span class="rail-label">{{ t.label }}</span>
            <span class="rail-count">{{ leftTabCount(t.id) }}</span>
          </button>
        </div>
        <div v-if="leftExpanded" class="left-list">
          <div class="left-list-head">
            <span>{{ leftTabs.find((t) => t.id === activeTab)?.label }}</span>
            <button class="left-collapse" title="收起列表（点左侧图标再次展开）" @click="leftExpanded = false">
              <el-icon :size="14"><ArrowLeft /></el-icon>
            </button>
          </div>
          <div class="list-scroll">
            <template v-if="activeTab === 'waypoints'">
              <div
                v-for="w in store.waypoints"
                :key="w.id"
                class="list-item"
                :class="{ active: store.selection?.kind === 'waypoint' && store.selection.id === w.id }"
                @click="selectListItem('waypoint', w.id)"
              >
                <span class="item-glyph" :title="WAYPOINT_TYPE_LABELS[w.wp_type] ?? w.wp_type">
                  <svg viewBox="-1 -1 2 2" aria-hidden="true">
                    <circle r="1" :fill="pointStyle(w.wp_type).color" />
                    <polygon points="1,0 0,-1 0,1" fill="#52667a" />
                    <polygon
                      v-if="pointStyle(w.wp_type).outline.length > 0"
                      :points="pointStyle(w.wp_type).outline.map(([x, y]) => (x * 0.5 - 0.5) + ',' + -y * 0.5).join(' ')"
                      fill="#52667a"
                    />
                  </svg>
                </span>
                <span class="item-title">{{ w.waypoint_code }}</span>
                <span class="item-type">{{ WAYPOINT_TYPE_LABELS[w.wp_type] ?? w.wp_type }}</span>
              </div>
              <el-empty v-if="store.waypoints.length === 0" description="用「新建点位」工具在地图上放置" :image-size="40" />
            </template>

            <template v-else-if="activeTab === 'paths'">
              <div
                v-for="p in store.paths"
                :key="p.id"
                class="list-item"
                :class="{ active: store.selection?.kind === 'path' && store.selection.id === p.id }"
                @click="selectListItem('path', p.id)"
              >
                <span class="item-title">{{ p.path_code }}</span>
                <el-tag
                  size="small"
                  effect="plain"
                  :type="p.status === 'DEPLOYED' ? 'success' : p.status === 'DISABLED' ? 'info' : 'warning'"
                >
                  {{ p.status === 'DEPLOYED' ? '已部署' : p.status === 'DISABLED' ? '已停用' : '草稿' }}
                </el-tag>
                <el-tooltip v-if="p.status !== 'DEPLOYED'" content="部署" placement="top">
                  <el-button size="small" text circle type="success" :icon="VideoPlay" @click.stop="deployPath(p.id)" />
                </el-tooltip>
                <el-tooltip v-else content="停用" placement="top">
                  <el-button size="small" text circle type="info" :icon="Switch" @click.stop="disablePath(p.id)" />
                </el-tooltip>
              </div>
              <el-empty v-if="store.paths.length === 0" description="用「画路线」工具依次点击点位" :image-size="40" />
            </template>

            <template v-else-if="activeTab === 'zones'">
              <div
                v-for="z in store.zones"
                :key="z.id"
                class="list-item"
                :class="{ active: store.selection?.kind === 'zone' && store.selection.id === z.id }"
                @click="selectListItem('zone', z.id)"
              >
                <span class="item-title">{{ z.name }}</span>
                <el-tag size="small" effect="plain">{{ ZONE_TYPE_LABELS[z.zone_type] ?? z.zone_type }}</el-tag>
              </div>
              <el-empty v-if="store.zones.length === 0" description="用「画区域」工具拖拽矩形或圈多边形" :image-size="40" />
            </template>

            <template v-else-if="activeTab === 'shelves'">
              <div
                v-for="s in store.shelves"
                :key="s.id"
                class="list-item"
                :class="{ active: store.selection?.kind === 'shelf' && store.selection.id === s.id }"
                @click="selectListItem('shelf', s.id)"
              >
                <span class="item-title">{{ s.name }}</span>
                <el-tag size="small" effect="plain">{{ s.layers?.length ?? 0 }}层</el-tag>
              </div>
              <el-empty v-if="store.shelves.length === 0" description="用「画货架」工具拖拽矩形或圈多边形" :image-size="40" />
            </template>

            <template v-else-if="activeTab === 'conns'">
              <div
                v-for="c in store.connections"
                :key="c.id"
                class="list-item"
                :class="{ active: store.selection?.kind === 'conn' && store.selection.id === c.id }"
                @click="selectListItem('conn', c.id)"
              >
                <span class="item-title">{{ c.name }}</span>
                <el-tag size="small" effect="plain">{{ CONN_TYPE_LABELS[c.conn_type] ?? c.conn_type }}</el-tag>
              </div>
              <div class="list-item add" @click="openConnCreate">+ 新建楼层间连接</div>
              <el-empty v-if="store.connections.length === 0" description="电梯/楼梯/坡道跨楼层通道" :image-size="40" />
            </template>
          </div>
        </div>
      </aside>

      <el-main class="editor-main">
        <MapCanvas2 ref="mapCanvasRef" />
        <!-- 右侧属性面板折叠手柄：贴画布右缘中部 -->
        <button
          class="right-panel-handle"
          :title="rightPanelVisible ? '收起属性面板' : '展开属性面板'"
          @click="rightPanelVisible = !rightPanelVisible"
        >
          <el-icon :size="14">
            <ArrowRight v-if="rightPanelVisible" />
            <ArrowLeft v-else />
          </el-icon>
          <span v-if="!rightPanelVisible" class="handle-text">属性</span>
        </button>
      </el-main>

      <el-aside v-if="rightPanelVisible" width="300px" class="editor-right">
        <PropertyPanel2 @locate-point="onLocatePoint" @locate-bounds="onLocateBounds" />
      </el-aside>
    </el-container>

    <el-footer height="36px" class="editor-footer">
      <RobotStatusBar />
    </el-footer>

    <!-- 楼层新建/编辑 -->
    <el-dialog v-model="floorDialog.visible" :title="floorDialog.editingId ? '楼层设置' : '新建楼层'" width="420px">
      <el-form label-width="90px">
        <el-form-item label="楼层标识">
          <el-input v-model="floorDialog.form.floor_code" :disabled="!!floorDialog.editingId" placeholder="F1 / F2" />
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="floorDialog.form.name" /></el-form-item>
        <el-form-item label="基准高度 m"><el-input-number v-model="floorDialog.form.elevation" :step="0.1" :controls="false" /></el-form-item>
        <el-form-item label="默认限速 m/s"><el-input-number v-model="floorDialog.form.default_max_speed" :min="0.05" :step="0.1" :controls="false" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="floorDialog.form.sort_order" :min="0" :controls="false" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="floorDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveFloor">保存</el-button>
      </template>
    </el-dialog>

    <!-- 楼层间连接新建 -->
    <el-dialog v-model="connDialog.visible" title="新建楼层间连接" width="520px">
      <el-form label-width="80px">
        <el-form-item label="名称"><el-input v-model="connDialog.form.name" placeholder="如：1号电梯" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="connDialog.form.conn_type">
            <el-option v-for="(label, value) in CONN_TYPE_LABELS" :key="value" :value="value" :label="label" />
          </el-select>
        </el-form-item>
        <el-form-item label="载重 kg"><el-input-number v-model="connDialog.form.capacity" :controls="false" placeholder="不限" /></el-form-item>
        <el-form-item label="限速 m/s"><el-input-number v-model="connDialog.form.max_speed" :step="0.1" :controls="false" /></el-form-item>
        <el-form-item label="端点">
          <div class="conn-endpoints">
            <div v-for="(endpoint, i) in connDialog.form.connections" :key="i" class="cp-row">
              <el-select v-model="endpoint.floor_id" style="width: 100px" placeholder="楼层">
                <el-option v-for="bundle in store.floors" :key="bundle.floor.id" :value="bundle.floor.floor_code" :label="bundle.floor.floor_code" />
              </el-select>
              <el-select v-model="endpoint.waypoint_id" style="width: 180px" filterable placeholder="点位">
                <el-option v-for="w in floorWaypoints(endpoint.floor_id)" :key="w.id" :value="w.waypoint_code" :label="w.waypoint_code" />
              </el-select>
              <el-button text type="danger" @click="connDialog.form.connections.splice(i, 1)">×</el-button>
            </div>
            <el-button size="small" text type="primary" @click="connDialog.form.connections.push({ floor_id: '', waypoint_id: '' })">+ 端点</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="connDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveConn">创建</el-button>
      </template>
    </el-dialog>

    <!-- 建图抽屉 / 遥控器 / 地图管理 -->
    <MappingPanel2 v-if="mappingVisible" @close="mappingVisible = false" @map-created="onMapCreated" @open-teleop="teleopVisible = true" />
    <TeleopPanel v-if="teleopVisible" @close="teleopVisible = false" />
    <MapsV2ManageDialog v-model="manageVisible" />

    <!-- 右下浮动按钮（与 v1 编辑器一致；右面板收起时贴画布右缘） -->
    <div class="float-btns" :style="{ right: rightPanelVisible ? '312px' : '12px' }">
      <el-button circle :icon="Position" title="适应视图" @click="mapCanvasRef?.fitView()" />
      <el-button circle :icon="Odometer" title="建图" @click="mappingVisible = !mappingVisible" />
      <el-button circle :icon="Operation" title="全向遥控器" @click="teleopVisible = true" />
    </div>
  </div>
</template>

<style scoped>
.editor {
  height: 100%;
  display: flex;
  flex-direction: column;
  position: relative;
}

.editor-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  padding: 0;
}

.editor-body {
  flex: 1;
  min-height: 0;
}

/* 左侧：图标导航栏（常驻 54px）+ 可收起列表；面板贴左缘 */
.editor-left {
  width: 262px;
  flex-shrink: 0;
  display: flex;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  overflow: hidden;
  transition: width 0.18s ease;
}

.editor-left.rail-only {
  width: 54px;
}

.left-rail {
  width: 54px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 8px 0;
  border-right: 1px solid #f0f2f5;
}

.rail-tab {
  width: 44px;
  padding: 7px 0 5px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  border: none;
  background: transparent;
  border-radius: 8px;
  color: #606266;
  cursor: pointer;
  font: inherit;
}

.rail-tab:hover {
  color: #409eff;
  background: #f5f7fa;
}

.rail-tab.on {
  color: #409eff;
  background: #ecf5ff;
}

.rail-label {
  font-size: 11px;
  line-height: 1.2;
}

.rail-count {
  font-size: 10px;
  color: #c0c4cc;
  line-height: 1;
}

.rail-tab.on .rail-count {
  color: #409eff;
}

.left-list {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.left-list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 8px 6px 12px;
  font-size: 12.5px;
  font-weight: 600;
  color: #909399;
  border-bottom: 1px solid #f0f2f5;
}

.left-collapse {
  border: none;
  background: transparent;
  color: #909399;
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 4px;
  display: flex;
}

.left-collapse:hover {
  color: #409eff;
  background: #ecf5ff;
}

.list-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.list-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  cursor: pointer;
  font-size: 12.5px;
  color: #303133;
}

.list-item:hover {
  background: #f5f7fa;
}

.list-item.active {
  background: #ecf5ff;
  color: #409eff;
}

.list-item.add {
  color: #409eff;
  justify-content: center;
}

.item-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 点位行：类型小图标（与画布同款迷你版） */
.item-glyph {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  display: inline-flex;
}

.item-glyph svg {
  width: 100%;
  height: 100%;
}

/* 类型文字：无边框纯文本徽标（替代 el-tag 的按钮感） */
.item-type {
  flex-shrink: 0;
  font-size: 11px;
  line-height: 1;
  padding: 3px 0;
  color: #909399;
  border-radius: 3px;
}

/* 编码副标签：名称与编码不同时才显示 */
.item-code {
  flex-shrink: 0;
  font-size: 10px;
  color: #c0c4cc;
  font-family: monospace;
}

.list-item.active .item-type {
  color: #409eff;
}

.editor-main {
  padding: 0;
  position: relative;
  overflow: hidden;
}

/* 右侧属性面板折叠手柄：贴画布右缘中部（面板展开=收起箭头，收起=展开箭头+文字） */
.right-panel-handle {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  z-index: 30;
  height: 64px;
  min-width: 20px;
  padding: 0 3px;
  border: 1px solid #dcdfe6;
  border-right: none;
  border-radius: 6px 0 0 6px;
  background: rgba(255, 255, 255, 0.92);
  color: #606266;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
}

.right-panel-handle:hover {
  color: #409eff;
  border-color: #409eff;
}

.right-panel-handle .handle-text {
  writing-mode: vertical-rl;
  font-size: 11px;
  letter-spacing: 2px;
  line-height: 1;
}

.editor-right {
  background: #fff;
  border-left: 1px solid #e4e7ed;
}

.editor-footer {
  padding: 0;
}

.float-btns {
  position: absolute;
  bottom: 56px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 20;
  transition: right 0.2s;
}

.float-btns .el-button + .el-button {
  margin-left: 0;
}

.cp-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 4px 0;
}

.conn-endpoints {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
