<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Odometer, Position } from '@element-plus/icons-vue'
import EditorToolbar from '@/components/editor/EditorToolbar.vue'
import MapCanvas from '@/components/editor/MapCanvas.vue'
import PointListPanel from '@/components/editor/PointListPanel.vue'
import PathListPanel from '@/components/editor/PathListPanel.vue'
import PropertyPanel from '@/components/editor/PropertyPanel.vue'
import RobotStatusBar from '@/components/editor/RobotStatusBar.vue'
import MappingPanel from '@/components/editor/MappingPanel.vue'
import { useEditorStore } from '@/stores/editor'
import { useTasksStore } from '@/stores/tasks'
import type { PickResult } from '@/three/MapScene'

const props = defineProps<{ id: string }>()

const route = useRoute()
const router = useRouter()
const editor = useEditorStore()
const tasks = useTasksStore()

const mapCanvasRef = ref<InstanceType<typeof MapCanvas>>()
const pointPanelRef = ref<InstanceType<typeof PointListPanel>>()
const mappingVisible = ref(false)
const loadingLocal = ref(false)
const activeTab = ref<'points' | 'paths'>('points')

// ---------- 右键菜单 ----------
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

function openCtx(payload: { hit: PickResult | null; x: number; y: number; world: { x: number; y: number } }) {
  const hit = payload.hit
  const items: CtxItem[] = []

  if (hit?.type === 'point' && hit.id != null) {
    const p = editor.pointsById.get(hit.id)
    if (p) {
      editor.selectPoint(p.id)
      items.push({
        label: '删除点位',
        danger: true,
        action: async () => {
          await editor.deletePoint(p.id)
          ElMessage.success('已删除')
        },
      })
    }
  } else if (editor.pathEdit) {
    if (hit?.type === 'control' && hit.edgeIdx != null) {
      editor.selectedEdgeIdx = hit.edgeIdx
      items.push({ label: '切换直线/曲线', action: () => editor.edgeToggleType(hit!.edgeIdx!) })
    } else {
      items.push({ label: '退出路线编辑', action: () => editor.cancelPathEdit() })
    }
  } else {
    items.push(
      {
        label: '在此创建点位',
        action: () => pointPanelRef.value?.openManual({ x: payload.world.x, y: payload.world.y }),
      },
      { label: '适应视图', action: () => mapCanvasRef.value?.fitView() },
    )
  }
  if (items.length === 0) return
  ctx.items = items
  ctx.x = payload.x
  ctx.y = payload.y
  ctx.visible = true
  window.addEventListener('click', closeCtx, { once: true })
}

// ---------- 定位 ----------
function onLocatePoint(xy: { x: number; y: number }) {
  mapCanvasRef.value?.flyToPoint(xy.x, xy.y)
}

function onLocateBounds(pts: { x: number; y: number }[]) {
  if (pts.length === 1) mapCanvasRef.value?.flyToPoint(pts[0].x, pts[0].y)
  else mapCanvasRef.value?.flyToBounds(pts)
}

function onPathLocate(payload: { points: { x: number; y: number }[] }) {
  onLocateBounds(payload.points)
}

// ---------- 建点确认 ----------
const placeDialog = reactive({
  visible: false,
  saving: false,
  code: '',
  type: 'NORMAL' as 'NORMAL' | 'CHARGER' | 'HOME',
  x: 0,
  y: 0,
  yawDeg: 0,
})

function onPlacePoint(xy: { x: number; y: number }) {
  placeDialog.code = ''
  placeDialog.type = 'NORMAL'
  placeDialog.x = xy.x
  placeDialog.y = xy.y
  placeDialog.yawDeg = 0
  placeDialog.visible = true
  editor.setMode('idle')
}

