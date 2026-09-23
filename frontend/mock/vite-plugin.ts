import type { Connect, Plugin, PreviewServer } from 'vite'
import type { IncomingMessage, ServerResponse } from 'node:http'

/**
 * 开发用 mock 中间件：镜像部署服务器 REST + SSE 契约（全局 snake_case、{status,msg,data} 包裹）。
 * VITE_USE_MOCK=true 时启用，用于后端未启动时开发/演示。
 */

// ---------------- 内存状态 ----------------
interface MockMap {
  id: number
  map_name: string
  status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
  source: 'LIVE' | 'ROBOT_SYNC'
  robot_map_name: string | null
  resolution: number
  width: number
  height: number
  origin_x: number
  origin_y: number
  origin_yaw: number
  data: number[]
  data_size: number
  created_at: string
  updated_at: string
}
interface MockPoint {
  id: number
  map_id: number
  point_code: string
  point_type: string
  x: number
  y: number
  yaw: number
  remark: string | null
  created_at: string
  updated_at: string
}
interface MockEdge {
  id: number
  seq: number
  source_point_id: number
  target_point_id: number
  edge_type: 'STRAIGHT' | 'CURVE'
  control_points: { x: number; y: number }[] | null
  max_speed: number | null
  back_up: boolean | null
  reverse: boolean | null
}
interface MockPath {
  id: number
  map_id: number
  path_code: string
  path_name: string | null
  status: 'DRAFT' | 'DEPLOYED' | 'DISABLED'
  created_at: string
  updated_at: string
}
interface MockMapTask {
  id: number
  type: 'SWITCH_MAP' | 'START_MAPPING' | 'SAVE_MAP'
  status: 'DISPATCHED' | 'RELOCATING' | 'SUCCEEDED' | 'FAILED'
  nav_map_id: number | null
  map_name: string | null
  robot_map_name: string | null
  error_message: string | null
  created_at: string
  updated_at: string
  finished_at: string | null
}
interface MockMoveTask {
  id: number
  task_no: string
  task_type: 'TO_POINT' | 'GOAL' | 'FOLLOW_PATH'
  status: string
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

function makeGridData(): { data: number[]; width: number; height: number } {
  const width = 160
  const height = 120
  const data = new Array<number>(width * height).fill(0)
  const idx = (c: number, r: number) => r * width + c
  // 外圈未知
  for (let r = 0; r < height; r++)
    for (let c = 0; c < width; c++)
      if (r < 10 || r >= height - 10 || c < 10 || c >= width - 10) data[idx(c, r)] = -1
  // 墙（两格厚）
  for (let c = 10; c < width - 10; c++) {
    for (let d = 0; d < 2; d++) {
      data[idx(c, 10 + d)] = 100
      data[idx(c, height - 11 - d)] = 100
    }
  }
  for (let r = 10; r < height - 10; r++) {
    for (let d = 0; d < 2; d++) {
      data[idx(10 + d, r)] = 100
      data[idx(width - 11 - d, r)] = 100
    }
  }
  // 障碍物
  const rect = (c0: number, r0: number, c1: number, r1: number) => {
    for (let r = r0; r <= r1; r++) for (let c = c0; c <= c1; c++) data[idx(c, r)] = 100
  }
  rect(30, 30, 38, 70)
  rect(60, 30, 110, 36)
  rect(60, 60, 66, 90)
  rect(120, 50, 126, 90)
  rect(40, 88, 90, 94)
  return { data, width, height }
}

const RESOLUTION = 0.05
const ORIGIN_X = -2
const ORIGIN_Y = -1.5

function state() {
  const now = () => new Date().toISOString()
  const grid = makeGridData()
  const s = {
    idSeq: 100,
    nextId() {
      return ++this.idSeq
    },
    liveGrid: { ...grid, frameId: 'map', resolution: RESOLUTION, originX: ORIGIN_X, originY: ORIGIN_Y, originYaw: 0, receivedAt: now() },
    maps: [] as MockMap[],
    points: [] as MockPoint[],
    paths: [] as MockPath[],
    edges: new Map<number, MockEdge[]>(),
    mapTasks: [] as MockMapTask[],
    moveTasks: [] as MockMoveTask[],
    robot: {
      x: 0.2,
      y: 0.2,
      yaw: 0,
      mode: 'NAVIGATION',
      state: 'IDLE',
      battery: 87,
      poseInitialized: true,
      mapName: 'demo_map',
    },
    motion: null as null | { taskId: number; waypoints: { x: number; y: number }[]; segIdx: number; lastPush: number },
  }

  // 种子地图
  const m: MockMap = {
    id: 1,
    map_name: 'demo_map',
    status: 'ACTIVE',
    source: 'ROBOT_SYNC',
    robot_map_name: 'demo_map',
    resolution: RESOLUTION,
    width: grid.width,
    height: grid.height,
    origin_x: ORIGIN_X,
    origin_y: ORIGIN_Y,
    origin_yaw: 0,
    data: [...grid.data],
    data_size: 120000,
    created_at: now(),
    updated_at: now(),
  }
  s.maps.push(m)
  s.idSeq = 1

  const seedPoint = (code: string, x: number, y: number, yaw: number, type = 'NORMAL') => {
    const p: MockPoint = {
      id: s.nextId(),
      map_id: 1,
      point_code: code,
      point_type: type,
      x,
      y,
      yaw,
      remark: null,
      created_at: now(),
      updated_at: now(),
    }
    s.points.push(p)
    return p
  }
  const p1 = seedPoint('P1', -0.5, -0.5, 0)
  seedPoint('P2', 1.2, -0.5, Math.PI / 2)
  seedPoint('P3', 1.2, 1.0, Math.PI)
  seedPoint('CHARGE', -1.2, 1.0, 0, 'CHARGER')

  const path: MockPath = {
    id: s.nextId(),
    map_id: 1,
    path_code: 'ROUTE_A',
    path_name: '演示路线',
    status: 'DEPLOYED',
    created_at: now(),
    updated_at: now(),
  }
  s.paths.push(path)
  s.edges.set(path.id, [
    { id: s.nextId(), seq: 1, source_point_id: p1.id, target_point_id: p1.id + 1, edge_type: 'STRAIGHT', control_points: null, max_speed: 0.6, back_up: false, reverse: false },
    {
      id: s.nextId(),
      seq: 2,
      source_point_id: p1.id + 1,
      target_point_id: p1.id + 2,
      edge_type: 'CURVE',
      control_points: [{ x: 1.7, y: 0.3 }],
      max_speed: 0.4,
      back_up: false,
      reverse: false,
    },
  ])
  return s
}

// ---------------- 工具 ----------------
type State = ReturnType<typeof state>

function json(res: ServerResponse, status: number, body: unknown) {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.end(JSON.stringify(body))
}
const ok = (res: ServerResponse, data: unknown, httpStatus = 200) =>
  json(res, httpStatus, { status: 200, msg: 'success', data })
const fail = (res: ServerResponse, httpStatus: number, msg: string) =>
  json(res, httpStatus, { status: httpStatus, msg, data: null })

function readBody(req: IncomingMessage): Promise<any> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = []
    req.on('data', (c: Buffer) => chunks.push(c))
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf-8')
      if (!raw) return resolve({})
      try {
        resolve(JSON.parse(raw))
      } catch {
        resolve({})
      }
    })
  })
}

