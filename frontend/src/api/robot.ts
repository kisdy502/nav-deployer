import { get, post } from './http'
import type { MapModeTaskVO, TelemetrySnapshot } from '@/types/api'

export const getRobotSnapshot = () => get<TelemetrySnapshot>('/robot/snapshot')
export const robotControl = (action: 'start' | 'stop' | 'reset') =>
  post<{ success: boolean; message: string; state: string }>('/robot/control', { action })
/** 重定位：受理即返回任务，终态（成功/失败/超时原因）经 SSE map-task 事件推送 */
export const setInitialPose = (x: number, y: number, theta: number, mapName?: string) =>
  post<MapModeTaskVO>('/robot/initial-pose', { x, y, theta, map_name: mapName ?? null })

export const sendTeleop = (linearX: number, linearY: number, angularZ: number) =>
  post<void>('/robot/teleop', { linear_x: linearX, linear_y: linearY, angular_z: angularZ })

/** 机械臂运动（转发 /agv/arm_move）：arm=left/right，joints 为目标关节角（弧度，顺序=/joint_states 关节名排序），异步受理 */
export const armMove = (arm: string, joints: number[], durationSec?: number) =>
  post<{ success: boolean; message: string }>('/robot/arm-move', {
    arm,
    joints,
    duration_sec: durationSec ?? null,
  })

/** 设置机械臂归位（联锁基准）：home 为角色顺序 [pan,lift,elbow,wrist1,wrist2,wrist3] 弧度 */
export const setArmHome = (home: number[]) =>
  post<{ success: boolean; message: string }>('/robot/arm-home', { home })

// ---------- 运营状态（停用/恢复） ----------

/** 地图一致性（机器人当前加载地图 vs 上位机导航基准图）；state: ALIGNED / MISMATCH / UNKNOWN */
export interface MapAlignStatus {
  state: 'ALIGNED' | 'MISMATCH' | 'UNKNOWN'
  robot_map_name: string
  ref_map_name: string
  ref_source: string
  message: string
}

export interface OperationalStatus {
  decommissioned: boolean
  reason: string
  map_align?: MapAlignStatus
}

export const getOperational = () => get<OperationalStatus>('/robot/operational')
export const decommission = (reason?: string) => post<OperationalStatus>('/robot/decommission', { reason })
export const recommission = () => post<OperationalStatus>('/robot/recommission')
