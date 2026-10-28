// 与后端 VO 逐字段镜像。后端 Jackson 全局 SNAKE_CASE，因此字段一律 snake_case。
export interface ApiResponse<T> {
  status: number
  msg: string
  data: T
}

export type NavMapStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
export type PointType = 'NORMAL' | 'WORK' | 'CHARGER' | 'HOME'
export type PathStatus = 'DRAFT' | 'DEPLOYED' | 'DISABLED'
export type EdgeType = 'STRAIGHT' | 'CURVE'
export type MapTaskType = 'SWITCH_MAP' | 'START_MAPPING' | 'SAVE_MAP' | 'DISCARD_MAPPING'
export type MapTaskStatus = 'DISPATCHED' | 'RELOCATING' | 'SUCCEEDED' | 'FAILED'
export type MoveTaskType = 'TO_POINT' | 'GOAL' | 'FOLLOW_PATH' | 'FREE'
export type MoveTaskStatus =
  | 'CREATED'
  | 'DISPATCHED'
  | 'EXECUTING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMEOUT'

// ---------- 地图 ----------
export interface NavMapVO {
  id: number
  map_name: string
  status: NavMapStatus
  source: 'LIVE' | 'ROBOT_SYNC'
  robot_map_name: string | null
  resolution: number | null
  width: number | null
  height: number | null
  origin_x: number | null
  origin_y: number | null
  origin_yaw: number | null
  data_size: number | null
  created_at: string
  updated_at: string
}

export interface MapGridVO {
  frame_id: string | null
  resolution: number
  width: number
  height: number
  origin_x: number
  origin_y: number
  origin_yaw: number
  /** -1 未知 / 0 空闲 / 1~100 占据，行优先，第 0 行在世界坐标最下方 */
  data: number[]
  received_at: string | null
}

// ---------- 点位 ----------
export interface NavPointVO {
  id: number
  map_id: number
  point_code: string
  point_type: PointType
  x: number
  y: number
  yaw: number
  remark: string | null
  created_at: string
  updated_at: string
}

export interface NavPointCreateDTO {
  map_id: number
  point_code: string
  point_type?: PointType
  x: number
  y: number
  yaw: number
  remark?: string
}

export interface NavPointUpdateDTO {
  point_code?: string
  point_type?: PointType
  x?: number
  y?: number
  yaw?: number
  remark?: string
}

// ---------- 路线（点位之间的有序边链） ----------
export interface ControlPointVO {
  x: number
  y: number
}

export interface PathEdgeVO {
  id: number
  seq: number
  source_point_id: number
  source_point_code: string
  target_point_id: number
  target_point_code: string
  edge_type: EdgeType
  control_points: ControlPointVO[] | null
  max_speed: number | null
  back_up: boolean | null
  reverse: boolean | null
}

export interface PathEdgeDTO {
  source_point_id: number
  target_point_id: number
  edge_type?: EdgeType
  control_points?: ControlPointVO[]
  max_speed?: number
  back_up?: boolean
  reverse?: boolean
}

export interface NavPathVO {
  id: number
  map_id: number
  path_code: string
  path_name: string | null
  status: PathStatus
  /** 列表接口不返回，详情接口返回 */
  edges?: PathEdgeVO[] | null
  created_at: string
  updated_at: string
}

export interface NavPathCreateDTO {
  map_id: number
  path_code: string
  path_name?: string
}

export interface NavPathUpdateDTO {
  path_name?: string
  status?: 'DRAFT' | 'DISABLED'
}

// ---------- 建图/切图任务 ----------
export interface MapModeTaskVO {
  id: number
  type: MapTaskType
  status: MapTaskStatus
  nav_map_id: number | null
  map_name: string | null
  robot_map_name: string | null
  error_message: string | null
  created_at: string
  updated_at: string
  finished_at: string | null
}

// ---------- 移动任务 ----------
export interface MoveTaskVO {
  id: number
  task_no: string
  task_type: MoveTaskType
  status: MoveTaskStatus
  point_id: number | null
  path_id: number | null
  goal_x: number | null
  goal_y: number | null
  goal_theta: number | null
  current_x: number | null
  current_y: number | null
  current_theta: number | null
  agv_state: string | null
  segment_seq: number | null
  segment_total: number | null
  error_message: string | null
  created_at: string
  updated_at: string
  finished_at: string | null
}

export interface MoveTaskCreateDTO {
  task_type: MoveTaskType
  point_id?: number
  path_id?: number
  x?: number
  y?: number
  theta?: number
  max_speed?: number
  end_point?: boolean
}

// ---------- SSE /sse/agv ----------
export interface AgvStatus {
  agv_id: string | null
  state: string | null
  battery: number | null
  /** 电量接入字段（battery_simulator / 真机 BMS 同格式）；旧版机器人/后端未透出时缺失或为 null */
  battery_state?: string | null
  charging?: boolean | null
  charge_connected?: boolean | null
  battery_data_valid?: boolean | null
  pose_initialized: boolean | null
  active_command_id: string | null
  active_node_id: string | null
  mode: string | null
  map_name: string | null
  received_at: string | null
  age_ms: number | null
}

