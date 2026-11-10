<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { getRmsActiveTask, listMoveTaskSegments, listMoveTasks, listRmsTasks, resetRmsTasks, cancelMoveTask } from '@/api/tasks'
import { listChargeTasks } from '@/api/charge'
import type { ChargeTaskVO, MoveTaskSegmentVO, MoveTaskVO, RmsTaskVO } from '@/types/api'

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
      listMoveTasks(200),
      listChargeTasks(100),
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

// ==================== 调度任务（RMS）阶段关联 ====================

/** 单个执行阶段（时间线一行）：RMS 下发的导航 / 充电 / 充电内部导航。 */
interface StageItem {
  key: string
  kind: string
  kindType: 'primary' | 'success' | 'warning'
  desc: string
  status: string
  statusType: 'success' | 'warning' | 'danger' | 'info'
  time: string
  message: string | null
}

function stageStatusType(status: string): StageItem['statusType'] {
  if (status === 'SUCCEEDED' || status === 'COMPLETED') return 'success'
  if (['CREATED', 'DISPATCHED', 'EXECUTING', 'RUNNING', 'PAUSED', 'MOVING', 'STARTING'].includes(status)) return 'warning'
  if (status === 'CANCELLED' || status === 'CANCELED' || status === 'IDLE') return 'info'
  return 'danger'
}

/**
 * 一个 RMS 任务的完整执行链（按时间正序）：先发的导航（rms:{task_id}）→
 * 后发的 start-activity 充电（rms:{task_id}）→ 充电内部导航（charge:{chargeTaskId}）。
 * 同 task_id 多阶段天然聚合在同一行展开里，方便对照排查。
 */
function stagesOf(rms: RmsTaskVO): StageItem[] {
  const prefix = `rms:${rms.task_id}`
  const stages: StageItem[] = []
  for (const m of moveTasks.value) {
    if (m.source?.startsWith(prefix)) {
      stages.push({
        key: `m${m.id}`,
      kind: '导航',
      kindType: 'primary',
      desc: moveDesc(m),
        status: m.status ?? '—',
        statusType: stageStatusType(m.status ?? ''),
        time: m.created_at,
        message: m.error_message,
      })
    }
  }
  for (const c of chargeTasks.value) {
    if (!c.source?.startsWith(prefix)) continue
    stages.push({
      key: `c${c.id}`,
      kind: '充电',
      kindType: 'success',
      desc: c.x != null
        ? `去桩 (${fmtNum(c.x)}, ${fmtNum(c.y)}) 充至 ${fmtNum(c.target_battery_pct, 0)}%`
        : `原地充电 至 ${fmtNum(c.target_battery_pct, 0)}%`,
      status: c.status,
      statusType: stageStatusType(c.status),
      time: c.created_at,
      message: c.message,
    })
    for (const m of moveTasks.value) {
      if (m.source === `charge:${c.id}`) {
        stages.push({
          key: `cm${m.id}`,
          kind: '充电·导航',
          kindType: 'warning',
          desc: moveDesc(m),
          status: m.status ?? '—',
          statusType: stageStatusType(m.status ?? ''),
          time: m.created_at,
          message: m.error_message,
        })
      }
    }
  }
  return stages.sort((a, b) => Date.parse(a.time) - Date.parse(b.time))
}

/** RMS 任务行的阶段摘要 tag（导航 → 充电）。 */
function stageSummary(rms: RmsTaskVO) {
  const stages = stagesOf(rms)
  return stages.map((s) => s.kind)
}

// ==================== 本机任务（手动） ====================

interface LocalItem {
  key: string
  kind: '移动' | '充电' | '救援充电'
  kindType: 'primary' | 'success' | 'danger'
  ref: string
  /** 行程组内的移动任务（手动连续首尾相接的任务聚合；单条任务也是单元素组） */
  moves?: MoveTaskVO[]
  move?: MoveTaskVO
  charge?: ChargeTaskVO
  /** 充电任务聚合的内部导航（source=charge:{id}），展开行显示 */
  innerMoves?: MoveTaskVO[]
  status: string
  statusType: 'success' | 'warning' | 'danger' | 'info'
  desc: string
  message: string | null
  time: string
}

