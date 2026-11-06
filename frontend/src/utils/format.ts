import dayjs from 'dayjs'
import type {
  MapTaskStatus,
  MapTaskType,
  MoveTaskStatus,
  MoveTaskType,
} from '@/types/api'

export const fmtTime = (s: string | null | undefined) => (s ? dayjs(s).format('YYYY-MM-DD HH:mm:ss') : '-')

/** 浮点智能显示：去掉二进制精度毛刺（0.05000000000000123 → 0.05），最多保留 max 位小数；无效值返回 '-' */
export const fmtNum = (v: number | null | undefined, max = 4): string => {
  if (v == null || !Number.isFinite(v)) return '-'
  return String(+v.toFixed(max))
}

/** 按前缀自动生成图内唯一编码：prefix1、prefix2…（跳过已存在的编号） */
export const nextAutoCode = (existingCodes: string[], prefix: string): string => {
  const existing = new Set(existingCodes)
  let i = 1
  while (existing.has(`${prefix}${i}`)) i++
  return `${prefix}${i}`
}

export const POINT_TYPE_LABEL: Record<string, string> = {
  path: '路径点',
  charge: '充电点',
  pickup: '取货点',
  dropoff: '卸货点',
  storage: '暂存点',
  elevator: '电梯口',
  stairs: '楼梯口',
  ramp: '坡道口',
  relocation: '重定位点',
  parking: '归巢点',
  workstation: '作业点',
  dock: '对接点',
  roller: '滚筒点',
}

export const MAP_TASK_TYPE_LABEL: Record<MapTaskType, string> = {
  SWITCH_MAP: '切换地图',
  START_MAPPING: '开始建图',
  SAVE_MAP: '保存地图',
  DISCARD_MAPPING: '放弃建图',
  RELOCALIZE: '重定位',
}

/** 机器人侧定位操作阶段文案（/agv/localization_operation.stage） */
export const LOC_STAGE_LABEL: Record<string, string> = {
  ACCEPTED: '已受理',
  STOPPING_LOCALIZATION: '停止旧定位',
  STARTING_LOCALIZATION: '启动定位',
  WAITING_LOCALIZATION: '等待定位收敛',
  FINISHED: '已完成',
}

export const locStageText = (stage: string | null | undefined) =>
  stage ? LOC_STAGE_LABEL[stage] ?? stage : ''

export const MAP_TASK_STATUS_TAG: Record<MapTaskStatus, { label: string; type: 'success' | 'info' | 'warning' | 'danger' }> = {
  DISPATCHED: { label: '已下发', type: 'info' },
  RELOCATING: { label: '重定位中', type: 'warning' },
  SUCCEEDED: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
}

export const MOVE_TASK_TYPE_LABEL: Record<MoveTaskType, string> = {
  TO_POINT: '去点位',
  TO_WAYPOINT: '去点位',
  GOAL: '去目标点',
  FOLLOW_PATH: '循线',
  FREE: '自由到点',
}

export const MOVE_TASK_STATUS_TAG: Record<MoveTaskStatus, { label: string; type: 'success' | 'info' | 'warning' | 'danger' }> = {
  CREATED: { label: '已创建', type: 'info' },
  DISPATCHED: { label: '已下发', type: 'info' },
  EXECUTING: { label: '执行中', type: 'warning' },
  SUCCEEDED: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  CANCELLED: { label: '已取消', type: 'info' },
  TIMEOUT: { label: '超时', type: 'danger' },
}
