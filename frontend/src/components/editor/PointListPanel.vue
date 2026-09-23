<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Aim, Delete, EditPen, Plus, Position, Promotion, Search } from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editor'
import { useRobotStore } from '@/stores/robot'
import { useTasksStore } from '@/stores/tasks'
import { POINT_TYPE_LABEL } from '@/utils/format'
import { radToDeg } from '@/utils/coords'
import type { PointType } from '@/types/api'

const emit = defineEmits<{
  (e: 'open-place-dialog', xy: { x: number; y: number }): void
  (e: 'locate', xy: { x: number; y: number }): void
}>()

const editor = useEditorStore()
const robot = useRobotStore()
const tasks = useTasksStore()

const keyword = ref('')

const colorOf = (t: string): string => (t === 'CHARGER' ? '#e6a23c' : t === 'HOME' ? '#67c23a' : '#409eff')

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return editor.points
  return editor.points.filter(
    (p) => p.point_code.toLowerCase().includes(k) || (p.remark ?? '').toLowerCase().includes(k),
  )
})

// 手动建点对话框
const manualDialog = reactive({
  visible: false,
  saving: false,
  point_code: '',
  point_type: 'NORMAL' as PointType,
  x: 0,
  y: 0,
  yawDeg: 0,
  remark: '',
})

// 机器人当前位姿建点对话框
const robotDialog = reactive({
  visible: false,
  saving: false,
  point_code: '',
  point_type: 'NORMAL' as PointType,
  remark: '',
})

function openManual(xy?: { x: number; y: number }) {
  manualDialog.point_code = ''
  manualDialog.point_type = 'NORMAL'
  manualDialog.x = xy?.x ?? 0
  manualDialog.y = xy?.y ?? 0
  manualDialog.yawDeg = 0
  manualDialog.remark = ''
  manualDialog.visible = true
}

async function saveManual() {
  if (!manualDialog.point_code.trim()) {
    ElMessage.warning('请输入点位编码')
    return
  }
  manualDialog.saving = true
  try {
    await editor.createPoint({
      point_code: manualDialog.point_code.trim(),
      point_type: manualDialog.point_type,
      x: manualDialog.x,
      y: manualDialog.y,
      yaw: (manualDialog.yawDeg * Math.PI) / 180,
      remark: manualDialog.remark.trim() || undefined,
    })
    manualDialog.visible = false
    ElMessage.success('点位已创建')
  } catch {
    /* http 层已提示 */
  } finally {
    manualDialog.saving = false
  }
}

function openRobotDialog() {
  if (!robot.status?.pose_initialized) {
    ElMessage.warning('机器人位姿未初始化，无法以当前位姿建点')
    return
  }
  robotDialog.point_code = ''
  robotDialog.point_type = 'NORMAL'
  robotDialog.remark = ''
  robotDialog.visible = true
}

async function saveFromRobot() {
  if (!robotDialog.point_code.trim()) {
    ElMessage.warning('请输入点位编码')
    return
  }
  robotDialog.saving = true
  try {
    const vo = await editor.createPointFromRobot({
      point_code: robotDialog.point_code.trim(),
      point_type: robotDialog.point_type,
      remark: robotDialog.remark.trim() || undefined,
    })
    robotDialog.visible = false
    ElMessage.success(`已按机器人当前位姿创建点位（${vo.x.toFixed(2)}, ${vo.y.toFixed(2)}）`)
  } catch {
    /* http 层已提示 */
  } finally {
    robotDialog.saving = false
  }
}

function select(id: number) {
  editor.selectPoint(id)
  const p = editor.pointsById.get(id)
  if (p) emit('locate', { x: p.x, y: p.y })
}

async function remove(p: { id: number; point_code: string }) {
  await ElMessageBox.confirm(`确认删除点位「${p.point_code}」？`, '删除点位', { type: 'warning' })
  await editor.deletePoint(p.id)
  ElMessage.success('已删除')
}

async function navigate(p: { id: number; point_code: string }) {
  if (!robot.status?.pose_initialized) {
    ElMessage.warning('机器人位姿未初始化，无法导航')
    return
  }
  if (tasks.moveTaskInFlight) {
    ElMessage.warning('已有执行中的移动任务，请先取消')
    return
  }
  await tasks.navigateToPoint(p.id)
}

defineExpose({ openManual })
</script>