function snapshot(s: State) {
  const r = s.robot
  return {
    timestamp: new Date().toISOString(),
    connected: true,
    status_fresh: true,
    status: {
      agv_id: 'AGV001',
      state: r.state,
      battery: r.battery,
      pose_initialized: r.poseInitialized,
      active_command_id: null,
      active_node_id: null,
      mode: r.mode,
      map_name: r.mapName,
      received_at: new Date().toISOString(),
      age_ms: 12,
    },
    pose: { x: +r.x.toFixed(4), y: +r.y.toFixed(4), yaw: +r.yaw.toFixed(4), yaw_deg: +((r.yaw * 180) / Math.PI).toFixed(1) },
    scan1: { frame_id: 'laser_1', range_count: 360, min_range: 0.32, max_range: 7.9 },
    scan2: null,
    msg_status: 1000,
    msg_pose: 5000,
    msg_scan1: 3000,
    msg_scan2: 0,
  }
}

function toMapVO(m: MockMap) {
  const { data: _data, ...rest } = m
  return rest
}
function toEdgeVO(e: MockEdge, s: State) {
  const codeOf = (id: number) => s.points.find((p) => p.id === id)?.point_code ?? `#${id}`
  return { ...e, source_point_code: codeOf(e.source_point_id), target_point_code: codeOf(e.target_point_id) }
}

