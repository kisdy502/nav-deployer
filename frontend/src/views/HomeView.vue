<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  FolderOpened,
  FullScreen,
  Odometer,
  ScaleToOriginal,
  Share,
} from '@element-plus/icons-vue'
import MapCanvas from '@/components/editor/MapCanvas.vue'
import RobotStatusBar from '@/components/editor/RobotStatusBar.vue'
import MappingPanel from '@/components/editor/MappingPanel.vue'
import MapsManageDialog from '@/components/editor/MapsManageDialog.vue'
import BrowsePanel from '@/components/browse/BrowsePanel.vue'
import { useEditorStore } from '@/stores/editor'
import { useMapListStore } from '@/stores/mapList'
import { useRobotStore } from '@/stores/robot'
import { useTasksStore } from '@/stores/tasks'
import { startMapping } from '@/api/tasks'
import type { PickResult } from '@/three/MapScene'
import { onBeforeUnmount } from 'vue'

const router = useRouter()
const editor = useEditorStore()
const mapStore = useMapListStore()
const robot = useRobotStore()
const tasks = useTasksStore()

const mapCanvasRef = ref<InstanceType<typeof MapCanvas>>()
const mappingVisible = ref(false)
const manageVisible = ref(false)
const guide = reactive({ visible: false, starting: false })
/** 用户手动切换过地图后，不再自动跟随机器人地图 */
const manualSelected = ref(false)

// 地图下拉：机器人当前地图排第一，其次部署中
const sortedMaps = computed(() => {
  const name = robot.status?.map_name
  const score = (m: { map_name: string; robot_map_name: string | null; status: string }) => {
    if (name && (name === m.map_name || name === m.robot_map_name)) return 0
    return m.status === 'ACTIVE' ? 1 : 2
  }
  return [...mapStore.maps].sort((a, b) => score(a) - score(b))
})

const currentMapId = computed({
  get: () => editor.mapId || undefined,
  set: (v: number | undefined) => {
    if (v) selectMap(v)
  },
})

/** 打开页面第一步：加载机器人默认地图（机器人当前 > 部署中 > 最新） */
async function pickDefaultMap() {
  const maps = mapStore.maps
  if (maps.length === 0) {
    guide.visible = true
    return
  }
  const name = robot.status?.map_name
  const target =
    (name ? maps.find((m) => m.map_name === name || m.robot_map_name === name) : null) ??
    maps.find((m) => m.status === 'ACTIVE') ??
    maps[0]
  if (!target) return
  if (editor.mapId !== target.id || !editor.grid) {
    await editor.loadMap(target.id)
  }
}

function selectMap(id: number) {
  manualSelected.value = true
  void editor.loadMap(id)
}

// 机器人地图变化时（未手动选择过）自动跟随
watch(
  () => robot.status?.map_name,
  (name) => {
    if (!name || manualSelected.value || guide.visible) return
    const m = mapStore.maps.find((x) => x.map_name === name || x.robot_map_name === name)
    if (m && m.id !== editor.mapId) void editor.loadMap(m.id)
  },
)

onMounted(async () => {
  await mapStore.load()
  tasks.refresh()
  await pickDefaultMap()
})

function editMap() {
  if (!editor.mapId) {
    ElMessage.warning('请先选择一张地图')
    return
  }
  router.push({ name: 'editor', params: { id: String(editor.mapId) } })
}

function toggleMeasure() {
  editor.setMode(editor.mode === 'measure' ? 'idle' : 'measure')
}

async function startMappingNow() {
  guide.starting = true
  try {
    await startMapping()
    guide.visible = false
    mappingVisible.value = true
    ElMessage.success('已进入建图模式，操作机器人建图完成后，在右侧面板点击「保存并入库」')
  } catch {
    /* http 层已提示 */
  } finally {
    guide.starting = false
  }
}

// ---------- 浏览态右键菜单：导航/定位/循线 ----------
interface CtxItem {
  label: string
  danger?: boolean
  action: () => void
}
const ctx = reactive({ visible: false, x: 0, y: 0, items: [] as CtxItem[] })