/** 手动发起的任务聚合时间线，新→旧；连续首尾相接的手动导航归为一组行程，充电任务连同其内部导航聚合为一行 */
const localTasks = computed<LocalItem[]>(() => {
  const items: LocalItem[] = []
  const manualMoves = moveTasks.value
    .filter((m) => !m.source || m.source === 'manual')
    .sort((a, b) => Date.parse(a.created_at) - Date.parse(b.created_at))
  const trips: MoveTaskVO[][] = []
  for (const m of manualMoves) {
    const group = trips[trips.length - 1]
    const last = group?.[group.length - 1]
    if (
      last &&
      tailPoint(last.route_summary) &&
      tailPoint(last.route_summary) === headPoint(m.route_summary) &&
      Date.parse(m.created_at) - Date.parse(last.created_at) <= 3 * 60 * 1000
    ) {
      group.push(m)
    } else {
      trips.push([m])
    }
  }
  for (const trip of trips.slice().reverse()) {
    const last = trip[trip.length - 1]
    const head = headPoint(trip[0].route_summary)
    const tail = tailPoint(last.route_summary)
    const chain = trip.map((m) => m.route_summary || `#${m.id}`).join('；')
    items.push({
      key: `t${trip[0].id}`,
      kind: '移动',
      kindType: 'primary',
      ref: trip.length > 1 ? `${trip.length} 段` : `#${trip[0].id}`,
      moves: trip,
      status: last.status ?? '—',
      statusType: stageStatusType(last.status ?? ''),
      desc: head && tail && head !== tail
        ? `${head} → … → ${tail}`
        : chain,
      message: trip.map((m) => m.error_message).filter(Boolean).join('；') || null,
      time: trip[0].created_at,
    })
  }
  for (const c of chargeTasks.value) {
    if (c.source !== 'manual' && c.source !== 'rescue') continue
    const rescue = c.source === 'rescue'
    const innerMoves = moveTasks.value.filter((m) => m.source === `charge:${c.id}`)
    const routeText = innerMoves.length && innerMoves[0].route_summary ? `：${innerMoves[0].route_summary}` : ''
    items.push({
      key: `c${c.id}`,
      kind: rescue ? '救援充电' : '充电',
      kindType: rescue ? 'danger' : 'success',
      ref: `#${c.id}`,
      charge: c,
      innerMoves,
      status: c.status,
      statusType: stageStatusType(c.status),
      desc: rescue
        ? `原地充至 ${fmtNum(c.target_battery_pct, 0)}%`
        : c.x != null
          ? `去充电点充至 ${fmtNum(c.target_battery_pct, 0)}%${routeText}`
          : `原地充至 ${fmtNum(c.target_battery_pct, 0)}%`,
      message: c.message,
      time: c.created_at,
    })
  }
  return items.sort((a, b) => Date.parse(b.time) - Date.parse(a.time))
})

/** 路线摘要首点（"H9→P13→P2" → "H9"；空返回 null） */
function headPoint(summary?: string | null): string | null {
  if (!summary) return null
  const pts = summary.split('→').map((s) => s.trim()).filter(Boolean)
  return pts.length ? pts[0] : null
}

/** 路线摘要尾点 */
function tailPoint(summary?: string | null): string | null {
  if (!summary) return null
  const pts = summary.split('→').map((s) => s.trim()).filter(Boolean)
  return pts.length ? pts[pts.length - 1] : null
}

function localMoveActive(item: LocalItem) {
  const last = item.moves?.[item.moves.length - 1]
  return last && ['CREATED', 'DISPATCHED', 'EXECUTING'].includes(last.status ?? '')
}

// ==================== 展示辅助 ====================

/** 展开行懒加载的段执行记录（task_id → 段列表） */
const segmentCache = ref<Record<number, MoveTaskSegmentVO[]>>({})

