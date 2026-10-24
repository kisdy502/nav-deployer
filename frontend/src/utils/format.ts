import dayjs from 'dayjs'
import type {
  MapTaskStatus,
  MapTaskType,
  MoveTaskStatus,
  MoveTaskType,
  NavMapStatus,
  PathStatus,
  PointType,
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

export const MAP_STATUS_TAG: Record<NavMapStatus, { label: string; type: 'success' | 'info' | 'warning' }> = {
  ACTIVE: { label: '部署中', type: 'success' },
  DRAFT: { label: '草稿', type: 'info' },
  ARCHIVED: { label: '已归档', type: 'warning' },
}

export const PATH_STATUS_TAG: Record<PathStatus, { label: string; type: 'success' | 'info' | 'warning' }> = {
  DEPLOYED: { label: '已部署', type: 'success' },
  DRAFT: { label: '草稿', type: 'info' },
  DISABLED: { label: '已停用', type: 'warning' },
}

export const POINT_TYPE_LABEL: Record<string, string> = {
  NORMAL: '普通点',
  WORK: '作业点',
  CHARGER: '充电点',
  HOME: '待命点',
  // v2 词表（11 类，与 pointStyle.ts 的 label 一致，两套 key 并存兼容 v1/v2）
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
}

export const MAP_TASK_STATUS_TAG: Record<MapTaskStatus, { label: string; type: 'success' | 'info' | 'warning' | 'danger' }> = {
  DISPATCHED: { label: '已下发', type: 'info' },
  RELOCATING: { label: '重定位中', type: 'warning' },
  SUCCEEDED: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
}

export const MOVE_TASK_TYPE_LABEL: Record<MoveTaskType, string> = {
  TO_POINT: '去点位',
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

/** 待命点使用 Home，避免与作业点 W 混淆。 */
export const POINT_CODE_PREFIX: Record<PointType, string> = {
  NORMAL: 'P', CHARGER: 'C', WORK: 'W', HOME: 'Home',
}
export const nextPointCode = (codes: string[], type: PointType): string =>
  nextAutoCode(codes, POINT_CODE_PREFIX[type])