async function savePlacePoint() {
  if (!placeDialog.code.trim()) {
    ElMessage.warning('请输入点位编码')
    return
  }
  placeDialog.saving = true
  try {
    const vo = await editor.createPoint({
      point_code: placeDialog.code.trim(),
      point_type: placeDialog.type,
      x: placeDialog.x,
      y: placeDialog.y,
      yaw: (placeDialog.yawDeg * Math.PI) / 180,
    })
    placeDialog.visible = false
    ElMessage.success(`点位「${vo.point_code}」已创建`)
  } catch {
    /* http 层已提示 */
  } finally {
    placeDialog.saving = false
  }
}

// ---------- 生命周期 ----------
onMounted(async () => {
  const mapId = Number(props.id ?? route.params.id)
  if (!Number.isFinite(mapId) || mapId <= 0) {
    router.replace('/')
    return
  }
  loadingLocal.value = true
  try {
    await editor.loadMap(mapId)
  } catch {
    router.replace('/')
    return
  } finally {
    loadingLocal.value = false
  }
  tasks.refresh()
})

onBeforeUnmount(() => {
  closeCtx()
})
</script>

<template>
  <div class="editor" v-loading="loadingLocal">
    <el-header height="50px" class="editor-header">
      <EditorToolbar />
    </el-header>

    <el-container class="editor-body">
      <el-aside width="290px" class="editor-left">
        <el-tabs v-model="activeTab" class="left-tabs">
          <el-tab-pane label="点位" name="points">
            <PointListPanel ref="pointPanelRef" @locate="onLocatePoint" />
          </el-tab-pane>
          <el-tab-pane label="路线" name="paths">
            <PathListPanel @locate="onPathLocate" />
          </el-tab-pane>
        </el-tabs>
      </el-aside>

      <el-main class="editor-main">
        <MapCanvas ref="mapCanvasRef" @place-point="onPlacePoint" @context="openCtx" />
      </el-main>

      <el-aside width="310px" class="editor-right">
        <PropertyPanel @locate="onLocatePoint" />
      </el-aside>
    </el-container>

    <el-footer height="36px" class="editor-footer">
      <RobotStatusBar />
    </el-footer>

    <!-- 右键菜单 -->
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

    <!-- 建图抽屉 -->
    <MappingPanel v-if="mappingVisible" @close="mappingVisible = false" />

    <!-- 点位落点确认 -->
    <el-dialog v-model="placeDialog.visible" title="创建点位" width="400px" :append-to-body="true">
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="编码" required>
          <el-input v-model="placeDialog.code" maxlength="64" placeholder="图内唯一，如 P5" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="placeDialog.type">
            <el-radio-button value="NORMAL">普通点</el-radio-button>
            <el-radio-button value="CHARGER">充电点</el-radio-button>
            <el-radio-button value="HOME">待命点</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="坐标">
          <span class="muted">({{ placeDialog.x.toFixed(2) }}, {{ placeDialog.y.toFixed(2) }}) m</span>
        </el-form-item>
        <el-form-item label="朝向(°)">
          <el-input-number v-model="placeDialog.yawDeg" :min="-180" :max="180" :step="5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="placeDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="placeDialog.saving" @click="savePlacePoint">创建</el-button>
      </template>
    </el-dialog>

    <!-- 右下浮动按钮 -->
    <div class="float-btns">
      <el-button circle :icon="Position" title="适应视图" @click="mapCanvasRef?.fitView()" />
      <el-button circle :icon="Odometer" title="建图" @click="mappingVisible = !mappingVisible" />
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

.editor-left {
  background: #fff;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
}

.left-tabs {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.left-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.left-tabs :deep(.el-tab-pane) {
  height: 100%;
}

.editor-main {
  padding: 0;
  position: relative;
  overflow: hidden;
}

.editor-right {
  background: #fff;
  border-left: 1px solid #e4e7ed;
}

.editor-footer {
  padding: 0;
}

.ctx-menu {
  position: fixed;
  z-index: 3000;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  padding: 4px;
  min-width: 140px;
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

.float-btns {
  position: absolute;
  right: 330px;
  bottom: 56px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 20;
}

.float-btns .el-button + .el-button {
  margin-left: 0;
}

.muted {
  color: #909399;
}
</style>
