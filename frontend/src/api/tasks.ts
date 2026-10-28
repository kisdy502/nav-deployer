import { get, post } from './http'
import type { MapModeTaskVO, MoveTaskCreateDTO, MoveTaskVO, RmsTaskVO } from '@/types/api'

export const getCurrentMapTask = () => get<MapModeTaskVO | null>('/map-mode-tasks/current')
export const getRecentMapTasks = (limit = 20) => get<MapModeTaskVO[]>('/map-mode-tasks/recent', { limit })
export const startMapping = () => post<MapModeTaskVO>('/map-mode-tasks/start-mapping')
export const saveMapTask = (map_name: string) => post<MapModeTaskVO>('/map-mode-tasks/save-map', { map_name })
/** 放弃建图：不保存，机器人退出建图并回载建图前地图恢复定位 */
export const discardMapping = () => post<MapModeTaskVO>('/map-mode-tasks/discard-mapping')

export const createMoveTask = (dto: MoveTaskCreateDTO) => post<MoveTaskVO>('/move-tasks', dto)
export const cancelMoveTask = (id: number) => post<MoveTaskVO>(`/move-tasks/${id}/cancel`)
export const getActiveMoveTask = () => get<MoveTaskVO | null>('/move-tasks/active')
export const listMoveTasks = (limit = 50) => get<MoveTaskVO[]>('/move-tasks', { limit })

// ---------- RMS 任务运维（任务管理页） ----------
export const getRmsActiveTask = () => get<RmsTaskVO | null>('/rms/tasks/active')
export const listRmsTasks = (limit = 50) => get<RmsTaskVO[]>('/rms/tasks', { limit })
/** 复位：非终态任务强制收口 canceled（取消内部移动/充电 + 上报 RMS + 释放 busy 闸门） */
export const resetRmsTasks = () => post<RmsTaskVO[]>('/rms/tasks/reset')
