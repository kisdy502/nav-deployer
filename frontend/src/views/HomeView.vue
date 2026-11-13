<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CircleClose,
  FullScreen,
  Aim,
  Odometer,
  OfficeBuilding,
  Operation,
  ScaleToOriginal,
  Setting,
  Share,
  Tickets,
} from '@element-plus/icons-vue'
import MapCanvas2 from '@/components/editor2/MapCanvas2.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import V2BrowsePanel from '@/components/editor2/V2BrowsePanel.vue'
import RobotStatusBar from '@/components/editor/RobotStatusBar.vue'
import MappingPanel2 from '@/components/editor2/MappingPanel2.vue'
import TeleopPanel from '@/components/editor/TeleopPanel.vue'
import MapsV2ManageDialog from '@/components/editor2/MapsV2ManageDialog.vue'
import { useMapEditor2Store } from '@/stores/mapEditor2'
import { useRobotStore } from '@/stores/robot'
import { getOperational, decommission, recommission } from '@/api/robot'
import { useTasksStore } from '@/stores/tasks'
import { startMapping } from '@/api/tasks'
import { listMapsV2, switchMapV2 } from '@/api/map2'
import type { MapV2VO } from '@/types/map2'
import { onBeforeUnmount } from 'vue'

const router = useRouter()
const editor2 = useMapEditor2Store()
const robot = useRobotStore()
const tasks = useTasksStore()

const mapCanvas2Ref = ref<InstanceType<typeof MapCanvas2>>()

// ---------- v2 地图选择 ----------
const v2Maps = ref<MapV2VO[]>([])
const v2MapId = ref<number | null>(null)
/** 用户手动清除过选择后不再自动兜底选中 */
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
    // 首次进入：存在地图时默认选中（使用中优先，其次最新）
    if (v2MapId.value == null && !v2ManualCleared.value && v2Maps.value.length > 0) {
      v2MapId.value = v2Maps.value.find((m) => m.status === 'PUBLISHED')?.id ?? v2Maps.value[0].id
    }
  } catch {
    /* http 层已提示 */
  }
}

/** 下拉排序：机器人当前地图第一，其次使用中 */
const sortedMaps = computed(() => {
  const name = robot.status?.map_name
  const score = (m: MapV2VO) => {
    if (name && (name === m.map_name || name === m.robot_map_name)) return 0
    return m.status === 'PUBLISHED' ? 1 : 2
  }
  return [...v2Maps.value].sort((a, b) => score(a) - score(b))
})

/** v2 建图面板（实时预览进 v2 画布） */
const mapping2Visible = ref(false)
/** 本次实时预览是否由建图模式自动开启（退出建图时自动关，手动开启的不动） */
const v2AutoLive = ref(false)
const teleopVisible = ref(false)
const manage2Visible = ref(false)

// v2 地图管理（导入/删除）关闭后刷新下拉列表
watch(manage2Visible, (visible, was) => {
  if (!visible && was) void loadV2Maps()
})
const guide = reactive({ visible: false, starting: false })

// ---------- 机器人停用/恢复 ----------
const decommissioned = ref(false)
const decommissionReason = ref('')

async function loadOperational() {
  try {
    const status = await getOperational()
    decommissioned.value = status.decommissioned
    decommissionReason.value = status.reason ?? ''
  } catch { /* 忽略 */ }
}

async function toggleDecommission() {
  try {
    if (decommissioned.value) {
      await recommission()
      decommissioned.value = false
      decommissionReason.value = ''
      ElMessage.success('机器人已恢复可用')
    } else {
      const { value } = await ElMessageBox.prompt('停用原因（如：维修中、退役、故障）', '停用机器人', {
        confirmButtonText: '停用',
        cancelButtonText: '取消',
        inputValue: 'maintenance',
        inputPattern: /\S+/,
        inputErrorMessage: '请输入停用原因',
      })
      const status = await decommission(value.trim())
      decommissioned.value = status.decommissioned
      decommissionReason.value = status.reason ?? ''
      ElMessage.warning(`机器人已停用（${decommissionReason.value}），RMS 不再接收新任务`)
    }
  } catch { /* 取消 */ }
}