async function loadSegments(taskId: number) {
  if (segmentCache.value[taskId]) return
  try {
    segmentCache.value = { ...segmentCache.value, [taskId]: await listMoveTaskSegments(taskId) }
  } catch {
    segmentCache.value = { ...segmentCache.value, [taskId]: [] }
  }
}

/** 展开回调：行程组内每个任务懒加载真实段记录 */
function onExpandRow(row: LocalItem, expanded: LocalItem[]) {
  if (!expanded.includes(row)) return
  for (const m of row.moves ?? []) void loadSegments(m.id)
  if (row.charge && row.innerMoves?.length) void loadSegments(row.innerMoves[0].id)
}

/** 移动任务描述：优先点位链摘要（H9→P13→P2），旧数据回退坐标 */
function moveDesc(m: MoveTaskVO) {
  if (m.route_summary) return m.route_summary
  return m.task_type + (m.goal_x != null ? ` → (${fmtNum(m.goal_x)}, ${fmtNum(m.goal_y)})` : m.path_id ? ` 路线#${m.path_id}` : '')
}

/** 段级状态 */
type SegState = 'done' | 'active' | 'pending' | 'failed'

function segStatusOf(status: string): SegState {
  if (status === 'SUCCEEDED') return 'done'
  if (status === 'EXECUTING') return 'active'
  if (status === 'FAILED' || status === 'CANCELLED' || status === 'TIMEOUT') return 'failed'
  return 'pending'
}

function segLabelOf(status: string): string {
  return ({ SUCCEEDED: '已完成', EXECUTING: '执行中', FAILED: '失败', CANCELLED: '已取消', TIMEOUT: '超时' } as const)[status] ?? status
}

function rmsStatusType(status: string) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'RUNNING' || status === 'PAUSED') return 'warning'
  if (status === 'CANCELED' || status === 'IDLE') return 'info'
  return 'danger'
}

