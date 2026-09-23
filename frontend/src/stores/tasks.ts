import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { ElMessage } from 'element-plus'
import { cancelMoveTask, createMoveTask, getActiveMoveTask, getCurrentMapTask } from '@/api/tasks'
import { MAP_TASK_STATUS_TAG, MAP_TASK_TYPE_LABEL } from '@/utils/format'
import type { MapModeTaskVO, MapSyncEvent, MoveTaskCreateDTO, MoveTaskVO } from '@/types/api'

const TERMINAL_MOVE: MoveTaskVO['status'][] = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT']

export const useTasksStore = defineStore('tasks', () => {
  const currentMapTask = ref<MapModeTaskVO | null>(null)
  const recentMapTasks = ref<MapModeTaskVO[]>([])
  const activeMoveTask = ref<MoveTaskVO | null>(null)
  const lastMapSync = ref<MapSyncEvent | null>(null)

  const mapTaskInFlight = computed(
    () => currentMapTask.value !== null && (currentMapTask.value.status === 'DISPATCHED' || currentMapTask.value.status === 'RELOCATING'),
  )
  const moveTaskInFlight = computed(
    () => activeMoveTask.value !== null && !TERMINAL_MOVE.includes(activeMoveTask.value.status),
  )

  function onMoveTask(t: MoveTaskVO) {
    if (!activeMoveTask.value || t.id === activeMoveTask.value.id || !moveTaskInFlight.value) {
      activeMoveTask.value = t
    }
    if (t.status === 'SUCCEEDED') ElMessage.success(`移动任务完成（${t.task_no}）`)
    else if (t.status === 'FAILED') ElMessage.error(`移动任务失败：${t.error_message ?? '未知原因'}`)
    else if (t.status === 'TIMEOUT') ElMessage.error(`移动任务超时（${t.task_no}）`)
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
    }
  }

  function onMapSync(e: MapSyncEvent) {
    lastMapSync.value = e
    if (e.success) ElMessage.success(`机器人地图已入库并激活（nav_map_id=${e.navMapId ?? '-'}）`)
    else ElMessage.error(`机器人地图入库失败：${e.message ?? '未知原因'}`)
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
  }

  async function navigateToPoint(pointId: number, maxSpeed?: number) {
    const dto: MoveTaskCreateDTO = { task_type: 'TO_POINT', point_id: pointId }
    if (maxSpeed) dto.max_speed = maxSpeed
    activeMoveTask.value = await createMoveTask(dto)
    ElMessage.success('导航任务已下发')
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
    lastMapSync,
    mapTaskInFlight,
    moveTaskInFlight,
    onMoveTask,
    onMapTask,
    onMapSync,
    refresh,
    navigateToPoint,
    followPath,
    cancelActive,
  }
})

export const mapTaskStatusLabel = (t: MapModeTaskVO) => MAP_TASK_STATUS_TAG[t.status]?.label ?? t.status