export interface ScanSummary {
  frame_id: string | null
  range_count: number | null
  min_range: number | null
  max_range: number | null
}

/** scan SSE 事件体：双雷达点云（base_link 系扁平坐标 [x0,y0,x1,y1,...]） */
export interface ScanCloudEvent {
  timestamp: string
  scan1: ScanCloud | null
  scan2: ScanCloud | null
}

export interface ScanCloud {
  frame_id: string
  range_count: number
  /** ROS 捕获时间；用于识别 SSE 聚合事件中未变化的旧帧。旧后端可能不携带。 */
  stamp_sec?: number
  /** 扁平 [x0,y0,x1,y1,...]，单位米，base_link 系 */
  points: number[]
  /** 该帧捕获时机器人的 map 系位姿；投影到地图必须用它。null = 当时位姿未知（无法正确绘制） */
  pose: { x: number; y: number; yaw: number } | null
}

/** telemetry 事件体（= GET /robot/snapshot 的 data） */
export interface TfFrame {
  parent: string
  child: string
  x: number
  y: number
  z: number
  qx: number
  qy: number
  qz: number
  qw: number
}

// ---------- 充电任务 ----------
/** 充电任务阶段：到桩导航 → 开始充电（服务+遥测确认）→ 充电中 → 终态 */
export type ChargeTaskStatus = 'MOVING' | 'STARTING' | 'CHARGING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface ChargeTaskVO {
  id: number
  source: string | null
  status: ChargeTaskStatus
  waypoint_id: number | null
  x: number
  y: number
  yaw: number
  target_battery_pct: number
  battery_at_start: number | null
  battery_now: number | null
  message: string | null
  internal_move_task_id: number | null
  created_at: string
  updated_at: string
  finished_at: string | null
}

export interface ChargeStartDTO {
  /** v2 充电点点位 id（优先） */
  waypoint_id?: number
  x?: number
  y?: number
  theta?: number
  /** 目标电量（%），缺省用后端 charge.target_battery_pct 配置 */
  target_battery_pct?: number
}

// ---------- RMS 任务（任务管理页） ----------
/** 对齐后端 RmsTaskStatus 枚举（wire 语义见 action_status_code） */
export interface RmsTaskVO {
  task_id: string
  action_id: string | null
  task_type: string | null
  task_template_type: string | null
  template_code: string | null
  template_id: string | null
  parameters: Record<string, unknown> | null
  status: string
  action_status_code: number
  message: string | null
  dest_x: number | null
  dest_y: number | null
  dest_yaw: number | null
  internal_move_task_id: number | null
  internal_charge_task_id: number | null
  created_at: string
  started_at: string | null
  finished_at: string | null
}

// ---------- 系统配置总览（只读） ----------
export interface SystemConfig {
  app: { server_port: string }
  rms: {
    enabled: boolean
    session_open: boolean
    registered: boolean
    robot: {
      robot_code: string
      robot_sn: string
      robot_type: string
      robot_name: string
      ip: string
      capabilities: string[]
    }
    report: {
      heartbeat_interval_ms: number
      status_interval_ms: number
      verbose_log: boolean
      publish_legacy_status: boolean
      publish_body_status: boolean
    }
    zenoh: {
      config_file: string
      endpoints: string[]
      namespace: string | null
      mode?: string
      query_timeout_ms: number
      robot_key_prefix: string
      register_key: string
      continue_without_register: boolean
      config_error?: string
    }
    behavior: {
      charge_pose: { x: number; y: number; yaw: number } | null
      home_pose: { x: number; y: number; yaw: number } | null
    }
  }
  sim_agv: {
    enabled: boolean
    ws_url: string
    connected: boolean
    status_fresh_ms: number
    goal_timeout_s: number
    default_max_speed: number
    default_step: number
    follow_path_start_radius: number
    arm_gate_enabled: boolean
    arm_strict_unknown: boolean
    arm_home_tolerance_rad: number
    topics: { label: string; topic: string; type: string; enabled: boolean }[]
    services: Record<string, string>
    ws_message_log: { enabled: boolean; max_chars: number; topic_interval_ms: number }
  }
  charge: Record<string, string | number>
}

export interface TelemetrySnapshot {
  timestamp: string
  connected: boolean
  status_fresh: boolean
  status: AgvStatus | null
  pose: { x: number; y: number; yaw: number; yaw_deg: number; topo_name?: string | null } | null
  /** /odom twist（车体系）；odom_enabled=false 或未收到时为 null */
  velocity: { linear_x: number; linear_y: number; angular_z: number } | null
  joints: { name: string[]; position: number[]; received_at?: string | null } | null
  tf_frames: TfFrame[] | null
  scan1: ScanSummary | null
  scan2: ScanSummary | null
  msg_status: number
  msg_pose: number
  msg_odom?: number
  msg_scan1: number
  msg_scan2: number
}

/** map-sync 事件体（注意 navMapId 为 camelCase，后端 Map 键不受全局命名策略影响） */
export interface MapSyncEvent {
  success: boolean
  navMapId?: number
  robotMapName?: string
  message?: string
}
