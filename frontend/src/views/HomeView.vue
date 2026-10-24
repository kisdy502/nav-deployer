<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  FullScreen,
  Aim,
  Odometer,
  OfficeBuilding,
  Operation,
  ScaleToOriginal,
  Share,
} from '@element-plus/icons-vue'
import MapCanvas from '@/components/editor/MapCanvas.vue'
import MapCanvas2 from '@/components/editor2/MapCanvas2.vue'
import V2BrowsePanel from '@/components/editor2/V2BrowsePanel.vue'
import RobotStatusBar from '@/components/editor/RobotStatusBar.vue'
import MappingPanel from '@/components/editor/MappingPanel.vue'
import MappingPanel2 from '@/components/editor2/MappingPanel2.vue'
import TeleopPanel from '@/components/editor/TeleopPanel.vue'
import MapsV2ManageDialog from '@/components/editor2/MapsV2ManageDialog.vue'
import BrowsePanel from '@/components/browse/BrowsePanel.vue'
import { useEditorStore } from '@/stores/editor'
import { useMapEditor2Store } from '@/stores/mapEditor2'
import { useMapListStore } from '@/stores/mapList'
import { useRobotStore } from '@/stores/robot'
import { useTasksStore } from '@/stores/tasks'
import { startMapping } from '@/api/tasks'
import { switchMap } from '@/api/maps'
import { listMapsV2 } from '@/api/map2'
import type { MapV2VO } from '@/types/map2'
import type { PickResult } from '@/three/MapScene'
import { onBeforeUnmount } from 'vue'

const router = useRouter()
const editor = useEditorStore()
const editor2 = useMapEditor2Store()
const mapStore = useMapListStore()
const robot = useRobotStore()
const tasks = useTasksStore()

const mapCanvasRef = ref<InstanceType<typeof MapCanvas>>()
const mapCanvas2Ref = ref<InstanceType<typeof MapCanvas2>>()

// ---------- v2 地图切换（主页浏览新版多楼层地图，有 v2 地图时默认选中） ----------
const v2Maps = ref<MapV2VO[]>([])
const v2MapId = ref<number | null>(null)
/** 用户手动清除过 v2 选择后不再自动兜底选中 */
const v2ManualCleared = ref(false)
/** 右侧地图信息面板默认折叠：画布优先，点边缘按钮展开 */
const browsePanelVisible = ref(false)

watch(v2MapId, (id, was) => {
  if (id == null && was != null) v2ManualCleared.value = true
  if (id != null) {
    editor2.loadMap(id).catch(() => {
      /* http 层已提示 */
    })
  }
})

async function loadV2Maps() {
  try {
    v2Maps.value = await listMapsV2()
    // 首次进入：存在 v2 地图时默认选中（使用中优先，其次最新），主页直接展示新版地图
    if (v2MapId.value == null && !v2ManualCleared.value && v2Maps.value.length > 0) {
      v2MapId.value = v2Maps.value.find((m) => m.status === 'PUBLISHED')?.id ?? v2Maps.value[0].id
    }
  } catch {
    /* http 层已提示 */
  }
}
const mappingVisible = ref(false)
/** v2 建图面板（v2 地图选中时建图入口走它，实时预览进 v2 画布） */
const mapping2Visible = ref(false)
/** 本次实时预览是否由建图模式自动开启（退出建图时自动关，手动开启的不动） */
const v2AutoLive = ref(false)
const teleopVisible = ref(false)
const manage2Visible = ref(false)

// v2 地图管理（导入/迁移/删除）关闭后刷新下拉列表
watch(manage2Visible, (visible, was) => {
  if (!visible && was) void loadV2Maps()
})
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