function closeCtx() {
  ctx.visible = false
  window.removeEventListener('click', closeCtx)
}

function navToPoint(p: { id: number; x: number; y: number }) {
  if (!robot.status?.pose_initialized) return ElMessage.warning('机器人位姿未初始化，无法导航')
  if (tasks.moveTaskInFlight) return ElMessage.warning('已有执行中的移动任务，请先取消')
  void tasks.navigateToPoint(p.id)
}

function followPathById(id: number) {
  const path = editor.paths.find((x) => x.id === id)
  if (!path) return
  if (path.status !== 'DEPLOYED') return ElMessage.warning('只有已部署（DEPLOYED）的路线才能循线')
  if (!robot.status?.pose_initialized) return ElMessage.warning('机器人位姿未初始化，无法循线')
  if (tasks.moveTaskInFlight) return ElMessage.warning('已有执行中的移动任务，请先取消')
  void tasks.followPath(path.id)
}

function openCtx(payload: { hit: PickResult | null; x: number; y: number; world: { x: number; y: number } }) {
  const hit = payload.hit
  const items: CtxItem[] = []
  if (hit?.type === 'point' && hit.id != null) {
    const p = editor.pointsById.get(hit.id)
    if (p) {
      editor.selectPoint(p.id)
      items.push(
        { label: '导航到此', action: () => navToPoint(p) },
        { label: '定位到此', action: () => mapCanvasRef.value?.flyToPoint(p.x, p.y) },
      )
    }
  } else if (hit?.type === 'path' && hit.id != null) {
    editor.selectPath(hit.id)
    void editor.ensurePathDetail(hit.id)
    items.push({ label: '循线', action: () => followPathById(hit!.id!) })
  } else {
    items.push({ label: '适应视图', action: () => mapCanvasRef.value?.fitView() })
  }
  if (items.length === 0) return
  ctx.items = items
  ctx.x = payload.x
  ctx.y = payload.y
  ctx.visible = true
  window.addEventListener('click', closeCtx, { once: true })
}

onBeforeUnmount(closeCtx)
</script>

