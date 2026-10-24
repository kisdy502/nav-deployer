// v2 多楼层地图类型（与后端 metadata VO 逐字段镜像，全局 SNAKE_CASE）。

export type WaypointType =
  | 'path'
  | 'charge'
  | 'pickup'
  | 'dropoff'
  | 'storage'
  | 'elevator'
  | 'stairs'
  | 'ramp'
  | 'relocation'
  | 'parking'
  | 'workstation'
  | 'dock'
  | 'roller'

export type ZoneType = 'forbidden' | 'slowDown' | 'ramp'
export type ConnType = 'elevator' | 'stairs' | 'ramp'
export type MapV2Status = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
export type PathV2Status = 'DRAFT' | 'DEPLOYED' | 'DISABLED'
export type SegmentType = 'STRAIGHT' | 'CURVE'

export const WAYPOINT_TYPE_LABELS: Record<WaypointType, string> = {
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

/** 点位编码首字母（互不冲突） */
export const WAYPOINT_CODE_PREFIX: Record<WaypointType, string> = {
  path: 'P',
  charge: 'C',
  pickup: 'F',
  dropoff: 'D',
  storage: 'T',
  elevator: 'E',
  stairs: 'S',
  ramp: 'R',
  relocation: 'L',
  parking: 'H',
  workstation: 'W',
  dock: 'K',
  roller: 'G',
}

export const ZONE_TYPE_LABELS: Record<ZoneType, string> = {
  forbidden: '禁行区',
  slowDown: '减速区',
  ramp: '坡道区',
}

export const CONN_TYPE_LABELS: Record<ConnType, string> = {
  elevator: '电梯',
  stairs: '楼梯',
  ramp: '坡道',
}

export interface MapV2VO {
  id: number
  map_name: string
  coordinate_system: string
  unit: string
  version: string
  status: MapV2Status
  modified_by: string | null
  source: string
  robot_map_name: string | null
  resolution: number
  width: number
  height: number
  origin_x: number
  origin_y: number
  origin_yaw: number
  data_size: number | null
  created_at: string
  updated_at: string
}

export interface FloorVO {
  id: number
  map_id: number
  floor_code: string
  name: string
  elevation: number
  default_max_speed: number
  sort_order: number
}

export interface WaypointV2VO {
  id: number
  map_id: number
  floor_id: number
  waypoint_code: string
  name: string
  wp_type: WaypointType
  x: number
  y: number
  yaw: number
  remark: string | null
}

export interface RampInfoVO {
  slope: number
  direction: string
  start_elevation: number
  end_elevation: number
}

export interface ZoneV2VO {
  id: number
  map_id: number
  floor_id: number
  zone_code: string
  name: string
  zone_type: ZoneType
  priority: number
  max_speed: number
  shape: string
  vertices: number[][]
  height: number | null
  ramp_info: RampInfoVO | null
  description: string | null
}

export interface ShelfEdgeVO {
  edge_index: number
  vertex_start: number
  vertex_end: number
  direction: string | null
}

export interface ShelfSlotVO {
  id: number
  slot_index: number
  position: number[]
  size: number[]
  product_id: string | null
  product_name: string | null
}

export interface ShelfLayerVO {
  id: number
  layer_index: number
  elevation: number
  access_edge: number
  num_slots: number
  slots: ShelfSlotVO[]
}

export interface ShelfV2VO {
  id: number
  map_id: number
  floor_id: number
  shelf_code: string
  name: string
  vertices: number[][]
  height: number
  edges: ShelfEdgeVO[]
  layers: ShelfLayerVO[]
}

export interface SegmentV2VO {
  id: number
  seq: number
  source_waypoint_id: number
  target_waypoint_id: number
  segment_type: SegmentType
  control_points: number[][] | null
  max_speed: number | null
  back_up: boolean
  reverse: boolean
}

export interface PathV2VO {
  id: number
  map_id: number
  floor_id: number
  path_code: string
  name: string
  from_waypoint_id: number
  to_waypoint_id: number
  /** 段链推导：首段 source + 各段 target */
  waypoint_ids: number[]
  is_bidirectional: boolean
  max_speed: number | null
  enable_obstacle_avoidance: boolean
  status: PathV2Status
  segments: SegmentV2VO[]
}

export interface ConnEndpointVO {
  floor_id: string
  waypoint_id: string
}

export interface ConnRampVO {
  slope: number | null
  length: number | null
  surface: string | null
}

export interface ConnV2VO {
  id: number
  map_id: number
  conn_code: string
  name: string
  conn_type: ConnType
  connections: ConnEndpointVO[]
  capacity: number | null
  max_speed: number | null
  ramp_info: ConnRampVO | null
}

export interface FloorBundleVO {
  floor: FloorVO
  waypoints: WaypointV2VO[]
  zones: ZoneV2VO[]
  shelves: ShelfV2VO[]
  paths: PathV2VO[]
}

export interface MapFullVO {
  map: MapV2VO
  floors: FloorBundleVO[]
  connections: ConnV2VO[]
}

// ---------- 保存 DTO（snake_case 对齐后端 record） ----------

export interface WaypointSaveDTO {
  map_id?: number
  floor_id?: number
  waypoint_code?: string
  name?: string
  wp_type?: WaypointType
  x?: number
  y?: number
  yaw?: number
  remark?: string
  modified_by?: string
}

export interface ZoneSaveDTO {
  map_id?: number
  floor_id?: number
  zone_code?: string
  name?: string
  zone_type?: ZoneType
  priority?: number
  max_speed?: number
  vertices?: number[][]
  height?: number | null
  ramp_info?: RampInfoVO | null
  description?: string | null
  modified_by?: string
}

export interface ShelfEdgeDTO {
  edge_index: number
  vertex_start: number
  vertex_end: number
  direction?: string | null
}

export interface ShelfSlotDTO {
  slot_index: number
  position: number[]
  size: number[]
  product_id?: string | null
  product_name?: string | null
}

export interface ShelfLayerDTO {
  layer_index: number
  elevation: number
  access_edge: number
  slots: ShelfSlotDTO[]
}

export interface ShelfSaveDTO {
  map_id?: number
  floor_id?: number
  shelf_code?: string
  name?: string
  vertices?: number[][]
  height?: number
  edges?: ShelfEdgeDTO[]
  layers?: ShelfLayerDTO[]
  modified_by?: string
}

export interface PathSegmentDTO {
  source_waypoint_id: number
  target_waypoint_id: number
  segment_type?: SegmentType
  control_points?: number[][]
  max_speed?: number | null
  back_up?: boolean
  reverse?: boolean
}

export interface PathSaveDTO {
  map_id?: number
  floor_id?: number
  path_code?: string
  name?: string
  waypoint_ids?: number[]
  is_bidirectional?: boolean
  max_speed?: number | null
  enable_obstacle_avoidance?: boolean
  segments?: PathSegmentDTO[]
  status?: PathV2Status
  modified_by?: string
}

export interface ConnEndpointDTO {
  floor_id: string
  waypoint_id: string
}

export interface ConnSaveDTO {
  map_id?: number
  conn_code?: string
  name?: string
  conn_type?: ConnType
  connections?: ConnEndpointDTO[]
  capacity?: number | null
  max_speed?: number | null
  ramp_info?: ConnRampVO | null
  modified_by?: string
}

export interface FloorSaveDTO {
  map_id?: number
  floor_code?: string
  name?: string
  elevation?: number
  default_max_speed?: number
  sort_order?: number
  modified_by?: string
}