// ---------------- 插件 ----------------
export function mockPlugin(): Plugin {
  return {
    name: 'nav-deployer-mock',
    apply: 'serve',
    configureServer(server) {
      const s = state()
      const sseClients = new Set<ServerResponse>()

      const sseSend = (res: ServerResponse, event: string, data: unknown) => {
        try {
          res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
        } catch {
          sseClients.delete(res)
        }
      }
      const broadcast = (event: string, data: unknown) => {
        for (const c of [...sseClients]) sseSend(c, event, data)
      }

      // 机器人运动模拟：100ms 步进，0.6 m/s
      setInterval(() => {
        const mo = s.motion
        if (!mo) return
        const task = s.moveTasks.find((t) => t.id === mo.taskId)
        if (!task || task.status !== 'EXECUTING') return
        const wp = mo.waypoints[mo.segIdx]
        if (!wp) return
        const speed = 0.6 * 0.1
        const dx = wp.x - s.robot.x
        const dy = wp.y - s.robot.y
        const dist = Math.hypot(dx, dy)
        if (dist <= speed) {
          s.robot.x = wp.x
          s.robot.y = wp.y
          s.robot.yaw = task.goal_theta ?? s.robot.yaw
          mo.segIdx++
          if (mo.segIdx >= mo.waypoints.length) {
            task.status = 'SUCCEEDED'
            task.agv_state = 'COMPLETED'
            task.finished_at = new Date().toISOString()
            task.updated_at = task.finished_at
            s.motion = null
            s.robot.state = 'IDLE'
            broadcast('task', task)
            return
          }
        } else {
          s.robot.x += (dx / dist) * speed
          s.robot.y += (dy / dist) * speed
          s.robot.yaw = Math.atan2(dy, dx)
        }
        task.current_x = +s.robot.x.toFixed(4)
        task.current_y = +s.robot.y.toFixed(4)
        task.current_theta = +s.robot.yaw.toFixed(4)
        task.agv_state = 'EXECUTING'
        if (task.task_type === 'FOLLOW_PATH') {
          task.segment_seq = mo.segIdx + 1
          task.segment_total = mo.waypoints.length
        }
        const t = Date.now()
        if (t - mo.lastPush > 1000) {
          mo.lastPush = t
          task.updated_at = new Date().toISOString()
          broadcast('task', task)
        }
      }, 100)

      // SSE 心跳 + 遥测
      setInterval(() => {
        for (const c of [...sseClients]) {
          sseSend(c, 'telemetry', snapshot(s))
          sseSend(c, 'heartbeat', { timestamp: new Date().toISOString() })
        }
      }, 1000)

      // 建图/切图任务状态机
      const pushMapTask = (t: MockMapTask) => {
        s.mapTasks.unshift(t)
        if (s.mapTasks.length > 100) s.mapTasks.pop()
        broadcast('map-task', t)
      }
      const transition = (t: MockMapTask, status: MockMapTask['status'], afterMs: number, onDone?: () => void) => {
        setTimeout(() => {
          t.status = status
          t.updated_at = new Date().toISOString()
          if (status === 'SUCCEEDED' || status === 'FAILED') t.finished_at = t.updated_at
          pushMapTask(t)
          onDone?.()
        }, afterMs)
      }

      const requireMap = (id: number) => s.maps.find((m) => m.id === id)
      const pathEdgeList = (id: number) => s.edges.get(id) ?? []

      const handler: Connect.NextHandleFunction = async (req, res, next) => {
        const url = (req.url ?? '').split('?')[0]
        const method = (req.method ?? 'GET').toUpperCase()
        if (!url.startsWith('/api/v1') && !url.startsWith('/sse/')) return next()

        // ---------- SSE ----------
        if (url === '/sse/agv' && method === 'GET') {
          res.statusCode = 200
          res.setHeader('Content-Type', 'text/event-stream')
          res.setHeader('Cache-Control', 'no-cache')
          res.setHeader('Connection', 'keep-alive')
          res.flushHeaders?.()
          res.write(`retry: 3000\n\n`)
          sseSend(res, 'connected', { timestamp: new Date().toISOString() })
          sseSend(res, 'telemetry', snapshot(s))
          sseClients.add(res)
          req.on('close', () => sseClients.delete(res))
          return
        }

        const seg = url.replace('/api/v1', '').split('/').filter(Boolean)
        const body = method === 'POST' || method === 'PUT' ? await readBody(req) : {}

        // ---------- robot ----------
        if (seg[0] === 'robot') {
          if (seg[1] === 'snapshot' && method === 'GET') return ok(res, snapshot(s))
          if (seg[1] === 'control' && method === 'POST') {
            const action = body.action
            if (!['start', 'stop', 'reset'].includes(action)) return fail(res, 400, 'action 仅支持 start|stop|reset')
            if (action === 'stop') {
              s.motion = null
              s.robot.state = 'STOPPED'
              const act = s.moveTasks.find((t) => ['CREATED', 'DISPATCHED', 'EXECUTING'].includes(t.status))
              if (act) {
                act.status = 'CANCELLED'
                act.finished_at = new Date().toISOString()
                broadcast('task', act)
              }
            } else if (action === 'reset') {
              s.motion = null
              s.robot.state = 'IDLE'
            } else {
              s.robot.state = 'IDLE'
            }
            return ok(res, { success: true, message: 'ok', state: s.robot.state })
          }
          if (seg[1] === 'initial-pose' && method === 'POST') {
            s.robot.x = Number(body.x) || 0
            s.robot.y = Number(body.y) || 0
            s.robot.yaw = Number(body.theta) || 0
            s.robot.poseInitialized = true
            return ok(res, null)
          }
        }

        // ---------- 地图 ----------
        if (seg[0] === 'nav-maps') {
          if (seg.length === 1 && method === 'GET') {
            const list = [...s.maps].sort((a, b) => b.id - a.id)
            return ok(res, list.map(toMapVO))
          }
          if (seg.length === 1 && method === 'POST') {
            const name = String(body.map_name ?? '').trim()
            if (!name || name.length > 64) return fail(res, 400, 'map_name 必填且不超过 64 字符')
            if (s.maps.some((m) => m.map_name === name)) return fail(res, 400, `地图名已存在: ${name}`)
            const g = s.liveGrid
            const m: MockMap = {
              id: s.nextId(),
              map_name: name,
              status: 'DRAFT',
              source: 'LIVE',
              robot_map_name: null,
              resolution: g.resolution,
              width: g.width,
              height: g.height,
              origin_x: g.originX,
              origin_y: g.originY,
              origin_yaw: g.originYaw,
              data: [],
              data_size: g.data.length,
              created_at: new Date().toISOString(),
              updated_at: new Date().toISOString(),
            }
            m.data = [...g.data]
            s.maps.push(m)
            return ok(res, toMapVO(m), 201)
          }
          if (seg[1] === 'live' && method === 'GET') {
            const g = s.liveGrid
            return ok(res, {
              frame_id: g.frameId,
              resolution: g.resolution,
              width: g.width,
              height: g.height,
              origin_x: g.originX,
              origin_y: g.originY,
              origin_yaw: g.originYaw,
              data: g.data,
              received_at: g.receivedAt,
            })
          }
          const id = Number(seg[1])
          const map = requireMap(id)
          if (!map) return fail(res, 404, `地图不存在: ${id}`)
          if (seg.length === 2 && method === 'GET') return ok(res, toMapVO(map))
          if (seg[2] === 'data' && method === 'GET') {
            const g = map
            return json(res, 200, {
              frame_id: 'map',
              resolution: g.resolution,
              width: g.width,
              height: g.height,
              origin_x: g.origin_x,
              origin_y: g.origin_y,
              origin_yaw: g.origin_yaw,
              data: g.data,
              received_at: g.updated_at,
            })
          }
          if (seg.length === 2 && method === 'PUT') {
            const name = String(body.map_name ?? '').trim()
            if (!name) return fail(res, 400, 'map_name 必填')
            if (s.maps.some((m) => m.map_name === name && m.id !== id)) return fail(res, 400, `地图名已存在: ${name}`)
            map.map_name = name
            map.updated_at = new Date().toISOString()
            return ok(res, toMapVO(map))
          }
          if (seg[2] === 'robot-map-name' && method === 'PUT') {
            map.robot_map_name = body.robot_map_name ? String(body.robot_map_name) : null
            map.updated_at = new Date().toISOString()
            return ok(res, toMapVO(map))
          }
          if (seg[2] === 'switch' && method === 'POST') {
            if (s.mapTasks[0] && ['DISPATCHED', 'RELOCATING'].includes(s.mapTasks[0].status))
              return fail(res, 400, '已有进行中的模式任务')
            const t: MockMapTask = {
              id: s.nextId(),
              type: 'SWITCH_MAP',
              status: 'DISPATCHED',
              nav_map_id: id,
              map_name: map.map_name,
              robot_map_name: body.robot_map_name ?? map.robot_map_name ?? map.map_name,
              error_message: null,
              created_at: new Date().toISOString(),
              updated_at: new Date().toISOString(),
              finished_at: null,
            }
            pushMapTask(t)
            transition(t, 'RELOCATING', 1500)
            transition(t, 'SUCCEEDED', 3500, () => {
              s.robot.mapName = t.robot_map_name ?? map.map_name
              s.maps.forEach((m) => (m.status = m.id === id ? 'ACTIVE' : 'ARCHIVED'))
            })
            return ok(res, t)
          }
          if (seg[2] === 'activate' && method === 'POST') {
            s.maps.forEach((m) => (m.status = m.id === id ? 'ACTIVE' : 'ARCHIVED'))
            map.updated_at = new Date().toISOString()
            return ok(res, toMapVO(map))
          }
          if (seg.length === 2 && method === 'DELETE') {
            if (map.status === 'ACTIVE') return fail(res, 400, '部署中的地图不允许删除，请先激活其他地图')
            const ref = s.points.some((p) => p.map_id === id) || s.paths.some((p) => p.map_id === id)
            if (ref) return fail(res, 400, '地图下存在点位或路线，不允许删除')
            s.maps = s.maps.filter((m) => m.id !== id)
            return ok(res, null)
          }
        }

        // ---------- 点位 ----------
        if (seg[0] === 'nav-points') {
          if (seg.length === 1 && method === 'GET') {
            const mapId = Number(new URL(req.url ?? '', 'http://x').searchParams.get('mapId'))
            return ok(res, s.points.filter((p) => p.map_id === mapId))
          }
          if (seg.length === 1 && method === 'POST') {
            const mapId = Number(body.map_id)
            if (!requireMap(mapId)) return fail(res, 400, `地图不存在: ${body.map_id}`)
            const code = String(body.point_code ?? '').trim()
            if (!code) return fail(res, 400, 'point_code 必填')
            if (s.points.some((p) => p.map_id === mapId && p.point_code === code))
              return fail(res, 400, `点位编码已存在: ${code}`)
            const p: MockPoint = {
              id: s.nextId(),
              map_id: mapId,
              point_code: code,
              point_type: body.point_type ?? 'NORMAL',
              x: Number(body.x) || 0,
              y: Number(body.y) || 0,
              yaw: Number(body.yaw) || 0,
              remark: body.remark ?? null,
              created_at: new Date().toISOString(),
              updated_at: new Date().toISOString(),
            }
            s.points.push(p)
            return ok(res, p, 201)
          }
          if (seg[1] === 'from-current-pose' && method === 'POST') {
            const mapId = Number(body.map_id)
            const code = String(body.point_code ?? '').trim()
            if (!code) return fail(res, 400, 'point_code 必填')
            if (s.points.some((p) => p.map_id === mapId && p.point_code === code))
              return fail(res, 400, `点位编码已存在: ${code}`)
            const p: MockPoint = {
              id: s.nextId(),
              map_id: mapId,
              point_code: code,
              point_type: body.point_type ?? 'NORMAL',
              x: +s.robot.x.toFixed(3),
              y: +s.robot.y.toFixed(3),
              yaw: +s.robot.yaw.toFixed(3),
              remark: body.remark ?? null,
              created_at: new Date().toISOString(),
              updated_at: new Date().toISOString(),
            }
            s.points.push(p)
            return ok(res, p, 201)
          }
          const id = Number(seg[1])
          const point = s.points.find((p) => p.id === id)
          if (!point) return fail(res, 404, `点位不存在: ${id}`)
          if (seg.length === 2 && method === 'GET') return ok(res, point)
          if (seg.length === 2 && method === 'PUT') {
            for (const k of ['point_code', 'point_type', 'x', 'y', 'yaw', 'remark'] as const) {
              if (k in body) (point as any)[k] = body[k]
            }
            point.updated_at = new Date().toISOString()
            return ok(res, point)
          }
          if (seg.length === 2 && method === 'DELETE') {
            const ref = [...s.edges.values()].flat().some((e) => e.source_point_id === id || e.target_point_id === id)
            if (ref) return fail(res, 400, '点位已被路线引用，不允许删除')
            s.points = s.points.filter((p) => p.id !== id)
            return ok(res, null)
          }
        }

        // ---------- 路线 ----------
        if (seg[0] === 'nav-paths') {
          if (seg.length === 1 && method === 'GET') {
            const mapId = Number(new URL(req.url ?? '', 'http://x').searchParams.get('mapId'))
            return ok(res, s.paths.filter((p) => p.map_id === mapId).map((p) => ({ ...p, edges: null })))
          }
          if (seg.length === 1 && method === 'POST') {
            const mapId = Number(body.map_id)
            if (!requireMap(mapId)) return fail(res, 400, `地图不存在: ${body.map_id}`)
            const code = String(body.path_code ?? '').trim()
            if (!code) return fail(res, 400, 'path_code 必填')
            if (s.paths.some((p) => p.map_id === mapId && p.path_code === code))
              return fail(res, 400, `路线编码已存在: ${code}`)
            const p: MockPath = {
              id: s.nextId(),
              map_id: mapId,
              path_code: code,
              path_name: body.path_name ?? null,
              status: 'DRAFT',
              created_at: new Date().toISOString(),
              updated_at: new Date().toISOString(),
            }
            s.paths.push(p)
            s.edges.set(p.id, [])
            return ok(res, { ...p, edges: null }, 201)
          }
          const id = Number(seg[1])
          const path = s.paths.find((p) => p.id === id)
          if (!path) return fail(res, 404, `路线不存在: ${id}`)
          if (seg.length === 2 && method === 'GET') {
            return ok(res, { ...path, edges: pathEdgeList(id).map((e) => toEdgeVO(e, s)) })
          }
          if (seg.length === 2 && method === 'PUT') {
            if (body.path_name !== undefined) path.path_name = body.path_name
            if (body.status !== undefined) {
              if (!['DRAFT', 'DISABLED'].includes(body.status)) return fail(res, 400, 'status 仅支持 DRAFT|DISABLED')
              path.status = body.status
            }
            path.updated_at = new Date().toISOString()
            return ok(res, { ...path, edges: pathEdgeList(id).map((e) => toEdgeVO(e, s)) })
          }
          if (seg[2] === 'edges' && method === 'PUT') {
            const edges = (body.edges ?? []) as any[]
            if (!Array.isArray(edges) || edges.length === 0) return fail(res, 400, 'edges 不能为空')
            for (let i = 0; i < edges.length; i++) {
              const e = edges[i]
              const src = s.points.find((p) => p.id === e.source_point_id)
              const tgt = s.points.find((p) => p.id === e.target_point_id)
              if (!src || !tgt) return fail(res, 400, `第 ${i + 1} 条边引用了不存在的点位`)
              if (src.map_id !== path.map_id || tgt.map_id !== path.map_id) return fail(res, 400, `第 ${i + 1} 条边点位与路线不在同一地图`)
              if (src.id === tgt.id) return fail(res, 400, `第 ${i + 1} 条边起终点相同`)
              if (i > 0 && edges[i - 1].target_point_id !== e.source_point_id)
                return fail(res, 400, `第 ${i + 1} 条边与前一条不连续`)
              const type = e.edge_type || 'STRAIGHT'
              if (!['STRAIGHT', 'CURVE'].includes(type)) return fail(res, 400, `edge_type 仅支持 STRAIGHT|CURVE`)
              const cps = e.control_points ?? []
              if (type === 'CURVE' && (cps.length < 1 || cps.length > 2)) return fail(res, 400, 'CURVE 边需要 1~2 个控制点')
              if (type === 'STRAIGHT' && cps.length > 0) return fail(res, 400, 'STRAIGHT 边不允许控制点')
              if (e.max_speed != null && (e.max_speed < 0.05 || e.max_speed > 2)) return fail(res, 400, 'max_speed 范围 0.05~2.0')
            }
            const list: MockEdge[] = edges.map((e, i) => ({
              id: s.nextId(),
              seq: i + 1,
              source_point_id: e.source_point_id,
              target_point_id: e.target_point_id,
              edge_type: e.edge_type || 'STRAIGHT',
              control_points: (e.edge_type || 'STRAIGHT') === 'CURVE' ? e.control_points : null,
              max_speed: e.max_speed ?? null,
              back_up: !!e.back_up,
              reverse: !!e.reverse,
            }))
            s.edges.set(id, list)
            path.status = 'DRAFT'
            path.updated_at = new Date().toISOString()
            return ok(res, { ...path, edges: list.map((e) => toEdgeVO(e, s)) })
          }
          if (seg[2] === 'deploy' && method === 'POST') {
            if (pathEdgeList(id).length === 0) return fail(res, 400, '路线没有边，无法部署')
            path.status = 'DEPLOYED'
            path.updated_at = new Date().toISOString()
            return ok(res, { ...path, edges: pathEdgeList(id).map((e) => toEdgeVO(e, s)) })
          }
          if (seg.length === 2 && method === 'DELETE') {
            s.paths = s.paths.filter((p) => p.id !== id)
            s.edges.delete(id)
            return ok(res, null)
          }
        }

        // ---------- 建图/切图任务 ----------
        if (seg[0] === 'map-mode-tasks') {
          if (seg[1] === 'current' && method === 'GET')
            return ok(res, s.mapTasks.find((t) => ['DISPATCHED', 'RELOCATING'].includes(t.status)) ?? s.mapTasks[0] ?? null)
          if (seg[1] === 'recent' && method === 'GET') return ok(res, s.mapTasks.slice(0, 20))
          if (seg[1] === 'start-mapping' && method === 'POST') {
            if (s.mapTasks[0] && ['DISPATCHED', 'RELOCATING'].includes(s.mapTasks[0].status))
              return fail(res, 400, '已有进行中的模式任务')
            if (s.motion) return fail(res, 400, '存在执行中的移动任务')
            if (s.robot.mode !== 'NAVIGATION') return fail(res, 400, `当前模式为 ${s.robot.mode}，需为 NAVIGATION`)
            const t: MockMapTask = {
              id: s.nextId(),
              type: 'START_MAPPING',
              status: 'DISPATCHED',
              nav_map_id: null,
              map_name: null,
              robot_map_name: null,
              error_message: null,
              created_at: new Date().toISOString(),
              updated_at: new Date().toISOString(),
              finished_at: null,
            }
            pushMapTask(t)
            transition(t, 'SUCCEEDED', 1500, () => {
              s.robot.mode = 'MAPPING'
              s.robot.state = 'IDLE'
            })
            return ok(res, t)
          }
          if (seg[1] === 'save-map' && method === 'POST') {
            if (s.robot.mode !== 'MAPPING') return fail(res, 400, '当前不在建图模式，无法保存地图')
            const name = String(body.map_name ?? '').trim()
            if (!name || name.length > 64) return fail(res, 400, 'map_name 必填且不超过 64 字符')
            const t: MockMapTask = {
              id: s.nextId(),
              type: 'SAVE_MAP',
              status: 'DISPATCHED',
              nav_map_id: null,
              map_name: name,
              robot_map_name: name,
              error_message: null,
              created_at: new Date().toISOString(),
              updated_at: new Date().toISOString(),
              finished_at: null,
            }
            pushMapTask(t)
            transition(t, 'RELOCATING', 1500)
            transition(t, 'SUCCEEDED', 3000, () => {
              s.robot.mode = 'NAVIGATION'
              const g = s.liveGrid
              let m = s.maps.find((x) => x.map_name === name)
              if (!m) {
                m = {
                  id: s.nextId(),
                  map_name: name,
                  status: 'ACTIVE',
                  source: 'ROBOT_SYNC',
                  robot_map_name: name,
                  resolution: g.resolution,
                  width: g.width,
                  height: g.height,
                  origin_x: g.originX,
                  origin_y: g.originY,
                  origin_yaw: g.originYaw,
                  data: [],
                  data_size: g.data.length,
                  created_at: new Date().toISOString(),
                  updated_at: new Date().toISOString(),
                }
                s.maps.push(m)
              } else {
                m.updated_at = new Date().toISOString()
              }
              const saved = m
              saved.data = [...g.data]
              s.maps.forEach((x) => (x.status = x.id === saved.id ? 'ACTIVE' : 'ARCHIVED'))
              s.robot.mapName = name
              broadcast('map-sync', { success: true, navMapId: saved.id, robotMapName: name })
            })
            return ok(res, t)
          }
        }

        // ---------- 移动任务 ----------
        if (seg[0] === 'move-tasks') {
          if (seg[1] === 'active' && method === 'GET')
            return ok(res, s.moveTasks.find((t) => ['CREATED', 'DISPATCHED', 'EXECUTING'].includes(t.status)) ?? null)
          if (seg.length === 3 && seg[2] === 'cancel' && method === 'POST') {
            const t = s.moveTasks.find((x) => x.id === Number(seg[1]))
            if (!t) return fail(res, 404, `任务不存在: ${seg[1]}`)
            if (!['CREATED', 'DISPATCHED', 'EXECUTING'].includes(t.status)) return fail(res, 400, '任务已结束')
            t.status = 'CANCELLED'
            t.finished_at = new Date().toISOString()
            t.updated_at = t.finished_at
            s.motion = null
            s.robot.state = 'IDLE'
            broadcast('task', t)
            return ok(res, t)
          }
          if (seg.length === 1 && method === 'POST') {
            if (s.moveTasks.some((t) => ['CREATED', 'DISPATCHED', 'EXECUTING'].includes(t.status)))
              return fail(res, 400, '存在执行中的移动任务')
            if (s.robot.mode !== 'NAVIGATION') return fail(res, 400, `当前模式为 ${s.robot.mode}，需为 NAVIGATION`)
            let waypoints: { x: number; y: number }[] = []
            const taskType = body.task_type
            let goalX: number | null = null
            let goalY: number | null = null
            let goalTheta: number | null = null
            if (taskType === 'TO_POINT') {
              const p = s.points.find((x) => x.id === Number(body.point_id))
              if (!p) return fail(res, 400, `点位不存在: ${body.point_id}`)
              waypoints = [{ x: p.x, y: p.y }]
              goalX = p.x
              goalY = p.y
              goalTheta = p.yaw
            } else if (taskType === 'GOAL') {
              waypoints = [{ x: Number(body.x) || 0, y: Number(body.y) || 0 }]
              goalX = waypoints[0].x
              goalY = waypoints[0].y
              goalTheta = Number(body.theta) || 0
            } else if (taskType === 'FOLLOW_PATH') {
              const edges = pathEdgeList(Number(body.path_id))
              if (!edges.length) return fail(res, 400, '路线没有边或不存在')
              const pt = (pid: number) => s.points.find((p) => p.id === pid)!
              waypoints = [pt(edges[0].source_point_id), ...edges.map((e) => pt(e.target_point_id))].map((p) => ({ x: p.x, y: p.y }))
              const lastEdge = edges[edges.length - 1]
              goalTheta = pt(lastEdge.target_point_id).yaw
            } else {
              return fail(res, 400, 'task_type 仅支持 TO_POINT|GOAL|FOLLOW_PATH')
            }
            const t: MockMoveTask = {
              id: s.nextId(),
              task_no: `MT${Date.now()}`,
              task_type: taskType,
              status: 'EXECUTING',
              point_id: body.point_id ?? null,
              path_id: body.path_id ?? null,
              goal_x: goalX,
              goal_y: goalY,
              goal_theta: goalTheta,
              current_x: s.robot.x,
              current_y: s.robot.y,
              current_theta: s.robot.yaw,
              agv_state: 'EXECUTING',
              segment_seq: 1,
              segment_total: waypoints.length,
              error_message: null,
              created_at: new Date().toISOString(),
              updated_at: new Date().toISOString(),
              finished_at: null,
            }
            s.moveTasks.unshift(t)
            s.robot.state = 'EXECUTING'
            s.motion = { taskId: t.id, waypoints, segIdx: 0, lastPush: 0 }
            broadcast('task', t)
            return ok(res, t, 201)
          }
          if (seg.length === 1 && method === 'GET') return ok(res, s.moveTasks.slice(0, 50))
          const id = Number(seg[1])
          const t = s.moveTasks.find((x) => x.id === id)
          if (!t) return fail(res, 404, `任务不存在: ${id}`)
          return ok(res, t)
        }

        return fail(res, 404, `mock 未实现: ${method} ${url}`)
      }

      server.middlewares.use(handler)
    },
    configurePreviewServer(_server: PreviewServer) {},
  }
}
