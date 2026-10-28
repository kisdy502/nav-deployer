import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { ElMessage } from 'element-plus'
import { cancelMoveTask, createMoveTask, getActiveMoveTask, getCurrentMapTask } from '@/api/tasks'
import { cancelCharge, getActiveCharge, rescueCharge, startCharge } from '@/api/charge'
import { MAP_TASK_STATUS_TAG, MAP_TASK_TYPE_LABEL } from '@/utils/format'
import { useEditorStore } from './editor'
import { useMapListStore } from './mapList'
import type { ChargeTaskVO, MapModeTaskVO, MapSyncEvent, MoveTaskCreateDTO, MoveTaskVO } from '@/types/api'

const TERMINAL_MOVE: MoveTaskVO['status'][] = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT']
const TERMINAL_CHARGE: ChargeTaskVO['status'][] = ['SUCCEEDED', 'FAILED', 'CANCELLED']

export const useTasksStore = defineStore('tasks', () => {
  const currentMapTask = ref<MapModeTaskVO | null>(null)
  const recentMapTasks = ref<MapModeTaskVO[]>([])
  const activeMoveTask = ref<MoveTaskVO | null>(null)
  const chargeTask = ref<ChargeTaskVO | null>(null)
  const lastMapSync = ref<MapSyncEvent | null>(null)
  // 充电内部的导航任务由充电通知统一反馈，避免 task / charge 两路 SSE 重复弹窗。
  const chargeMoveTaskIds = new Set<number>()
  const notifiedChargeTaskIds = new Set<number>()

  function rememberId(ids: Set<number>, id: number) {
    ids.add(id)
    if (ids.size > 200) ids.delete(ids.values().next().value!)
  }

  function updateChargeTask(t: ChargeTaskVO | null) {
    chargeTask.value = t
    if (t?.internal_move_task_id != null) rememberId(chargeMoveTaskIds, t.internal_move_task_id)
  }

  const mapTaskInFlight = computed(
    () => currentMapTask.value !== null && (currentMapTask.value.status === 'DISPATCHED' || currentMapTask.value.status === 'RELOCATING'),
  )
  const moveTaskInFlight = computed(
    () => activeMoveTask.value !== null && !TERMINAL_MOVE.includes(activeMoveTask.value.status),
  )
  const chargeTaskInFlight = computed(
    () => chargeTask.value !== null && !TERMINAL_CHARGE.includes(chargeTask.value.status),
  )

  function onMoveTask(t: MoveTaskVO) {
    if (!activeMoveTask.value || t.id === activeMoveTask.value.id || !moveTaskInFlight.value) {
      activeMoveTask.value = t
    }
    if (chargeMoveTaskIds.has(t.id)) return
    if (t.status === 'SUCCEEDED') ElMessage.success(`移动任务完成（${t.task_no}）`)
    else if (t.status === 'FAILED') ElMessage.error(`移动任务失败：${t.error_message ?? '未知原因'}`)
    else if (t.status === 'TIMEOUT') ElMessage.error(`移动任务超时（${t.task_no}）`)
  }

  /** 充电任务 SSE 事件（后端 ChargeService 状态机推送） */
  function onChargeTask(t: ChargeTaskVO) {
    updateChargeTask(t)
    if (!TERMINAL_CHARGE.includes(t.status) || notifiedChargeTaskIds.has(t.id)) return
    rememberId(notifiedChargeTaskIds, t.id)
    if (t.status === 'SUCCEEDED') ElMessage.success(`充电完成：${t.message ?? '已达到目标电量'}`)
    else if (t.status === 'FAILED') ElMessage.error(`充电任务失败：${t.message ?? '未知原因'}`)
    else if (t.status === 'CANCELLED') ElMessage.info(`充电已取消：${t.message ?? ''}`)
  }

  /** 去充电（地图充电点右键入口）：导航到桩 → 开始充电 → 充到目标电量 */
  async function goCharge(waypointId: number, targetBatteryPct?: number) {
    updateChargeTask(await startCharge(
      targetBatteryPct != null ? { waypoint_id: waypointId, target_battery_pct: targetBatteryPct } : { waypoint_id: waypointId },
    ))
    ElMessage.success('充电任务已下发')
    return chargeTask.value
  }

  /** 救援充电：电量耗尽机器人拒绝移动、无法导航到桩的死锁场景，远程直接开始充电 */
  async function rescueChargeTask() {
    updateChargeTask(await rescueCharge())
    ElMessage.success('救援充电已开始（远程充电，无需在桩上）')
    return chargeTask.value
  }

  async function cancelChargeTask() {
    if (!chargeTask.value || !chargeTaskInFlight.value) return
    updateChargeTask(await cancelCharge())
  }

  function onMapTask(t: MapModeTaskVO) {
    if (currentMapTask.value && t.id === currentMapTask.value.id) {
      currentMapTask.value = t
    } else if (!currentMapTask.value || t.updated_at >= (currentMapTask.value.updated_at ?? '')) {
      currentMapTask.value = t
    }
    const idx = recentMapTasks.value.findIndex((x) => x.id === t.id)
    if (idx >= 0) recentMapTasks.value.splice(idx, 1)
    recentMapTasks.value.unshift(t)
    if (recentMapTasks.value.length > 50) recentMapTasks.value.pop()

    if (t.status === 'SUCCEEDED' || t.status === 'FAILED') {
      const label = MAP_TASK_TYPE_LABEL[t.type]
      if (t.status === 'SUCCEEDED') ElMessage.success(`${label}成功`)
      else ElMessage.error(`${label}失败：${t.error_message ?? '未知原因'}`)
      // 切图成功：激活状态变了，刷新地图列表让徽标同步
      if (t.status === 'SUCCEEDED' && t.type === 'SWITCH_MAP') {
        void useMapListStore().load()
      }
    }
  }

  function onMapSync(e: MapSyncEvent) {
    lastMapSync.value = e
    if (e.success) {
      ElMessage.success(`机器人地图已入库并激活（nav_map_id=${e.navMapId ?? '-'}）`)
      // 界面同步：左侧列表刷新（新图变 ACTIVE）+ 编辑器加载新激活地图。
      // 必须先停实时预览（不回灌旧图），否则 1s 轮询会把 loadMap 的结果再覆盖回实时帧
      void useMapListStore().load()
      if (e.navMapId) {
        const editor = useEditorStore()
        editor.stopLivePolling(false)
        editor.loadMap(e.navMapId).catch(() => {})
      }
    } else {
      ElMessage.error(`机器人地图入库失败：${e.message ?? '未知原因'}`)
    }
  }

  async function refresh() {
    getCurrentMapTask()
      .then((t) => (currentMapTask.value = t))
      .catch(() => {})
    getActiveMoveTask()
      .then((t) => {
        if (!moveTaskInFlight.value || t) activeMoveTask.value = t
      })
      .catch(() => {})
    getActiveCharge()
      .then((t) => {
        updateChargeTask(t)
      })
      .catch(() => {})
  }

  async function navigateToPoint(pointId: number, maxSpeed?: number) {
    const dto: MoveTaskCreateDTO = { task_type: 'TO_POINT', point_id: pointId }
    if (maxSpeed) dto.max_speed = maxSpeed
    activeMoveTask.value = await createMoveTask(dto)
    ElMessage.success('导航任务已下发')
    return activeMoveTask.value
  }

  /** 按位姿导航（v2 点位等无 v1 point_id 的场景）：GOAL + x/y/theta。 */
  async function navigateToPose(x: number, y: number, theta: number, maxSpeed?: number) {
    const dto: MoveTaskCreateDTO = { task_type: 'GOAL', x, y, theta }
    if (maxSpeed) dto.max_speed = maxSpeed
    activeMoveTask.value = await createMoveTask(dto)
    ElMessage.success('导航任务已下发')
    return activeMoveTask.value
  }

  /** 自由到点：机器人自主规划路径到目标（edge_type=FREE，走 Nav2 planner） */
  async function freeNavigate(x: number, y: number, theta = 0, maxSpeed?: number) {
    const dto: MoveTaskCreateDTO = { task_type: 'FREE', x, y, theta }
    if (maxSpeed) dto.max_speed = maxSpeed
    activeMoveTask.value = await createMoveTask(dto)
    ElMessage.success('自由导航任务已下发')
    return activeMoveTask.value
  }

  async function followPath(pathId: number, maxSpeed?: number) {
    const dto: MoveTaskCreateDTO = { task_type: 'FOLLOW_PATH', path_id: pathId }
    if (maxSpeed) dto.max_speed = maxSpeed
    activeMoveTask.value = await createMoveTask(dto)
    ElMessage.success('循线任务已下发')
    return activeMoveTask.value
  }

  async function cancelActive() {
    if (!activeMoveTask.value || !moveTaskInFlight.value) return
    activeMoveTask.value = await cancelMoveTask(activeMoveTask.value.id)
  }

  return {
    currentMapTask,
    recentMapTasks,
    activeMoveTask,
    chargeTask,
    lastMapSync,
    mapTaskInFlight,
    moveTaskInFlight,
    chargeTaskInFlight,
    onMoveTask,
    onChargeTask,
    onMapTask,
    onMapSync,
    refresh,
    navigateToPoint,
    navigateToPose,
    freeNavigate,
    followPath,
    goCharge,
    rescueChargeTask,
    cancelChargeTask,
    cancelActive,
  }
})

export const mapTaskStatusLabel = (t: MapModeTaskVO) => MAP_TASK_STATUS_TAG[t.status]?.label ?? t.status