// 建图模式联动（刷新页面后恢复建图上下文）：
// v2 地图选中 → v2 面板 + v2 画布实时预览（存档地图与点位/路线全部隐藏）
function syncMappingMode() {
  const mode = robot.status?.mode
  if (mode === 'MAPPING') {
    mapping2Visible.value = true
    if (!editor2.livePreview) {
      editor2.startLivePreview()
      v2AutoLive.value = true
    }
  } else if (mode && v2AutoLive.value) {
    // 退出建图：自动开启的预览自动关（手动开启的不动）
    editor2.stopLivePreview()
    v2AutoLive.value = false
  }
}

watch(() => robot.status?.mode, syncMappingMode, { immediate: true })

function toggleMappingPanel() {
  mapping2Visible.value = !mapping2Visible.value
}

/** v2 建图保存为新地图后：主页直接切到新地图浏览。 */
function onV2MapCreated(id: number) {
  mapping2Visible.value = false
  v2MapId.value = id
}

onMounted(async () => {
  tasks.refresh()
  await loadV2Maps()
  if (v2Maps.value.length === 0) guide.visible = true
  void loadOperational()
})

function editMap() {
  if (!v2MapId.value) {
    ElMessage.warning('请先选择一张地图')
    return
  }
  router.push({ name: 'map2-editor', params: { id: String(v2MapId.value) } })
}

function toggleMeasure() {
  if (!v2MapId.value) return ElMessage.warning('请先选择地图')
  editor2.setTool(editor2.tool === 'measure' ? 'select' : 'measure')
}

function toggleInitialPose() {
  if (!v2MapId.value) return ElMessage.warning('请先选择地图')
  editor2.setTool(editor2.tool === 'initialPose' ? 'select' : 'initialPose')
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
  // 状态失新（桥接连着但机器人停发 /agv/status）不判"未定位"：残留末帧可能已过期
  && robot.snapshot?.status_fresh !== false
  && !robot.status.pose_initialized
  && robot.status.mode !== 'MAPPING'
  && robot.status.mode !== 'RELOCALIZING')

watch(needsLocalization, (needed) => {
  if (!needed) {
    // 定位就绪（或进入建图/重定位、状态失新）：自动撤下冷启动引导。
    // 否则机器人启动收敛期（定位完成前 pose_initialized=false 的瞬时帧）触发过弹窗后，
    // 即使随后 pose_initialized=true 弹窗也会滞留，误导"仍未定位"
    locateGuide.visible = false
    locateGuide.dismissed = false
    return
  }
  if (guide.visible || locateGuide.dismissed) return
  locateGuide.mapId = v2MapId.value
    ?? v2Maps.value.find((m) => m.status === 'PUBLISHED')?.id
    ?? v2Maps.value[0]?.id
  locateGuide.visible = v2Maps.value.length > 0
}, { immediate: true })

async function deployAndLocalize() {
  if (!locateGuide.mapId) {
    ElMessage.warning('请先选择一张地图')
    return
  }
  locateGuide.switching = true
  try {
    await switchMapV2(locateGuide.mapId)
    locateGuide.visible = false
    locateGuide.dismissed = true
    ElMessage.success('已下发切图：机器人加载地图并启动定位，收敛后机器人将出现在地图上（必要时用「重定位」微调初始位姿）')
  } catch {
    /* http 层已提示 */
  } finally {
    locateGuide.switching = false
  }
}

onBeforeUnmount(() => {})
</script>

