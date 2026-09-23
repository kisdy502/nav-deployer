import { del, get, post, put } from './http'
import type { NavPathCreateDTO, NavPathUpdateDTO, NavPathVO, PathEdgeDTO } from '@/types/api'

// 列表不返回 edges；PUT edges 会把状态重置为 DRAFT
export const listPaths = (mapId: number) => get<NavPathVO[]>('/nav-paths', { mapId })
export const getPath = (id: number) => get<NavPathVO>(`/nav-paths/${id}`)
export const createPath = (dto: NavPathCreateDTO) => post<NavPathVO>('/nav-paths', dto)
export const updatePath = (id: number, dto: NavPathUpdateDTO) => put<NavPathVO>(`/nav-paths/${id}`, dto)
export const putPathEdges = (id: number, edges: PathEdgeDTO[]) =>
  put<NavPathVO>(`/nav-paths/${id}/edges`, { edges })
export const deployPath = (id: number) => post<NavPathVO>(`/nav-paths/${id}/deploy`)
export const deletePath = (id: number) => del<void>(`/nav-paths/${id}`)
