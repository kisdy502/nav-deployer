import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { connectAgvSse } from '@/sse/agv-sse'
import { getRobotSnapshot, robotControl as robotControlApi, setInitialPose as setInitialPoseApi } from '@/api/robot'
import { useTasksStore } from './tasks'
import type { TelemetrySnapshot } from '@/types/api'

const TRAIL_MAX = 2000

export const useRobotStore = defineStore('robot', () => {
  const connStatus = ref<'connecting' | 'open' | 'closed'>('connecting')
  const snapshot = ref<TelemetrySnapshot | null>(null)
  const trail = ref<{ x: number; y: number }[]>([])
  const started = ref(false)

  const pose = computed(() => snapshot.value?.pose ?? null)
  const status = computed(() => snapshot.value?.status ?? null)
  const battery = computed(() => status.value?.battery ?? null)

  function applySnapshot(t: TelemetrySnapshot) {
    const p = t.pose
    if (p) {
      const last = trail.value[trail.value.length - 1]
      if (!last || Math.hypot(last.x - p.x, last.y - p.y) > 0.02) {
        trail.value.push({ x: p.x, y: p.y })
        if (trail.value.length > TRAIL_MAX) trail.value.splice(0, trail.value.length - TRAIL_MAX)
      }
    }
    snapshot.value = t
  }

  /** 应用启动时调用一次：先拉一次快照兜底，再挂 SSE */
  function start() {
    if (started.value) return
    started.value = true
    getRobotSnapshot()
      .then((s) => applySnapshot(s))
      .catch(() => {})
    connectAgvSse({
      onStatus: (s) => (connStatus.value = s),
      onTelemetry: (t) => applySnapshot(t),
      onMoveTask: (t) => useTasksStore().onMoveTask(t),
      onMapTask: (t) => useTasksStore().onMapTask(t),
      onMapSync: (e) => useTasksStore().onMapSync(e),
    })
  }

  function control(action: 'start' | 'stop' | 'reset') {
    return robotControlApi(action)
  }

  function setInitialPose(x: number, y: number, theta: number) {
    return setInitialPoseApi(x, y, theta)
  }

  function clearTrail() {
    trail.value = []
  }

  return { connStatus, snapshot, trail, pose, status, battery, start, control, setInitialPose, clearTrail }
})