<template>
  <div class="browse">
    <header class="browse-header">
      <div class="header-title">
        <el-icon :size="20"><MapLocation /></el-icon>
        <span>NavDeployer</span>
      </div>

      <div class="header-map">
        <el-select
          v-if="v2Maps.length > 0"
          v-model="v2MapId"
          placeholder="选择地图"
          size="default"
          clearable
          :value-on-clear="null"
          style="width: 240px"
        >
          <el-option v-for="m in sortedMaps" :key="m.id" :value="m.id" :label="`${m.map_name} · ${m.version}`">
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
        <el-button :icon="Tickets" plain @click="router.push('/tasks')">任务</el-button>
        <el-button :icon="Setting" plain @click="router.push('/config')">配置</el-button>
        <ThemeToggle />
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
            :class="{ active: editor2.tool === 'initialPose' }"
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
            :class="{ active: editor2.tool === 'measure' }"
            @click="toggleMeasure"
          >
            <el-icon :size="18"><ScaleToOriginal /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip content="适应视图" placement="right">
          <button class="rail-btn" @click="mapCanvas2Ref?.fitView()">
            <el-icon :size="18"><FullScreen /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip :content="decommissioned ? `恢复可用（当前停用: ${decommissionReason}）` : '停用机器人（维修中/退役，拒接新任务）'" placement="right">
          <button
            class="rail-btn"
            :class="{ 'rail-btn-danger': decommissioned }"
            :style="decommissioned ? { color: '#f56c6c', background: '#fef0f0' } : {}"
            @click="toggleDecommission"
          >
            <el-icon :size="18"><CircleClose /></el-icon>
          </button>
        </el-tooltip>
      </aside>

      <main class="browse-main">
        <MapCanvas2
          v-if="v2MapId || editor2.livePreview"
          ref="mapCanvas2Ref"
          :editable="false"
        />
        <el-empty v-else description="暂无地图，请先建图或导入" />

        <!-- 右侧地图信息面板折叠开关（默认折叠，画布最大化） -->
        <button
          v-if="v2MapId"
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

      <aside v-if="browsePanelVisible && v2MapId" class="browse-right">
        <V2BrowsePanel
          @locate-point="(xy) => mapCanvas2Ref?.flyToPoint(xy.x, xy.y)"
          @locate-bounds="(pts) => mapCanvas2Ref?.flyToBounds(pts)"
        />
      </aside>
    </div>

    <footer class="browse-footer">
      <RobotStatusBar />
    </footer>

    <MappingPanel2
      v-if="mapping2Visible"
      @close="mapping2Visible = false"
      @map-created="onV2MapCreated"
      @open-teleop="teleopVisible = true"
    />
    <TeleopPanel v-if="teleopVisible" @close="teleopVisible = false" />
    <MapsV2ManageDialog v-model="manage2Visible" />

    <!-- 空地图建图引导 -->
    <el-dialog v-model="guide.visible" title="欢迎使用 Nav Deployer" width="460px" :close-on-click-modal="false">
      <div class="guide-body">
        <el-empty description="当前还没有任何地图" :image-size="80" />
        <div class="guide-steps">
          <div>1. 点击「开始建图」，机器人进入在线建图模式；</div>
          <div>2. 遥控机器人走遍作业区域，地图实时预览；</div>
          <div>3. 建图完成后在「建图」面板保存，地图自动入库并发布；</div>
          <div>4. 发布后即可在本页浏览、编辑点位与路线。</div>
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
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-light);
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
  background: var(--el-bg-color);
  border-right: 1px solid var(--el-border-color-light);
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
  color: var(--el-text-color-regular);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.rail-btn:hover {
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}

.rail-btn.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
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
  border: 1px solid var(--el-border-color);
  border-right: none;
  border-radius: 6px 0 0 6px;
  background: var(--el-bg-color-overlay);
  color: var(--el-text-color-regular);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}

.panel-toggle:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
}

.browse-right {
  width: 300px;
  background: var(--el-bg-color);
  border-left: 1px solid var(--el-border-color-light);
}

.browse-footer {
  height: 36px;
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
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 12px 16px;
  margin-top: 8px;
}
</style>