<template>
  <div class="panel">
    <div class="panel-actions">
      <el-input v-model="keyword" placeholder="搜索编码/备注" clearable :prefix-icon="Search" style="flex: 1" />
      <el-tooltip content="在地图上点击放置" placement="top">
        <el-button type="primary" :icon="Plus" @click="editor.setMode('placePoint')"></el-button>
      </el-tooltip>
      <el-tooltip content="手动输入坐标建点" placement="top">
        <el-button :icon="EditPen" @click="openManual()"></el-button>
      </el-tooltip>
      <el-tooltip content="按机器人当前位姿建点" placement="top">
        <el-button type="success" plain :icon="Aim" @click="openRobotDialog()"></el-button>
      </el-tooltip>
    </div>

    <el-scrollbar class="panel-list">
      <div
        v-for="p in filtered"
        :key="p.id"
        class="point-item"
        :class="{ selected: editor.selectedPointId === p.id }"
        @click="editor.selectPoint(p.id)"
        @dblclick="select(p.id)"
      >
        <span class="point-dot" :style="{ background: colorOf(p.point_type) }"></span>
        <div class="point-main">
          <div class="point-code">
            {{ p.point_code }}
            <el-tag size="small" type="info" effect="plain">{{ POINT_TYPE_LABEL[p.point_type] ?? p.point_type }}</el-tag>
          </div>
          <div class="point-sub">({{ p.x.toFixed(2) }}, {{ p.y.toFixed(2) }}) 朝向 {{ radToDeg(p.yaw) }}°</div>
        </div>
        <div class="point-ops" @click.stop>
          <el-tooltip content="定位" placement="top">
            <el-button size="small" text circle :icon="Position" @click="emit('locate', { x: p.x, y: p.y })" />
          </el-tooltip>
          <el-tooltip content="导航到此" placement="top">
            <el-button size="small" text circle type="primary" :icon="Promotion" @click="navigate(p)" />
          </el-tooltip>
          <el-tooltip content="删除" placement="top">
            <el-button size="small" text circle type="danger" :icon="Delete" @click="remove(p)" />
          </el-tooltip>
        </div>
      </div>
      <el-empty v-if="filtered.length === 0" description="暂无点位" :image-size="60" />
    </el-scrollbar>

    <el-dialog v-model="manualDialog.visible" title="手动创建点位" width="440px">
      <el-form :model="manualDialog" label-width="80px" @submit.prevent>
        <el-form-item label="编码" required>
          <el-input v-model="manualDialog.point_code" maxlength="64" placeholder="图内唯一，如 P5" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="manualDialog.point_type">
            <el-radio-button value="NORMAL">普通点</el-radio-button>
            <el-radio-button value="CHARGER">充电点</el-radio-button>
            <el-radio-button value="HOME">待命点</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="坐标">
          <el-input-number v-model="manualDialog.x" :step="0.05" style="width: 140px" />
          <el-input-number v-model="manualDialog.y" :step="0.05" style="width: 140px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="朝向(°)">
          <el-input-number v-model="manualDialog.yawDeg" :min="-180" :max="180" :step="5" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="manualDialog.remark" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="manualDialog.saving" @click="saveManual">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="robotDialog.visible" title="以机器人当前位姿创建点位" width="420px">
      <el-form :model="robotDialog" label-width="80px" @submit.prevent>
        <el-form-item label="编码" required>
          <el-input v-model="robotDialog.point_code" maxlength="64" placeholder="图内唯一，如 P5" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="robotDialog.point_type">
            <el-radio-button value="NORMAL">普通点</el-radio-button>
            <el-radio-button value="CHARGER">充电点</el-radio-button>
            <el-radio-button value="HOME">待命点</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="robotDialog.remark" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="robotDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="robotDialog.saving" @click="saveFromRobot">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.panel {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.panel-actions {
  display: flex;
  gap: 6px;
  padding: 10px;
}

.panel-list {
  flex: 1;
}

.point-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  cursor: pointer;
  border-left: 3px solid transparent;
}

.point-item:hover {
  background: #f5f7fa;
}

.point-item.selected {
  background: #ecf5ff;
  border-left-color: #409eff;
}

.point-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.point-main {
  flex: 1;
  min-width: 0;
}

.point-code {
  font-size: 13.5px;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 6px;
}

.point-sub {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.point-ops {
  display: flex;
  gap: 2px;
}
</style>
