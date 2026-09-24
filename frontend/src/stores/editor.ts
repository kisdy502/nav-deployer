import { computed, reactive, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import * as mapsApi from '@/api/maps'
import * as pointsApi from '@/api/points'
import * as pathsApi from '@/api/paths'
import { useRobotStore } from './robot'
import type {
  EdgeType,
  MapGridVO,
  NavMapVO,
  NavPointVO,
  NavPathVO,
  PathEdgeVO,
  PointType,
} from '@/types/api'

export type EditorMode = 'idle' | 'placePoint' | 'drawPath' | 'initialPose' | 'measure'

export interface XY {
  x: number
  y: number
}

/** 边编辑草稿（对应后端 PathEdgeDTO，seq 由后端按数组顺序生成） */
export interface PathEdgeDraft {
  source_point_id: number
  target_point_id: number
  edge_type: EdgeType
  control_points: XY[]
  max_speed: number | null
  back_up: boolean
  reverse: boolean
}

export const toEdgeDraft = (e: PathEdgeVO): PathEdgeDraft => ({
  source_point_id: e.source_point_id,
  target_point_id: e.target_point_id,
  edge_type: e.edge_type,
  control_points: (e.control_points ?? []).map((c) => ({ x: c.x, y: c.y })),
  max_speed: e.max_speed ?? null,
  back_up: !!e.back_up,
  reverse: !!e.reverse,
})

export const DEFAULT_MAX_SPEED = 0.6

export const useEditorStore = defineStore('editor', () => {
  const mapId = ref(0)
  const mapInfo = ref<NavMapVO | null>(null)
  const grid = ref<MapGridVO | null>(null)
  const points = ref<NavPointVO[]>([])
  const paths = ref<NavPathVO[]>([])
  const pathDetails = ref<Record<number, PathEdgeVO[]>>({})

  const selectedPointId = ref<number | null>(null)
  const selectedPathId = ref<number | null>(null)
  const selectedEdgeIdx = ref<number | null>(null)
  const mode = ref<EditorMode>('idle')
  const pathEdit = ref<{ pathId: number; edges: PathEdgeDraft[]; pendingSourceId: number | null } | null>(null)
  const measure = ref<{ a: XY | null; b: XY | null }>({ a: null, b: null })
  const layers = reactive({ map: true, points: true, paths: true, robot: true, trail: true, scan: true })
  const livePolling = ref(false)
  const loading = ref(false)

  const pointsById = computed(() => new Map(points.value.map((p) => [p.id, p])))
  const selectedPoint = computed(() => (selectedPointId.value == null ? null : pointsById.value.get(selectedPointId.value) ?? null))
  const selectedPath = computed(() => (selectedPathId.value == null ? null : paths.value.find((p) => p.id === selectedPathId.value) ?? null))
  const editingPath = computed(() => (pathEdit.value ? paths.value.find((p) => p.id === pathEdit.value!.pathId) ?? null : null))

  // ---------- 加载 ----------
  async function loadMap(id: number) {
    loading.value = true
    mapId.value = id
    try {
      const [m, g, pts, pths] = await Promise.all([
        mapsApi.getMap(id),
        mapsApi.getMapGrid(id),
        pointsApi.listPoints(id),
        pathsApi.listPaths(id),
      ])
      mapInfo.value = m
      grid.value = g
      points.value = pts
      paths.value = pths
      // 预加载全部路线边详情，路线无需点击即可显示
      const details: Record<number, PathEdgeVO[]> = {}
      await Promise.all(
        pths.map(async (p) => {
          try {
            const d = await pathsApi.getPath(p.id)
            details[p.id] = d.edges ?? []
          } catch {
            details[p.id] = []
          }
        }),
      )
      pathDetails.value = details
      clearSelection()
      setMode('idle')
      pathEdit.value = null
    } finally {
      loading.value = false
    }
  }

  function setMode(m: EditorMode) {
    mode.value = m
    if (m !== 'measure') measure.value = { a: null, b: null }
  }

  function clearSelection() {
    selectedPointId.value = null
    selectedPathId.value = null
    selectedEdgeIdx.value = null
  }

  // ---------- 点位 ----------
  function selectPoint(id: number | null) {
    selectedPointId.value = id
    if (id != null) selectedPathId.value = null
  }

  async function createPoint(dto: { point_code: string; point_type: PointType; x: number; y: number; yaw: number; remark?: string }) {
    const vo = await pointsApi.createPoint({ map_id: mapId.value, ...dto })
    points.value = [...points.value, vo]
    selectPoint(vo.id)
    return vo
  }

  async function createPointFromRobot(dto: { point_code: string; point_type: PointType; remark?: string }) {
    const vo = await pointsApi.createPointFromCurrentPose({ map_id: mapId.value, ...dto })
    points.value = [...points.value, vo]
    selectPoint(vo.id)
    return vo
  }

  async function updatePoint(id: number, patch: { point_code?: string; point_type?: PointType; x?: number; y?: number; yaw?: number; remark?: string }) {
    const vo = await pointsApi.updatePoint(id, patch)
    points.value = points.value.map((p) => (p.id === id ? vo : p))
    return vo
  }

  async function deletePoint(id: number) {
    await pointsApi.deletePoint(id)
    points.value = points.value.filter((p) => p.id !== id)
    if (selectedPointId.value === id) selectedPointId.value = null
  }

  // ---------- 路线 ----------
  function selectPath(id: number | null) {
    selectedPathId.value = id
    selectedEdgeIdx.value = null
    if (id != null) selectedPointId.value = null
  }

  async function ensurePathDetail(id: number) {
    if (pathDetails.value[id]) return pathDetails.value[id]
    const d = await pathsApi.getPath(id)
    pathDetails.value = { ...pathDetails.value, [id]: d.edges ?? [] }
    return pathDetails.value[id]
  }

  async function refreshPaths() {
    paths.value = await pathsApi.listPaths(mapId.value)
  }

  async function createPath(path_code: string, path_name?: string) {
    const vo = await pathsApi.createPath({ map_id: mapId.value, path_code, path_name })
    paths.value = [vo, ...paths.value]
    selectPath(vo.id)
    return vo
  }

  async function startPathEdit(id: number) {
    const detail = await pathsApi.getPath(id)
    pathDetails.value = { ...pathDetails.value, [id]: detail.edges ?? [] }
    selectPath(id)
    pathEdit.value = { pathId: id, edges: (detail.edges ?? []).map(toEdgeDraft), pendingSourceId: null }
    selectedEdgeIdx.value = null
    setMode('drawPath')
  }

  function cancelPathEdit() {
    pathEdit.value = null
    if (mode.value === 'drawPath') setMode('idle')
  }

  async function savePathEdit() {
    const pe = pathEdit.value
    if (!pe) return
    if (pe.edges.length === 0) throw new Error('路线至少需要一条边')
    const dto = pe.edges.map((e) => ({
      source_point_id: e.source_point_id,
      target_point_id: e.target_point_id,
      edge_type: e.edge_type,
      control_points: e.edge_type === 'CURVE' ? e.control_points : [],
      max_speed: e.max_speed ?? undefined,
      back_up: e.back_up,
      reverse: e.reverse,
    }))
    await pathsApi.putPathEdges(pe.pathId, dto)
    const detail = await pathsApi.getPath(pe.pathId)
    pathDetails.value = { ...pathDetails.value, [pe.pathId]: detail.edges ?? [] }
    await refreshPaths()
    pathEdit.value = null
    setMode('idle')
  }

  /** 画路线模式下点击点位：第一次设起点，之后链式追加边（自动从上一条边的终点继续，保证连续性） */
  function clickPointForPath(pointId: number) {
    const pe = pathEdit.value
    if (!pe) return
    if (pe.pendingSourceId == null) {
      if (pe.edges.length > 0) {
        const lastTarget = pe.edges[pe.edges.length - 1].target_point_id
        if (pointId === lastTarget) {
          pe.pendingSourceId = pointId
          return
        }
        pe.edges.push({
          source_point_id: lastTarget,
          target_point_id: pointId,
          edge_type: 'STRAIGHT',
          control_points: [],
          max_speed: null,
          back_up: false,
          reverse: false,
        })
        selectedEdgeIdx.value = pe.edges.length - 1
        return
      }
      pe.pendingSourceId = pointId
      return
    }
    if (pe.pendingSourceId === pointId) {
      pe.pendingSourceId = null
      return
    }
    pe.edges.push({
      source_point_id: pe.pendingSourceId,
      target_point_id: pointId,
      edge_type: 'STRAIGHT',
      control_points: [],
      max_speed: null,
      back_up: false,
      reverse: false,
    })
    pe.pendingSourceId = pointId
    selectedEdgeIdx.value = pe.edges.length - 1
  }

  function edgeMidpoint(e: PathEdgeDraft): XY {
    const a = pointsById.value.get(e.source_point_id)
    const b = pointsById.value.get(e.target_point_id)
    if (!a || !b) return { x: 0, y: 0 }
    return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
  }

  function edgeToggleType(idx: number) {
    const pe = pathEdit.value
    if (!pe) return
    const e = pe.edges[idx]
    if (!e) return
    if (e.edge_type === 'STRAIGHT') {
      e.edge_type = 'CURVE'
      e.control_points = [edgeMidpoint(e)]
    } else {
      e.edge_type = 'STRAIGHT'
      e.control_points = []
    }
  }

  function edgeAddControlPoint(idx: number) {
    const pe = pathEdit.value
    if (!pe) return
    const e = pe.edges[idx]
    if (!e || e.edge_type !== 'CURVE' || e.control_points.length >= 2) return
    if (e.control_points.length === 0) {
      e.control_points = [edgeMidpoint(e)]
      return
    }
    // 1 → 2：取中点垂直偏移 0.3m
    const mid = edgeMidpoint(e)
    const a = pointsById.value.get(e.source_point_id)!
    const b = pointsById.value.get(e.target_point_id)!
    const len = Math.hypot(b.x - a.x, b.y - a.y) || 1
    const nx = (-(b.y - a.y) / len) * 0.3
    const ny = ((b.x - a.x) / len) * 0.3
    e.control_points = [e.control_points[0], { x: mid.x + nx, y: mid.y + ny }]
  }

  function edgeRemoveControlPoint(idx: number, cpIdx: number) {
    const pe = pathEdit.value
    if (!pe) return
    const e = pe.edges[idx]
    if (!e) return
    e.control_points.splice(cpIdx, 1)
    if (e.control_points.length === 0) e.edge_type = 'STRAIGHT'
  }

  function updateControlPoint(idx: number, cpIdx: number, xy: XY) {
    const e = pathEdit.value?.edges[idx]
    if (!e || !e.control_points[cpIdx]) return
    e.control_points[cpIdx] = { x: +xy.x.toFixed(4), y: +xy.y.toFixed(4) }
  }

  function removeLastEdge() {
    const pe = pathEdit.value
    if (!pe || pe.edges.length === 0) return
    pe.edges.pop()
    pe.pendingSourceId = pe.edges.length > 0 ? pe.edges[pe.edges.length - 1].target_point_id : null
    if ((selectedEdgeIdx.value ?? -1) >= pe.edges.length) selectedEdgeIdx.value = pe.edges.length - 1 >= 0 ? pe.edges.length - 1 : null
  }

  function clearEdgesDraft() {
    const pe = pathEdit.value
    if (!pe) return
    pe.edges = []
    pe.pendingSourceId = null
    selectedEdgeIdx.value = null
  }

  async function updatePathMeta(id: number, dto: { path_name?: string; status?: 'DRAFT' | 'DISABLED' }) {
    await pathsApi.updatePath(id, dto)
    await refreshPaths()
  }

  async function deployPath(id: number) {
    await pathsApi.deployPath(id)
    await refreshPaths()
  }

  async function deletePath(id: number) {
    await pathsApi.deletePath(id)
    paths.value = paths.value.filter((p) => p.id !== id)
    const details = { ...pathDetails.value }
    delete details[id]
    pathDetails.value = details
    if (pathEdit.value?.pathId === id) cancelPathEdit()
    if (selectedPathId.value === id) selectPath(null)
  }

  // ---------- 建图实时预览 ----------
  function startLivePolling() {
    if (livePolling.value) return
    livePolling.value = true
    const tick = async () => {
      try {
        const g = await mapsApi.getLiveGrid()
        grid.value = g
      } catch {
        /* 后端暂无实时图，忽略 */
      }
    }
    void tick()
    liveTimer = window.setInterval(tick, 3000)
  }

  function stopLivePolling() {
    if (liveTimer) window.clearInterval(liveTimer)
    liveTimer = undefined
    livePolling.value = false
    autoLive.value = false
  }

  let liveTimer: number | undefined
  /** 本次预览是否由建图模式自动开启（退出建图时只关自动开启的） */
  const autoLive = ref(false)

  const robotStore = useRobotStore()

  // 离开编辑器时停掉轮询；建图模式下不关，切换地图预览继续
  watch(mapId, () => {
    if (robotStore.status?.mode !== 'MAPPING') stopLivePolling()
  })

  // 建图默认开启实时预览：机器人进入 MAPPING 自动开（刷新后同样生效），退出建图自动关
  watch(
    () => robotStore.status?.mode,
    (mode) => {
      if (mode === 'MAPPING') {
        if (!livePolling.value) {
          startLivePolling()
          autoLive.value = true
        }
      } else if (mode && autoLive.value) {
        stopLivePolling()
      }
    },
    { immediate: true },
  )

  return {
    mapId,
    mapInfo,
    grid,
    points,
    paths,
    pathDetails,
    selectedPointId,
    selectedPathId,
    selectedEdgeIdx,
    mode,
    pathEdit,
    measure,
    layers,
    livePolling,
    loading,
    pointsById,
    selectedPoint,
    selectedPath,
    editingPath,
    loadMap,
    setMode,
    clearSelection,
    selectPoint,
    createPoint,
    createPointFromRobot,
    updatePoint,
    deletePoint,
    selectPath,
    ensurePathDetail,
    refreshPaths,
    createPath,
    startPathEdit,
    cancelPathEdit,
    savePathEdit,
    clickPointForPath,
    edgeToggleType,
    edgeAddControlPoint,
    edgeRemoveControlPoint,
    updateControlPoint,
    removeLastEdge,
    clearEdgesDraft,
    updatePathMeta,
    deployPath,
    deletePath,
    startLivePolling,
    stopLivePolling,
  }
})
