import { del, get, post, put } from './http'
import type { MapGridVO, NavMapVO } from '@/types/api'

export const listMaps = () => get<NavMapVO[]>('/nav-maps')
export const getMap = (id: number) => get<NavMapVO>(`/nav-maps/${id}`)
/** 存储栅格：裸 JSON，无包裹 */
export const getMapGrid = (id: number) => get<MapGridVO>(`/nav-maps/${id}/data`)
export const getLiveGrid = () => get<MapGridVO>('/nav-maps/live')
export const saveLiveMap = (map_name: string) => post<NavMapVO>('/nav-maps', { map_name })
export const renameMap = (id: number, map_name: string) => put<NavMapVO>(`/nav-maps/${id}`, { map_name })
export const setRobotMapName = (id: number, robot_map_name: string) =>
  put<NavMapVO>(`/nav-maps/${id}/robot-map-name`, { robot_map_name })
/** 切换机器人地图（异步，终态经 SSE map-task 推送） */
export const switchMap = (id: number, robot_map_name?: string) =>
  post<object>(`/nav-maps/${id}/switch`, robot_map_name ? { robot_map_name } : {})
export const activateMap = (id: number) => post<NavMapVO>(`/nav-maps/${id}/activate`)
export const deleteMap = (id: number) => del<void>(`/nav-maps/${id}`)
