import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { connectAgvSse } from '@/sse/agv-sse'
import { getRobotSnapshot, robotControl as robotControlApi, sendTeleop, setInitialPose as setInitialPoseApi, setArmHome } from '@/api/robot'
import { useTasksStore } from './tasks'
import { hasArmHomeOverride, armHome, ARM_ROLE_ORDER } from '@/utils/armJoints'
import type { ScanCloud, TelemetrySnapshot } from '@/types/api'

const TRAIL_MAX = 2000

export const useRobotStore = defineStore('robot', () => {
  const connStatus = ref<'connecting' | 'open' | 'closed'>('connecting')
  // 高频整帧替换的数据一律 shallowRef：ref() 的深代理会为 tf_frames/joints/points 数组
  // 的每个元素建 Proxy（10Hz × 数百对象/帧），GC 压力直接把主线程拖卡
  const snapshot = shallowRef<TelemetrySnapshot | null>(null)
  const trail = shallowRef<{ x: number; y: number }[]>([])
  const scan1Cloud = shallowRef<ScanCloud | null>(null)
  const scan2Cloud = shallowRef<ScanCloud | null>(null)
  const started = ref(false)

  const pose = computed(() => snapshot.value?.pose ?? null)
  const velocity = computed(() => snapshot.value?.velocity ?? null)
  const joints = computed(() => snapshot.value?.joints ?? null)
  const tfFrames = computed(() => snapshot.value?.tf_frames ?? null)
  const status = computed(() => snapshot.value?.status ?? null)
  const battery = computed(() => status.value?.battery ?? null)
  /** 机器人上报充电中（battery_simulator / BMS）；null = 旧版机器人未携带 */
  const charging = computed(() => status.value?.charging ?? null)

  function applySnapshot(t: TelemetrySnapshot) {
    const p = t.pose
    if (p) {
      const last = trail.value[trail.value.length - 1]
      if (!last || Math.hypot(last.x - p.x, last.y - p.y) > 0.02) {
        // 复制式更新：shallowRef 换新数组引用触发订阅（无需 deep watch）
        const next = trail.value.concat({ x: p.x, y: p.y })
        if (next.length > TRAIL_MAX) next.splice(0, next.length - TRAIL_MAX)
        trail.value = next
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
    // 自定义归位回推后端联锁（后端重启后 yaml 回默认，这里保证两侧基准一致）
    if (hasArmHomeOverride()) {
      setArmHome(ARM_ROLE_ORDER.map((r) => armHome[r])).catch(() => {})
    }
    connectAgvSse({
      onStatus: (s) => (connStatus.value = s),
      onTelemetry: (t) => applySnapshot(t),
      onScan: (t) => {
        // 后端在任一雷达更新时会把双雷达当前快照一起放进事件。按捕获时间去重，
        // 避免前后雷达异步到帧时反复触发未变化那一侧的 GPU Buffer 重写。
        if (!t.scan1) {
          scan1Cloud.value = null
        } else if (!scan1Cloud.value || t.scan1.stamp_sec == null
          || scan1Cloud.value.stamp_sec !== t.scan1.stamp_sec) {
          scan1Cloud.value = t.scan1
        }
        if (!t.scan2) {
          scan2Cloud.value = null
        } else if (!scan2Cloud.value || t.scan2.stamp_sec == null
          || scan2Cloud.value.stamp_sec !== t.scan2.stamp_sec) {
          scan2Cloud.value = t.scan2
        }
      },
      onMoveTask: (t) => useTasksStore().onMoveTask(t),
      onChargeTask: (t) => useTasksStore().onChargeTask(t),
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

  function teleop(linearX: number, linearY: number, angularZ: number) {
    return sendTeleop(linearX, linearY, angularZ)
  }

  function clearTrail() {
    trail.value = []
  }

  return {
    connStatus,
    snapshot,
    trail,
    scan1Cloud,
    scan2Cloud,
    pose,
    velocity,
    joints,
    tfFrames,
    status,
    battery,
    charging,
    start,
    control,
    setInitialPose,
    teleop,
    clearTrail,
  }
})