function fmtTime(value: string | null | undefined) {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

function fmtNum(value: number | null | undefined, digits = 2) {
  return value == null ? '—' : value.toFixed(digits)
}

function fmtDuration(task: { created_at: string; finished_at: string | null; started_at?: string | null }) {
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
      <!-- 调度任务：RMS 下发的任务（含多阶段：导航 → start-activity 充电），展开看完整执行链 -->
      <el-tab-pane :label="`调度任务（${rmsTasks.length}）`" name="rms">
        <el-table :data="rmsTasks" size="small" border stripe row-key="task_id">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="stage-list">
                <div v-for="stage in stagesOf(row)" :key="stage.key" class="stage-item">
                  <el-tag size="small" :type="stage.kindType" effect="plain">{{ stage.kind }}</el-tag>
                  <span class="stage-desc">{{ stage.desc }}</span>
                  <el-tag size="small" :type="stage.statusType">{{ stage.status }}</el-tag>
                  <span class="stage-time">{{ fmtTime(stage.time) }}</span>
                  <span v-if="stage.message" class="stage-msg">{{ stage.message }}</span>
                </div>
                <div v-if="!stagesOf(row).length" class="muted">
                  无内部执行记录（行为类任务原地执行，未派发导航/充电）
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="task_id" label="任务 ID" min-width="230" show-overflow-tooltip />
          <el-table-column label="模板 / 类型" width="150">
            <template #default="{ row }">
              <div>{{ row.task_template_type || '—' }}</div>
              <div class="sub">{{ row.task_type || '—' }}{{ row.target_label ? ' → ' + row.target_label : '' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="执行阶段" width="180">
            <template #default="{ row }">
              <template v-if="stageSummary(row).length">
                <el-tag
                  v-for="(kind, i) in stageSummary(row)"
                  :key="i"
                  size="small"
                  effect="plain"
                  type="info"
                >{{ kind }}<span v-if="i < stageSummary(row).length - 1" class="arrow"> →</span></el-tag>
              </template>
              <span v-else class="sub">—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }">
              <el-tag size="small" :type="rmsStatusType(row.status)">{{ row.status }}</el-tag>
              <span class="sub">code={{ row.action_status_code }}</span>
            </template>
          </el-table-column>
          <el-table-column label="目标点" width="140">
            <template #default="{ row }">
              <span v-if="row.target_label">{{ row.target_label }}</span>
              <span v-else-if="row.dest_x != null">({{ fmtNum(row.dest_x, 2) }}, {{ fmtNum(row.dest_y, 2) }})</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="结果 / 原因" min-width="200" show-overflow-tooltip>
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

      <!-- 本机任务：页面上手动发起的移动/充电/救援充电；充电任务聚合其内部导航（展开查看路线） -->
      <el-tab-pane :label="`本机任务（${localTasks.length}）`" name="local">
        <el-table :data="localTasks" size="small" border stripe row-key="key" @expand-change="onExpandRow">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="stage-list">
                <template v-if="row.moves">
                  <div v-for="m in row.moves" :key="m.id" class="stage-item">
                    <el-tag size="small" :type="stageStatusType(m.status ?? '')" effect="plain">{{ m.status }}</el-tag>
                    <span class="stage-desc">{{ moveDesc(m) }}</span>
                    <span class="stage-time">{{ fmtTime(m.created_at) }} ~ {{ m.finished_at ? fmtTime(m.finished_at) : '…' }}</span>
                    <span v-if="m.error_message" class="stage-msg">{{ m.error_message }}</span>
                    <template v-if="segmentCache[m.id]?.length">
                      <span
                        v-for="seg in segmentCache[m.id]"
                        :key="seg.id"
                        class="seg-chip"
                        :class="segStatusOf(seg.status)"
                      >{{ seg.source_code || '接入' }}→{{ seg.target_code }}·{{ segLabelOf(seg.status) }}</span>
                    </template>
                  </div>
                </template>
                <template v-else-if="row.innerMoves?.length">
                  <div v-for="m in row.innerMoves" :key="m.id" class="stage-item">
                    <el-tag size="small" type="warning" effect="plain">内部导航</el-tag>
                    <span class="stage-desc">{{ moveDesc(m) }}</span>
                    <el-tag size="small" :type="stageStatusType(m.status ?? '')">{{ m.status }}</el-tag>
                    <span class="stage-time">{{ fmtTime(m.created_at) }}</span>
                    <span v-if="m.error_message" class="stage-msg">{{ m.error_message }}</span>
                  </div>
                </template>
                <div v-else class="muted">无内部导航（原地充电/直达）</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.kindType" effect="plain">{{ row.kind }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="编号" width="80">
            <template #default="{ row }">{{ row.ref }}</template>
          </el-table-column>
          <el-table-column label="内容" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.desc }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="row.statusType">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="结果 / 进度" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.message || '—' }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.time) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="80">
            <template #default="{ row }">
              <span v-if="row.moves?.length">{{ fmtDuration({ created_at: row.moves[0].created_at, finished_at: row.moves[row.moves.length - 1].finished_at }) }}</span>
              <span v-else-if="row.charge">{{ fmtDuration({ created_at: row.charge.created_at, finished_at: row.charge.finished_at }) }}</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button v-if="localMoveActive(row)" size="small" type="warning" plain @click="doCancelMove(row.moves![row.moves!.length - 1])">
                取消
              </el-button>
            </template>
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

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  padding: 4px 8px;
}

/* RMS 任务展开行：阶段执行链时间线 */
.stage-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 6px 12px;
}

.stage-item {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.stage-desc {
  font-size: 13px;
  min-width: 200px;
}

.stage-time {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-family: monospace;
}

.stage-msg {
  color: var(--el-color-danger);
  font-size: 12px;
}

.arrow {
  margin-left: 2px;
}

/* 行程组展开：任务行内的段级 chip */
.seg-chip {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 12px;
  border: 1px solid var(--el-border-color-light);
  color: var(--el-text-color-secondary);
}

.seg-chip.done {
  color: var(--el-color-success);
  border-color: var(--el-color-success-light-7);
}

.seg-chip.active {
  color: var(--el-color-warning);
  border-color: var(--el-color-warning-light-7);
}

.seg-chip.failed {
  color: var(--el-color-danger);
  border-color: var(--el-color-danger-light-7);
}
</style>
