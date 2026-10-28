<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { getRmsActiveTask, listMoveTasks, listRmsTasks, resetRmsTasks, cancelMoveTask } from '@/api/tasks'
import { listChargeTasks } from '@/api/charge'
import type { ChargeTaskVO, MoveTaskVO, RmsTaskVO } from '@/types/api'

const router = useRouter()
const activeTab = ref('rms')
const activeRms = ref<RmsTaskVO | null>(null)
const rmsTasks = ref<RmsTaskVO[]>([])
const moveTasks = ref<MoveTaskVO[]>([])
const chargeTasks = ref<ChargeTaskVO[]>([])
const resetting = ref(false)
let timer: number | undefined

async function refresh() {
  try {
    const [rms, active, moves, charges] = await Promise.all([
      listRmsTasks(100),
      getRmsActiveTask(),
      listMoveTasks(50),
      listChargeTasks(50),
    ])
    rmsTasks.value = rms
    activeRms.value = active
    moveTasks.value = moves
    chargeTasks.value = charges
  } catch {
    /* 轮询失败静默，下一轮重试 */
  }
}

/** 复位：强制收口所有非终态 RMS 任务（取消内部移动/充电 + 上报 RMS + 释放 busy 闸门） */
async function doReset() {
  try {
    await ElMessageBox.confirm(
      '将强制取消所有未完成的 RMS 任务（连同内部移动/充电任务）并上报 RMS，之后上层可继续下发新任务。确定复位？',
      '任务复位',
      { type: 'warning', confirmButtonText: '复位', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  resetting.value = true
  try {
    const closed = await resetRmsTasks()
    if (closed.length) {
      ElMessage.success(`已复位 ${closed.length} 个任务：${closed.map((t) => t.task_id).join(', ')}`)
    } else {
      ElMessage.info('当前没有需要复位的任务')
    }
    await refresh()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '复位失败')
  } finally {
    resetting.value = false
  }
}

async function doCancelMove(task: MoveTaskVO) {
  try {
    await cancelMoveTask(task.id)
    ElMessage.success(`已取消移动任务 ${task.id}`)
    await refresh()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '取消失败')
  }
}

function rmsStatusType(status: string) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'RUNNING' || status === 'PAUSED') return 'warning'
  if (status === 'CANCELED' || status === 'IDLE') return 'info'
  return 'danger'
}

function moveStatusType(status: string | null) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'CREATED' || status === 'DISPATCHED' || status === 'EXECUTING') return 'warning'
  if (status === 'CANCELLED') return 'info'
  return 'danger'
}

function chargeStatusType(status: string) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'CANCELLED') return 'info'
  if (status === 'FAILED') return 'danger'
  return 'warning'
}

function moveActive(task: MoveTaskVO) {
  return task.status === 'CREATED' || task.status === 'DISPATCHED' || task.status === 'EXECUTING'
}

