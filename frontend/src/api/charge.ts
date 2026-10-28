import { get, post } from './http'
import type { ChargeStartDTO, ChargeTaskVO } from '@/types/api'

/** 发起充电：导航到充电点 → 开始充电 → 充到目标电量（进度经 SSE charge 事件推送） */
export const startCharge = (dto: ChargeStartDTO) => post<ChargeTaskVO>('/charge/start', dto)
export const getActiveCharge = () => get<ChargeTaskVO | null>('/charge/active')
export const cancelCharge = () => post<ChargeTaskVO>('/charge/cancel')
/** 救援充电：跳过导航远程开始充电（电量耗尽无法移动到桩的死锁场景） */
export const rescueCharge = () => post<ChargeTaskVO>('/charge/rescue')
/** 充电任务历史（最新在前，含进行中置顶） */
export const listChargeTasks = (limit = 50) => get<ChargeTaskVO[]>('/charge/tasks', { limit })
