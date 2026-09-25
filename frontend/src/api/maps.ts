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

/** 机器人侧现有地图列表（maps_dir 下 pgm+yaml 齐全的名字） */
export const listRobotMaps = () => get<string[]>('/nav-maps/robot-maps')
/** 从机器人导入地图入库（保存任务报失败但机器人实际已存图的补救通道） */
export const importFromRobot = (robot_map_name: string) =>
  post<NavMapVO>('/nav-maps/import-from-robot', { robot_map_name })
