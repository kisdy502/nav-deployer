import { get, post } from './http'
import type { MapModeTaskVO, MoveTaskCreateDTO, MoveTaskVO } from '@/types/api'

export const getCurrentMapTask = () => get<MapModeTaskVO | null>('/map-mode-tasks/current')
export const getRecentMapTasks = (limit = 20) => get<MapModeTaskVO[]>('/map-mode-tasks/recent', { limit })
export const startMapping = () => post<MapModeTaskVO>('/map-mode-tasks/start-mapping')
export const saveMapTask = (map_name: string) => post<MapModeTaskVO>('/map-mode-tasks/save-map', { map_name })

export const createMoveTask = (dto: MoveTaskCreateDTO) => post<MoveTaskVO>('/move-tasks', dto)
export const cancelMoveTask = (id: number) => post<MoveTaskVO>(`/move-tasks/${id}/cancel`)
export const getActiveMoveTask = () => get<MoveTaskVO | null>('/move-tasks/active')
