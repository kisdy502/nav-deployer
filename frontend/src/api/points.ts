import { del, get, post, put } from './http'
import type { NavPointCreateDTO, NavPointUpdateDTO, NavPointVO } from '@/types/api'

// 注意：查询参数为 camelCase 的 mapId（后端 @RequestParam），响应体为 snake_case
export const listPoints = (mapId: number) => get<NavPointVO[]>('/nav-points', { mapId })
export const getPoint = (id: number) => get<NavPointVO>(`/nav-points/${id}`)
export const createPoint = (dto: NavPointCreateDTO) => post<NavPointVO>('/nav-points', dto)
export const createPointFromCurrentPose = (dto: {
  map_id: number
  point_code: string
  point_type?: string
  remark?: string
}) => post<NavPointVO>('/nav-points/from-current-pose', dto)
export const updatePoint = (id: number, dto: NavPointUpdateDTO) => put<NavPointVO>(`/nav-points/${id}`, dto)
export const deletePoint = (id: number) => del<void>(`/nav-points/${id}`)