/** 打开页面第一步：加载机器人默认地图（机器人当前 > 部署中 > 最新）；v2 已默认选中时跳过 v1 兜底 */
async function pickDefaultMap() {
  const maps = mapStore.maps
  if (maps.length === 0) {
    // v2 已选中：不弹空地图引导（引导是建图流程，v2 浏览不受影响）
    if (v2MapId.value == null) guide.visible = true
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

// 建图模式联动（刷新页面后恢复建图上下文）：
// v2 地图选中 → v2 面板 + v2 画布实时预览（存档地图与点位/路线全部隐藏）；
// 注意 SSE 的 MAPPING 状态可能先于 v2 地图列表加载到达（v2MapId 尚为 null 时走了 v1 分支），
// 因此模式与 v2 选图任一变化都重新校准，避免画布停留在存档地图。
function syncMappingMode() {
  const mode = robot.status?.mode
  if (mode === 'MAPPING') {
    if (v2MapId.value != null) {
      mapping2Visible.value = true
      mappingVisible.value = false
      if (!editor2.livePreview) {
        editor2.startLivePreview()
        v2AutoLive.value = true
      }
    } else {
      mappingVisible.value = true
    }
  } else if (mode && v2AutoLive.value) {
    // 退出建图：自动开启的预览自动关（手动开启的不动）
    editor2.stopLivePreview()
    v2AutoLive.value = false
  }
}

watch(() => robot.status?.mode, syncMappingMode, { immediate: true })
watch(v2MapId, syncMappingMode)

/** 建图入口 v2 感知：v2 地图开 v2 面板（实时预览进 v2 画布），否则 v1 面板。 */
function toggleMappingPanel() {
  if (v2MapId.value != null) {
    mapping2Visible.value = !mapping2Visible.value
  } else {
    mappingVisible.value = !mappingVisible.value
  }
}

/** v2 建图保存为新地图后：主页直接切到新地图浏览。 */
function onV2MapCreated(id: number) {
  mapping2Visible.value = false
  v2MapId.value = id
}

onMounted(async () => {
  await mapStore.load()
  tasks.refresh()
  // 先加载 v2 列表（有 v2 地图则主页默认展示 v2），再决定是否需要 v1 兜底
  await loadV2Maps()
  await pickDefaultMap()
})

function editMap() {
  // 选中 v2 地图时直接进入 v2 编辑器
  if (v2MapId.value) {
    router.push({ name: 'map2-editor', params: { id: String(v2MapId.value) } })
    return
  }
  if (!editor.mapId) {
    ElMessage.warning('请先选择一张地图')
    return
  }
  router.push({ name: 'editor', params: { id: String(editor.mapId) } })
}

function toggleMeasure() {
  if (v2MapId.value) {
    editor2.setTool(editor2.tool === 'measure' ? 'select' : 'measure')
    return
  }
  editor.setMode(editor.mode === 'measure' ? 'idle' : 'measure')
}

function toggleInitialPose() {
  // v2 地图：切 v2 画布的重定位工具；v1 地图：保持原逻辑
  if (v2MapId.value) {
    editor2.setTool(editor2.tool === 'initialPose' ? 'select' : 'initialPose')
    return
  }
  editor.setMode(editor.mode === 'initialPose' ? 'idle' : 'initialPose')
}

async function startMappingNow() {
  guide.starting = true
  try {
    await startMapping()
    guide.visible = false
    locateGuide.visible = false
    toggleMappingPanel()
    ElMessage.success('已进入建图模式，遥控机器人走图，实时预览自动开启')
  } catch {
    /* http 层已提示 */
  } finally {
    guide.starting = false
  }
}

// ---------- 未定位冷启动引导 ----------
// 机器人整机重启后既无建图也无定位（pose_initialized=false），页面却仍展示上次地图，
// 误导"机器人在图中"。检测到该状态时引导：选图启动定位 / 开始建图 / 仅浏览。
const locateGuide = reactive({
  visible: false,
  mapId: undefined as number | undefined,
  switching: false,
  dismissed: false,
})

const needsLocalization = computed(() =>
  robot.connStatus === 'open'
  && robot.status != null
  && !robot.status.pose_initialized
  && robot.status.mode !== 'MAPPING'
  && robot.status.mode !== 'RELOCALIZING')

watch(needsLocalization, (needed) => {
  if (!needed || guide.visible || locateGuide.dismissed) return
  // 默认选中当前部署中（ACTIVE）的地图
  locateGuide.mapId = editor.mapId
    ?? mapStore.maps.find((m) => m.status === 'ACTIVE')?.id
    ?? mapStore.maps[0]?.id
  locateGuide.visible = mapStore.maps.length > 0
}, { immediate: true })

async function deployAndLocalize() {
  if (!locateGuide.mapId) {
    ElMessage.warning('请先选择一张地图')
    return
  }
  locateGuide.switching = true
  try {
    await switchMap(locateGuide.mapId)
    locateGuide.visible = false
    locateGuide.dismissed = true
    ElMessage.success('已下发切图：机器人加载地图并启动定位，收敛后机器人将出现在地图上（必要时用「重定位」微调初始位姿）')
  } catch {
    /* http 层已提示 */
  } finally {
    locateGuide.switching = false
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
        <span>NavDeployer</span>
      </div>

      <div class="header-map">
        <!-- v1 地图下拉：仅 v1 浏览模式（未选 v2 地图）显示，v2 为主流程时不再出现冗余禁用态 -->
        <el-select
          v-if="v2MapId == null"
          v-model="currentMapId"
          placeholder="选择地图"
          size="default"
          style="width: 200px"
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
        <el-tag v-if="editor.mapInfo && v2MapId == null" size="small" :type="editor.mapInfo.status === 'ACTIVE' ? 'success' : 'info'">
          {{ editor.mapInfo.status === 'ACTIVE' ? '部署中' : editor.mapInfo.status }}
        </el-tag>

        <el-divider v-if="v2MapId == null && v2Maps.length > 0" direction="vertical" />

        <el-select
          v-if="v2Maps.length > 0"
          v-model="v2MapId"
          placeholder="选择地图"
          size="default"
          clearable
          :value-on-clear="null"
          style="width: 240px"
        >
          <el-option v-for="m in v2Maps" :key="m.id" :value="m.id" :label="`${m.map_name} · ${m.version}`">
            <div class="map-option">
              <span>{{ m.map_name }}</span>
              <el-tag size="small" :type="m.status === 'PUBLISHED' ? 'success' : 'info'" effect="light">
                {{ m.status === 'PUBLISHED' ? '使用中' : m.status }}
              </el-tag>
            </div>
          </el-option>
        </el-select>
        <el-tag v-if="v2MapId" size="small" type="primary">多楼层</el-tag>
      </div>

      <div class="header-actions">
        <el-button type="primary" :icon="Share" @click="editMap">编辑地图</el-button>
        <el-button :icon="Odometer" @click="toggleMappingPanel">建图</el-button>
        <el-button :icon="Operation" @click="teleopVisible = true">遥控器</el-button>
        <el-button :icon="OfficeBuilding" type="primary" plain @click="manage2Visible = true">地图管理</el-button>
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
          <button class="rail-btn" @click="toggleMappingPanel">
            <el-icon :size="18"><Odometer /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip content="全向遥控器" placement="right">
          <button class="rail-btn" @click="teleopVisible = true">
            <el-icon :size="18"><Operation /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip content="重定位" placement="right">
          <button
            class="rail-btn"
            :class="{ active: v2MapId ? editor2.tool === 'initialPose' : editor.mode === 'initialPose' }"
            @click="toggleInitialPose"
          >
            <el-icon :size="18"><Aim /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip content="地图管理（v2 多楼层）" placement="right">
          <button class="rail-btn" @click="manage2Visible = true">
            <el-icon :size="18"><OfficeBuilding /></el-icon>
          </button>
        </el-tooltip>
        <div class="rail-divider"></div>
        <el-tooltip content="测量距离" placement="right">
          <button
            class="rail-btn"
            :class="{ active: v2MapId ? editor2.tool === 'measure' : editor.mode === 'measure' }"
            @click="toggleMeasure"
          >
            <el-icon :size="18"><ScaleToOriginal /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip content="适应视图" placement="right">
          <button class="rail-btn" @click="v2MapId ? mapCanvas2Ref?.fitView() : mapCanvasRef?.fitView()">
            <el-icon :size="18"><FullScreen /></el-icon>
          </button>
        </el-tooltip>
      </aside>

      <main class="browse-main">
        <MapCanvas2
          v-if="v2MapId"
          ref="mapCanvas2Ref"
          :editable="false"
        />
        <MapCanvas v-else ref="mapCanvasRef" :editable="false" @context="openCtx" />

        <!-- 右侧地图信息面板折叠开关（默认折叠，画布最大化） -->
        <button
          class="panel-toggle"
          :title="browsePanelVisible ? '收起地图信息' : '展开地图信息'"
          @click="browsePanelVisible = !browsePanelVisible"
        >
          <el-icon :size="14">
            <ArrowRight v-if="browsePanelVisible" />
            <ArrowLeft v-else />
          </el-icon>
        </button>
      </main>

      <aside v-if="browsePanelVisible" class="browse-right">
        <V2BrowsePanel
          v-if="v2MapId"
          @locate-point="(xy) => mapCanvas2Ref?.flyToPoint(xy.x, xy.y)"
          @locate-bounds="(pts) => mapCanvas2Ref?.flyToBounds(pts)"
        />
        <BrowsePanel v-else @locate-point="(xy) => mapCanvasRef?.flyToPoint(xy.x, xy.y)" @locate-bounds="(pts) => mapCanvasRef?.flyToBounds(pts)" />
      </aside>
    </div>

    <footer class="browse-footer">
      <RobotStatusBar />
    </footer>

    <MappingPanel v-if="mappingVisible" @close="mappingVisible = false" />
    <MappingPanel2
      v-if="mapping2Visible"
      @close="mapping2Visible = false"
      @map-created="onV2MapCreated"
      @open-teleop="teleopVisible = true"
    />
    <TeleopPanel v-if="teleopVisible" @close="teleopVisible = false" />
    <MapsV2ManageDialog v-model="manage2Visible" />

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

    <!-- 未定位冷启动引导：机器人无建图无定位，页面展示的是存档地图（仅为浏览） -->
    <el-dialog v-model="locateGuide.visible" title="机器人未启动定位" width="480px" :close-on-click-modal="false">
      <div class="guide-body">
        <el-alert type="warning" :closable="false" show-icon
          title="机器人当前既无建图也无定位"
          description="页面显示的是上位机存档地图，仅为浏览（机器人不在图中）。选择一张地图启动定位，或开始新建图。" />
        <el-select v-model="locateGuide.mapId" placeholder="选择地图" style="width: 100%; margin-top: 14px">
          <el-option v-for="m in sortedMaps" :key="m.id" :value="m.id" :label="m.map_name" />
        </el-select>
      </div>
      <template #footer>
        <el-button @click="startMappingNow">开始建图</el-button>
        <el-button @click="locateGuide.visible = false; locateGuide.dismissed = true">仅浏览</el-button>
        <el-button type="primary" :loading="locateGuide.switching" :disabled="!locateGuide.mapId" @click="deployAndLocalize">
          启动定位
        </el-button>
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

/* 右侧信息面板折叠开关：贴画布右缘中部，半透明不遮挡内容 */
.panel-toggle {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  z-index: 30;
  width: 20px;
  height: 64px;
  border: 1px solid #dcdfe6;
  border-right: none;
  border-radius: 6px 0 0 6px;
  background: rgba(255, 255, 255, 0.92);
  color: #606266;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}

.panel-toggle:hover {
  color: #409eff;
  border-color: #409eff;
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