function fmtTime(value: string | null | undefined) {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

function fmtNum(value: number | null | undefined, digits = 2) {
  return value == null ? '—' : value.toFixed(digits)
}

function fmtDuration(task: { created_at: string; finished_at: string | null; started_at: string | null }) {
  const end = task.finished_at ? Date.parse(task.finished_at) : Date.now()
  const begin = task.started_at ? Date.parse(task.started_at) : Date.parse(task.created_at)
  if (!Number.isFinite(end) || !Number.isFinite(begin)) return '—'
  const seconds = Math.max(0, Math.round((end - begin) / 1000))
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m${seconds % 60}s`
  return `${Math.floor(minutes / 60)}h${minutes % 60}m`
}

onMounted(() => {
  refresh()
  timer = window.setInterval(refresh, 3000)
})
onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="tasks-page">
    <header class="page-header">
      <el-button :icon="ArrowLeft" @click="router.push('/')">返回主页</el-button>
      <span class="page-title">任务管理</span>
      <span class="spacer"></span>
      <el-button :icon="Refresh" @click="refresh">刷新</el-button>
    </header>

    <!-- 当前任务 + 复位 -->
    <section class="current-card">
      <div class="current-info">
        <template v-if="activeRms">
          <el-tag type="warning" effect="dark">当前任务</el-tag>
          <span class="task-id">{{ activeRms.task_id }}</span>
          <el-tag size="small">{{ activeRms.task_template_type || activeRms.task_type || '—' }}</el-tag>
          <el-tag size="small" :type="rmsStatusType(activeRms.status)">{{ activeRms.status }}</el-tag>
          <span v-if="activeRms.message" class="current-msg">{{ activeRms.message }}</span>
        </template>
        <el-tag v-else type="success" effect="plain">空闲：无在途 RMS 任务</el-tag>
      </div>
      <el-button
        type="danger"
        plain
        :icon="WarningFilled"
        :loading="resetting"
        @click="doReset"
      >复位（释放任务闸门）</el-button>
    </section>

    <el-tabs v-model="activeTab" class="task-tabs">
      <el-tab-pane :label="`RMS 任务（${rmsTasks.length}）`" name="rms">
        <el-table :data="rmsTasks" size="small" border stripe>
          <el-table-column prop="task_id" label="任务 ID" min-width="230" show-overflow-tooltip />
          <el-table-column label="模板 / 类型" width="140">
            <template #default="{ row }">
              <div>{{ row.task_template_type || '—' }}</div>
              <div class="sub">{{ row.task_type || '—' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }">
              <el-tag size="small" :type="rmsStatusType(row.status)">{{ row.status }}</el-tag>
              <span class="sub">code={{ row.action_status_code }}</span>
            </template>
          </el-table-column>
          <el-table-column label="目标点" width="150">
            <template #default="{ row }">
              <span v-if="row.dest_x != null">({{ fmtNum(row.dest_x, 2) }}, {{ fmtNum(row.dest_y, 2) }})</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="内部任务" width="120">
            <template #default="{ row }">
              <span v-if="row.internal_move_task_id">移动#{{ row.internal_move_task_id }}</span>
              <span v-if="row.internal_charge_task_id">充电#{{ row.internal_charge_task_id }}</span>
              <span v-if="!row.internal_move_task_id && !row.internal_charge_task_id">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="结果 / 原因" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.message || '—' }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="80">
            <template #default="{ row }">{{ fmtDuration(row) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`移动任务（${moveTasks.length}）`" name="move">
        <el-table :data="moveTasks" size="small" border stripe>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="task_no" label="编号" min-width="200" show-overflow-tooltip />
          <el-table-column prop="task_type" label="类型" width="110" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="moveStatusType(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="目标" width="160">
            <template #default="{ row }">
              <span v-if="row.goal_x != null">({{ fmtNum(row.goal_x) }}, {{ fmtNum(row.goal_y) }})</span>
              <span v-else-if="row.point_id">点#{{ row.point_id }}</span>
              <span v-else-if="row.path_id">路线#{{ row.path_id }}</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="段进度" width="90">
            <template #default="{ row }">
              <span v-if="row.segment_total">{{ row.segment_seq }}/{{ row.segment_total }}</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="error_message" label="错误信息" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.error_message || '—' }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button v-if="moveActive(row)" size="small" type="warning" plain @click="doCancelMove(row)">
                取消
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`充电任务（${chargeTasks.length}）`" name="charge">
        <el-table :data="chargeTasks" size="small" border stripe>
          <el-table-column prop="id" label="ID" width="150" />
          <el-table-column prop="source" label="来源" width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="chargeStatusType(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="电量" width="140">
            <template #default="{ row }">
              <span v-if="row.battery_now != null">
                {{ fmtNum(row.battery_now, 1) }}% / 目标 {{ fmtNum(row.target_battery_pct, 0) }}%
              </span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="进度 / 结果" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.message || '—' }}</template>
          </el-table-column>
          <el-table-column label="内部移动" width="100">
            <template #default="{ row }">
              <span v-if="row.internal_move_task_id">#{{ row.internal_move_task_id }}</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.tasks-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 16px 20px;
  box-sizing: border-box;
  gap: 12px;
}

.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
}

.spacer {
  flex: 1;
}

.current-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.current-info {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex-wrap: wrap;
}

.task-id {
  font-family: monospace;
  font-weight: 600;
}

.current-msg {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.task-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.task-tabs :deep(.el-tabs__content) {
  flex: 1;
  overflow: auto;
}

.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
