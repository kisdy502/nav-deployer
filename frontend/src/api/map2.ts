import { del, get, post, put } from './http2'
import http2 from './http2'
import type {
  ConnSaveDTO,
  ConnV2VO,
  FloorSaveDTO,
  FloorVO,
  MapFullVO,
  MapV2VO,
  PathSaveDTO,
  PathV2VO,
  ShelfSaveDTO,
  ShelfV2VO,
  WaypointSaveDTO,
  WaypointV2VO,
  ZoneSaveDTO,
  ZoneV2VO,
} from '@/types/map2'
import type { MapGridVO } from '@/types/api'

// ---------- 地图 ----------

export const listMapsV2 = () => get<MapV2VO[]>('/maps')
export const getMapV2 = (id: number) => get<MapV2VO>(`/maps/${id}`)
export const getMapFullV2 = (id: number) => get<MapFullVO>(`/maps/${id}/full`)
/** 栅格：裸 JSON，无包裹 */
export const getMapGridV2 = (id: number) => get<MapGridVO>(`/maps/${id}/grid`)
export const createMapFromLiveV2 = (map_name: string, modified_by?: string) =>
  post<MapV2VO>('/maps/from-live', { map_name, modified_by })
export const updateMapV2 = (id: number, dto: { map_name?: string; robot_map_name?: string; modified_by?: string }) =>
  put<MapV2VO>(`/maps/${id}`, dto)
export const publishMapV2 = (id: number, modified_by?: string) =>
  post<MapV2VO>(`/maps/${id}/publish`, modified_by ? { modified_by } : {})
export const deleteMapV2 = (id: number) => del<void>(`/maps/${id}`)
export const replaceGridFromLiveV2 = (id: number, modified_by?: string) =>
  post<MapV2VO>(`/maps/${id}/grid-from-live`, modified_by ? { modified_by } : {})
export const migrateLegacyMapV2 = (navMapId: number, dto: { map_name?: string; modified_by?: string } = {}) =>
  post<MapV2VO>(`/maps/migrate/${navMapId}`, dto)

/** 导出新格式 zip（map_info.json + global_map.pgm/png/yaml），浏览器直接下载。
 *  注意：http2 拦截器已把响应解包，blob 场景下返回值即 Blob 本身。 */
export async function downloadMapPackageV2(id: number, fileName: string) {
  const blob = (await http2.get(`/maps/${id}/package`, { responseType: 'blob' })) as unknown as Blob
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName.endsWith('.zip') ? fileName : `${fileName}.zip`
  link.click()
  URL.revokeObjectURL(url)
}

/** 导入新格式 zip（multipart） */
export function importMapZipV2(file: File, mapName?: string, modifiedBy?: string) {
  const form = new FormData()
  form.append('file', file)
  if (mapName) form.append('map_name', mapName)
  if (modifiedBy) form.append('modified_by', modifiedBy)
  return post<MapV2VO>('/maps/import-zip', form)
}

// ---------- 楼层 ----------

export const listFloorsV2 = (mapId: number) => get<FloorVO[]>('/map-floors', { mapId })
export const createFloorV2 = (dto: FloorSaveDTO) => post<FloorVO>('/map-floors', dto)
export const updateFloorV2 = (id: number, dto: FloorSaveDTO) => put<FloorVO>(`/map-floors/${id}`, dto)
export const deleteFloorV2 = (id: number) => del<void>(`/map-floors/${id}`)

// ---------- 点位 ----------

export const createWaypointV2 = (dto: WaypointSaveDTO) => post<WaypointV2VO>('/map-waypoints', dto)
export const updateWaypointV2 = (id: number, dto: WaypointSaveDTO) =>
  put<WaypointV2VO>(`/map-waypoints/${id}`, dto)
export const deleteWaypointV2 = (id: number) => del<void>(`/map-waypoints/${id}`)

// ---------- 区域 ----------

export const createZoneV2 = (dto: ZoneSaveDTO) => post<ZoneV2VO>('/map-zones', dto)
export const updateZoneV2 = (id: number, dto: ZoneSaveDTO) => put<ZoneV2VO>(`/map-zones/${id}`, dto)
export const deleteZoneV2 = (id: number) => del<void>(`/map-zones/${id}`)

// ---------- 货架 ----------

export const createShelfV2 = (dto: ShelfSaveDTO) => post<ShelfV2VO>('/map-shelves', dto)
export const updateShelfV2 = (id: number, dto: ShelfSaveDTO) => put<ShelfV2VO>(`/map-shelves/${id}`, dto)
export const deleteShelfV2 = (id: number) => del<void>(`/map-shelves/${id}`)

// ---------- 路线 ----------

export const createPathV2 = (dto: PathSaveDTO) => post<PathV2VO>('/map-paths', dto)
export const updatePathV2 = (id: number, dto: PathSaveDTO) => put<PathV2VO>(`/map-paths/${id}`, dto)
export const deletePathV2 = (id: number) => del<void>(`/map-paths/${id}`)

// ---------- 楼层间连接 ----------

export const listConnsV2 = (mapId: number) => get<ConnV2VO[]>('/map-inter-floor-conns', { mapId })
export const createConnV2 = (dto: ConnSaveDTO) => post<ConnV2VO>('/map-inter-floor-conns', dto)
export const updateConnV2 = (id: number, dto: ConnSaveDTO) =>
  put<ConnV2VO>(`/map-inter-floor-conns/${id}`, dto)
export const deleteConnV2 = (id: number) => del<void>(`/map-inter-floor-conns/${id}`)

// ---------- 路径规划 ----------

export interface RouteStepV2 {
  path_id: number
  path_code: string
  from_waypoint_id: number
  to_waypoint_id: number
  from_waypoint_code: string
  to_waypoint_code: string
  floor_id: number | null
  is_cross_floor: boolean
  conn_type: string | null
  cost: number
}

export interface RoutePlanV2 {
  steps: RouteStepV2[]
  total_cost: number
  cross_floor_count: number
  found: boolean
}

/** 服务端路径规划：Dijkstra 加权最短路径（禁行区/减速区/跨楼层） */
export const planRouteV2 = (mapId: number, fromWaypointId: number, toWaypointId: number) =>
  get<RoutePlanV2>('/route/plan', { mapId, fromWaypointId, toWaypointId })