<template>
  <div class="browse">
    <header class="browse-header">
      <div class="header-title">
        <el-icon :size="20"><MapLocation /></el-icon>
        <span>Nav Deployer 部署控制台</span>
      </div>

      <div class="header-map">
        <el-select
          v-model="currentMapId"
          placeholder="选择地图"
          size="default"
          style="width: 240px"
        >
          <el-option
            v-for="m in sortedMaps"
            :key="m.id"
            :value="m.id"
            :label="m.map_name"
          >
            <div class="map-option">
              <span>{{ m.map_name }}</span>
              <el-tag
                v-if="robot.status?.map_name && (robot.status.map_name === m.map_name || robot.status.map_name === m.robot_map_name)"
                size="small"
                type="success"
                effect="light"
              >机器人当前</el-tag>
              <el-tag v-else-if="m.status === 'ACTIVE'" size="small" type="primary" effect="light">部署中</el-tag>
            </div>
          </el-option>
        </el-select>
        <el-tag v-if="editor.mapInfo" size="small" :type="editor.mapInfo.status === 'ACTIVE' ? 'success' : 'info'">
          {{ editor.mapInfo.status === 'ACTIVE' ? '部署中' : editor.mapInfo.status }}
        </el-tag>
      </div>

      <div class="header-actions">
        <el-button type="primary" :icon="Share" @click="editMap">编辑地图</el-button>
        <el-button :icon="Odometer" @click="mappingVisible = true">建图</el-button>
        <el-button :icon="FolderOpened" @click="manageVisible = true">地图管理</el-button>
      </div>
    </header>

    <div class="browse-body">
      <!-- 左侧编辑菜单栏 -->
      <aside class="rail">
        <el-tooltip content="编辑地图" placement="right">
          <button class="rail-btn" :class="{ active: false }" @click="editMap">
            <el-icon :size="18"><Share /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip content="建图 / 保存地图" placement="right">
          <button class="rail-btn" @click="mappingVisible = true">
            <el-icon :size="18"><Odometer /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip content="地图管理" placement="right">
          <button class="rail-btn" @click="manageVisible = true">
            <el-icon :size="18"><FolderOpened /></el-icon>
          </button>
        </el-tooltip>
        <div class="rail-divider"></div>
        <el-tooltip content="测量距离" placement="right">
          <button class="rail-btn" :class="{ active: editor.mode === 'measure' }" @click="toggleMeasure">
            <el-icon :size="18"><ScaleToOriginal /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip content="适应视图" placement="right">
          <button class="rail-btn" @click="mapCanvasRef?.fitView()">
            <el-icon :size="18"><FullScreen /></el-icon>
          </button>
        </el-tooltip>
      </aside>

      <main class="browse-main">
        <MapCanvas ref="mapCanvasRef" :editable="false" @context="openCtx" />
      </main>

      <aside class="browse-right">
        <BrowsePanel @locate-point="(xy) => mapCanvasRef?.flyToPoint(xy.x, xy.y)" @locate-bounds="(pts) => mapCanvasRef?.flyToBounds(pts)" />
      </aside>
    </div>

    <footer class="browse-footer">
      <RobotStatusBar />
    </footer>

    <MappingPanel v-if="mappingVisible" @close="mappingVisible = false" />
    <MapsManageDialog v-model="manageVisible" @browse="(id: number) => selectMap(id)" />

    <!-- 浏览态右键菜单 -->
    <div v-if="ctx.visible" class="ctx-menu" :style="{ left: ctx.x + 'px', top: ctx.y + 'px' }">
      <div
        v-for="(item, i) in ctx.items"
        :key="i"
        class="ctx-item"
        :class="{ danger: item.danger }"
        @click="item.action(); closeCtx()"
      >
        {{ item.label }}
      </div>
    </div>

    <!-- 空地图建图引导 -->
    <el-dialog v-model="guide.visible" title="欢迎使用 Nav Deployer" width="460px" :close-on-click-modal="false">
      <div class="guide-body">
        <el-empty description="当前还没有任何地图" :image-size="80" />
        <div class="guide-steps">
          <div>1. 点击「开始建图」，机器人进入在线建图模式；</div>
          <div>2. 遥控机器人走遍作业区域，地图实时预览；</div>
          <div>3. 建图完成后在「建图」面板点击「保存并入库」；</div>
          <div>4. 入库成功后即可在本页浏览、标注点位与路线。</div>
        </div>
      </div>
      <template #footer>
        <el-button @click="guide.visible = false">稍后</el-button>
        <el-button type="primary" :loading="guide.starting" @click="startMappingNow">开始建图</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.browse {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.browse-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 16px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 16px;
  flex-shrink: 0;
}

.header-map {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.map-option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.browse-body {
  flex: 1;
  min-height: 0;
  display: flex;
}

.rail {
  width: 48px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 10px 0;
  gap: 6px;
  z-index: 20;
}

.rail-btn {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #606266;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.rail-btn:hover {
  background: #f5f7fa;
  color: #409eff;
}

.rail-btn.active {
  background: #ecf5ff;
  color: #409eff;
}

.rail-divider {
  width: 24px;
  height: 1px;
  background: #e4e7ed;
  margin: 4px 0;
}

.browse-main {
  flex: 1;
  min-width: 0;
  position: relative;
}

.browse-right {
  width: 300px;
  background: #fff;
  border-left: 1px solid #e4e7ed;
}

.browse-footer {
  height: 36px;
}

.ctx-menu {
  position: fixed;
  z-index: 3000;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  padding: 4px;
  min-width: 130px;
}

.ctx-item {
  padding: 7px 12px;
  font-size: 13px;
  border-radius: 4px;
  cursor: pointer;
}

.ctx-item:hover {
  background: #f5f7fa;
}

.ctx-item.danger {
  color: #f56c6c;
}

.guide-body {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.guide-steps {
  align-self: stretch;
  display: grid;
  gap: 6px;
  font-size: 13px;
  color: #606266;
  background: #f5f7fa;
  border-radius: 8px;
  padding: 12px 16px;
  margin-top: 8px;
}
</style>
