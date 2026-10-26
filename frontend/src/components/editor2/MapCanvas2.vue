<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as THREE from 'three'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MapScene, type PickResult } from '@/three/MapScene'
import { PointLayer, type MapPointLike } from '@/three/PointLayer'
import { PathLayer, type PathRenderItem, type RenderEdge } from '@/three/PathLayer'
import { PolygonLayer, type PolygonRenderItem } from '@/three/PolygonLayer'
import { RobotLayer } from '@/three/RobotLayer'
import { ScanLayer } from '@/three/ScanLayer'
import { registerRobotLayer } from '@/three/robotLayerRef'
import PointLegend from '@/components/editor/PointLegend.vue'
import Map3DViewer from '@/components/editor/Map3DViewer.vue'
import { useMapEditor2Store } from '@/stores/mapEditor2'
import { useRobotStore } from '@/stores/robot'
import { useTasksStore } from '@/stores/tasks'
import { WAYPOINT_TYPE_LABELS, WAYPOINT_CODE_PREFIX, type WaypointType } from '@/types/map2'

/**
 * v2 多楼层地图画布：复用 v1 的 Three.js 场景与全部图层
 * （栅格/点位/路线条带/机器人/轨迹/雷达点云/测量/重定位），
 * 新增多边形图层（区域/货架）。交互与 v1 编辑器保持一致：
 * - 选择：拖点位/旋转手柄改位姿、拖路线段控制点、拖区域/货架顶点与中心手柄
 * - 加点位：点击落点（连续创建）
 * - 画路线：依次点击点位成链（再点末点/回车完成，Backspace 回退）
 * - 画区域/货架：空白拖拽 = 矩形快捷创建；单击 = 多边形逐点（首点吸附闭合/双击/回车完成）
 * - 重定位：按下拖出位姿 → 青色雷达预览贴合确认 → 应用
 * - 测量：点击两点量距
 */
const store = useMapEditor2Store()
const robot = useRobotStore()
const tasks = useTasksStore()

// editable=false 为浏览模式（主页）：可选中/缩放/平移/看机器人，但不可拖拽编辑、不可删除
const props = withDefaults(defineProps<{ editable?: boolean }>(), { editable: true })

const containerRef = ref<HTMLDivElement>()
const coordsText = ref('')

const ZONE_COLORS: Record<string, number> = {
  forbidden: 0xf56c6c,
  slowDown: 0xe6a23c,
  ramp: 0x9254de,
}
const SHELF_COLOR = 0x8d6e63

const hint = computed(() => {
  switch (store.tool) {
    case 'waypoint':
      return '在地图上点击放置点位，保存后可连续创建（Esc 退出）'
    case 'path':
      return store.draftPath.length === 0
        ? '点击起点点位开始画路线（Esc 退出）'
        : `已选 ${store.draftPath.length} 个点位，继续点击途经点位；再点最后一点/回车完成，Backspace 回退`
    case 'zone':
      return store.draftVertices.length === 0
        ? '拖拽画矩形区域，或单击逐点圈多边形（Esc 退出）'
        : '点击加点（靠近绿色起点闭合），双击/回车完成，Backspace 回退，Esc 取消'
    case 'shelf':
      return store.draftVertices.length === 0
        ? '拖拽画矩形货架，或单击逐点圈多边形（Esc 退出）'
        : '点击加点（靠近绿色起点闭合），双击/回车完成，Backspace 回退，Esc 取消'
    case 'measure':
      return '点击两点测量距离（Esc 退出）'
    case 'initialPose':
      return '按下并拖动设置新位姿（方向 = 拖动方向）：松手后青色雷达点云按新位姿重绘，与地图障碍物贴合后再点「应用重定位」'
    default:
      return ''
  }
})

let scene: MapScene | null = null
let pointLayer: PointLayer
let pathLayer: PathLayer
let polygonLayer: PolygonLayer
let robotLayer: RobotLayer
let scanLayer: ScanLayer

// ---------- 拖拽状态 ----------
type DragState =
  | { kind: 'point'; id: number; originX: number; originY: number; originYaw: number; snappedX?: number; snappedY?: number }
  | { kind: 'rotate'; id: number }
  | { kind: 'rotateMaybe'; id: number; originX: number; originY: number; originYaw: number; pressYaw: number; settled?: 'rotate' | 'point'; snappedX?: number; snappedY?: number }
  | { kind: 'control'; edgeIdx: number; cpIdx: number }
  | { kind: 'endpointHandle'; edgeIdx: number; end: 'src' | 'tgt' }
  | { kind: 'vertex'; kindOf: 'zone' | 'shelf'; id: number; vIdx: number }
  | { kind: 'move'; kindOf: 'zone' | 'shelf'; id: number; anchor: { x: number; y: number }; snapshot: number[][] }
let drag: DragState | null = null

// 矩形绘制（zone/shelf 工具按下空白拖拽）
const rectDraft = reactive({
  anchor: null as { x: number; y: number } | null,
  active: false,
})
/** 光标世界坐标（草稿预览用） */
let cursorWorld = { x: 0, y: 0 }

let lastFitMapKey = ''

// ---------- 对齐辅助：拖点时与其他点 x/y 对齐（吸附 + 虚线十字） ----------
const ALIGN_THRESHOLD = 0.08
let alignVLine: THREE.Line | null = null
let alignHLine: THREE.Line | null = null
let alignDiagLine: THREE.Line | null = null

function ensureAlignGuides() {
  if ((alignVLine && alignHLine && alignDiagLine) || !scene) return
  const mat = () => new THREE.LineDashedMaterial({ color: 0x409eff, dashSize: 0.2, gapSize: 0.12 })
  const geo = () => new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()])
  alignVLine = new THREE.Line(geo(), mat())
  alignHLine = new THREE.Line(geo(), mat())
  alignDiagLine = new THREE.Line(geo(), new THREE.LineDashedMaterial({ color: 0x67c23a, dashSize: 0.2, gapSize: 0.12 }))
  for (const line of [alignVLine, alignHLine, alignDiagLine]) {
    line.position.z = 0.15
    line.frustumCulled = false
    line.visible = false
    scene.scene.add(line)
  }
}

/** 共线吸附结果（A 投影到 B-C 连线上） */
interface CollinearSnap {
  x: number
  y: number
  line: { x1: number; y1: number; x2: number; y2: number }
}

/**
 * 与同楼层其他点位的对齐吸附，优先级：
 * 1. x/y 轴对齐（与其他点同 x 或同 y）→ 精确吸附 + 蓝色虚线十字
 * 2. 共线对齐：与其他任意两点 B/C 的连线共线且 A 在 BC 之间 → 吸附到线上 + 绿色虚线连线
 */
function snapToAlign(dragId: number, x: number, y: number): {
  x: number; y: number; alignX: boolean; alignY: boolean; collinear?: CollinearSnap
} {
  const others = store.waypoints.filter((w) => w.id !== dragId)
  let ax: number | null = null
  let ay: number | null = null
  for (const w of others) {
    if (ax == null && Math.abs(w.x - x) < ALIGN_THRESHOLD) ax = w.x
    if (ay == null && Math.abs(w.y - y) < ALIGN_THRESHOLD) ay = w.y
    if (ax != null && ay != null) break
  }
  if (ax != null || ay != null) {
    return { x: ax ?? x, y: ay ?? y, alignX: ax != null, alignY: ay != null }
  }
  // 共线检测：遍历点对 (B, C)，找拖动点到 BC 连线的最近距离
  let best: { dist: number; snap: CollinearSnap } | null = null
  for (let i = 0; i < others.length; i++) {
    for (let j = i + 1; j < others.length; j++) {
      const b = others[i]
      const c = others[j]
      const dx = c.x - b.x
      const dy = c.y - b.y
      const len2 = dx * dx + dy * dy
      if (len2 < 0.01) continue
      const t = ((x - b.x) * dx + (y - b.y) * dy) / len2
      if (t < -0.02 || t > 1.02) continue // A 须在 BC 之间（含微小外扩容差）
      const px = b.x + t * dx
      const py = b.y + t * dy
      const dist = Math.hypot(x - px, y - py)
      if (dist < ALIGN_THRESHOLD && (!best || dist < best.dist)) {
        const len = Math.sqrt(len2)
        const ux = dx / len
        const uy = dy / len
        const ext = 1.2 // 辅助线向两端延伸，便于看清"落在同一条线上"
        best = {
          dist,
          snap: {
            x: px,
            y: py,
            line: { x1: b.x - ux * ext, y1: b.y - uy * ext, x2: c.x + ux * ext, y2: c.y + uy * ext },
          },
        }
      }
    }
  }
  if (best) {
    return { x: best.snap.x, y: best.snap.y, alignX: false, alignY: false, collinear: best.snap }
  }
  return { x, y, alignX: false, alignY: false }
}

/** 显示对齐辅助线：蓝色虚线十字（x/y 对齐）或绿色虚线连线（共线） */
function showAlignGuides(alignX: boolean, alignY: boolean, x: number, y: number, collinear?: CollinearSnap) {
  ensureAlignGuides()
  if (!scene || !alignVLine || !alignHLine || !alignDiagLine) return
  const half = Math.max(
    scene.mapBounds ? Math.max(scene.mapBounds.maxY - scene.mapBounds.minY, scene.mapBounds.maxX - scene.mapBounds.minX) : 20,
    20,
  ) * 0.75
  const setLine = (line: THREE.Line, visible: boolean, x1: number, y1: number, x2: number, y2: number) => {
    if (!visible) {
      line.visible = false
      return
    }
    const g = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(x1, y1, 0),
      new THREE.Vector3(x2, y2, 0),
    ])
    line.geometry.dispose()
    line.geometry = g
    line.computeLineDistances()
    line.visible = true
  }
  setLine(alignVLine, alignX, x, y - half, x, y + half)
  setLine(alignHLine, alignY, x - half, y, x + half, y)
  setLine(alignDiagLine, !!collinear, collinear?.line.x1 ?? 0, collinear?.line.y1 ?? 0, collinear?.line.x2 ?? 0, collinear?.line.y2 ?? 0)
}

function hideAlignGuides() {
  if (alignVLine) alignVLine.visible = false
  if (alignHLine) alignHLine.visible = false
  if (alignDiagLine) alignDiagLine.visible = false
}

// ---------- 重定位（与 v1 相同的两步交互） ----------
let poseAnchor: { x: number; y: number } | null = null
const pendingReloc = ref<{ x: number; y: number; yaw: number } | null>(null)
let posePreview: THREE.Group | null = null
let posePreviewArrow: THREE.Mesh | null = null

// ---------- 视图控制（3D 视角/跟随/顺时针90°旋转/缩放/三维地图） ----------
/** 视图顺时针旋转角（0/90/180/270，四次还原）；兼容旧 nd_view_rot180 存储 */
const viewRot = ref((() => {
  const saved = Number(localStorage.getItem('nd_view_rot'))
  if (Number.isFinite(saved) && saved >= 0) return ((saved % 360) + 360) % 360
  return localStorage.getItem('nd_view_rot180') === '1' ? 180 : 0
})())
const orbit3d = ref(false)
const followCam = ref(false)
const show3d = ref(false)
const map3dName = computed(() => store.map?.robot_map_name || store.map?.map_name || '')

function toggleOrbit3d() {
  orbit3d.value = !orbit3d.value
  scene?.setOrbitView(orbit3d.value)
}

function toggleFollowCam() {
  followCam.value = !followCam.value
  scene?.setFollowEnabled(followCam.value)
}

function rotateView() {
  viewRot.value = (viewRot.value + 90) % 360
  localStorage.setItem('nd_view_rot', String(viewRot.value))
  scene?.setViewRotation(viewRot.value)
}

function zoomIn() {
  scene?.zoomBy(1.25)
}

function zoomOut() {
  scene?.zoomBy(0.8)
}

// ---------- 渲染数据 ----------
const pointLikes = computed<MapPointLike[]>(() =>
  store.waypoints.map((w) => ({
    id: w.id,
    x: w.x,
    y: w.y,
    yaw: w.yaw,
    point_code: w.waypoint_code,
    point_type: w.wp_type,
  })),
)

const pathItems = computed<PathRenderItem[]>(() => {
  const byId = store.waypointById
  const selected = store.selection?.kind === 'path' ? store.selection.id : null
  return store.paths.map((path) => {
    const isSelected = path.id === selected
    const edges: RenderEdge[] = []
    const segments = path.segments ?? []
    for (let idx = 0; idx < segments.length; idx++) {
      const segment = segments[idx]
      const src = byId.get(segment.source_waypoint_id)
      const tgt = byId.get(segment.target_waypoint_id)
      if (!src || !tgt) continue
      edges.push({
        edgeIdx: idx,
        sx: src.x,
        sy: src.y,
        tx: tgt.x,
        ty: tgt.y,
        edgeType: segment.segment_type,
        cps: (segment.control_points ?? []).map((cp) => ({ x: cp[0], y: cp[1] })),
        selected: false,
      })
    }
    // editing=选中：路线加宽 + 显示段控制点手柄（可直接拖拽）
    return {
      id: path.id,
      status: path.status,
      editing: isSelected,
      selected: isSelected,
      bidirectional: path.is_bidirectional,
      edges,
    }
  })
})

const polygonItems = computed<PolygonRenderItem[]>(() => {
  const selectedZone = store.selection?.kind === 'zone' ? store.selection.id : null
  const selectedShelf = store.selection?.kind === 'shelf' ? store.selection.id : null
  const zones: PolygonRenderItem[] = store.zones.map((z) => {
    const color = ZONE_COLORS[z.zone_type] ?? ZONE_COLORS.slowDown
    return {
      id: z.id,
      kind: 'zone',
      label: z.zone_type === 'forbidden' ? `${z.name}·禁行` : `${z.name} ≤${z.max_speed}m/s`,
      vertices: z.vertices,
      stroke: color,
      fill: color,
      fillOpacity: 0.14,
      selected: z.id === selectedZone,
    }
  })
  const shelves: PolygonRenderItem[] = store.shelves.map((s) => ({
    id: s.id,
    kind: 'shelf',
    label: `${s.name}·${s.layers?.length ?? 0}层`,
    vertices: s.vertices,
    stroke: SHELF_COLOR,
    fill: SHELF_COLOR,
    fillOpacity: 0.18,
    selected: s.id === selectedShelf,
  }))
  return [...zones, ...shelves]
})

/** 画路线时链上点位的蓝色高亮环 */
const draftHighlightIds = computed<Set<number> | null>(() =>
  store.tool === 'path' && store.draftPath.length > 0 ? new Set(store.draftPath) : null,
)

function draftColor(): number {
  return store.tool === 'zone' ? (ZONE_COLORS.slowDown as number) : SHELF_COLOR
}

// ---------- 生命周期 ----------
onMounted(() => {
  const el = containerRef.value!
  pointLayer = new PointLayer()
  pathLayer = new PathLayer()
  polygonLayer = new PolygonLayer()
  robotLayer = new RobotLayer()
  scanLayer = new ScanLayer()
  scene = new MapScene(el, {
    onLeftDown: onDown,
    onLeftMove: onMove,
    onLeftUp: onUp,
    onLeftClick: onClick,
    onContext: onCtx,
    onFrame: (dt) => {
      robotLayer?.tick(dt)
      scene?.updateFollow(robotLayer?.currentPose ?? null, dt)
      followCam.value = scene?.isFollowEnabled ?? false
    },
  })
  scene.pickRoot.add(polygonLayer.group, pathLayer.group, pointLayer.group, robotLayer.group, robotLayer.trailGroup)
  scene.scene.add(scanLayer.group)
  // 区域/货架绘制、重定位工具接管左键，关闭左键空白平移（中键/右键仍可平移）
  scene.leftDragPanEnabled = store.tool !== 'zone' && store.tool !== 'shelf' && store.tool !== 'initialPose'
  scene.setViewRotation(viewRot.value)
  registerRobotLayer(robotLayer)

  // 重定位模式的方向预览箭头：大号半透明品红圆底 + 白色描边 + 纯白大箭头（颜色对比清晰）
  posePreview = new THREE.Group()
  const base = new THREE.Mesh(
    new THREE.CircleGeometry(0.36, 32),
    new THREE.MeshBasicMaterial({ color: 0xd946ef, transparent: true, opacity: 0.35, depthWrite: false }),
  )
  base.position.z = 0.08
  const ring = new THREE.Mesh(
    new THREE.RingGeometry(0.36, 0.44, 32),
    new THREE.MeshBasicMaterial({ color: 0xd946ef, side: THREE.DoubleSide }),
  )
  ring.position.z = 0.085
  posePreviewArrow = new THREE.Mesh(
    new THREE.ConeGeometry(0.16, 0.52, 16),
    new THREE.MeshBasicMaterial({ color: 0xffffff, side: THREE.DoubleSide }),
  )
  posePreviewArrow.position.z = 0.09
  posePreview.add(base, ring, posePreviewArrow)
  posePreview.visible = false
  scene.scene.add(posePreview)

  window.addEventListener('keydown', onKey)
  el.addEventListener('pointermove', (ev: PointerEvent) => {
    if (!scene) return
    const w = scene.screenToWorld(ev.clientX, ev.clientY)
    coordsText.value = `${w.x.toFixed(2)} , ${w.y.toFixed(2)} m`
  })
  // 浏览模式（主页）：复位编辑器遗留状态（退出编辑时可能停留在绘制工具/草稿中）
  if (!props.editable) {
    store.setTool('select')
    store.cancelDraft()
  }
  // 实时预览生命周期跟随建图模式：
  // 1) 防御：建图已结束（非 MAPPING）但预览仍开着（跨页面导航/保存后跳转的遗留状态）——直接关掉，
  //    否则带着 livePreview=true 进编辑会被"建图实时预览中不能编辑"拦截；
  // 2) 运行中：机器人退出 MAPPING 时预览数据已停更，继续显示会误导，同样自动关。
  if (store.livePreview && robot.status?.mode !== 'MAPPING') {
    store.stopLivePreview()
  }
  syncAll()
  robotLayer.tryLoadUrdf()
})

/** 把 store 中的当前状态整体刷进场景（挂载/重挂载时） */
function syncAll() {
  if (!scene) return
  applyGrid(store.livePreview ? store.liveGrid : store.grid)
  pointLayer?.sync(pointLikes.value, store.selectedWaypoint?.id ?? null, props.editable, draftHighlightIds.value ?? undefined)
  pathLayer?.render(pathItems.value)
  polygonLayer?.render(polygonItems.value)
  robotLayer?.setTarget(robot.pose)
  robotLayer?.setJointStates(robot.joints)
  robotLayer?.setTfFrames(robot.tfFrames)
  robotLayer?.setTrail(robot.trail)
  scanLayer?.setScan(1, robot.scan1Cloud?.points ?? null, robot.scan1Cloud?.pose ?? null)
  scanLayer?.setScan(2, robot.scan2Cloud?.points ?? null, robot.scan2Cloud?.pose ?? null)
  applyLayers()
  scene.showMeasure(store.measure.a, store.measure.b)
}

function applyGrid(grid: import('@/types/api').MapGridVO | null) {
  if (!scene) return
  if (grid) {
    scene.setMap(grid)
    const key = `${store.mapId}-${store.livePreview ? 'live' : 'saved'}`
    if (scene.mapBounds && lastFitMapKey !== key) {
      scene.fitView(scene.mapBounds)
      lastFitMapKey = key
    }
  } else {
    scene.hideMap()
  }
}

function applyLayers() {
  if (!scene) return
  const meta = !store.livePreview
  scene.mapGroup.visible = store.layers.map
  if (pointLayer) pointLayer.group.visible = meta && store.layers.points
  if (pathLayer) pathLayer.group.visible = meta && store.layers.paths
  if (polygonLayer) polygonLayer.group.visible = meta && (store.layers.zones || store.layers.shelves)
  if (robotLayer) {
    robotLayer.group.visible = store.layers.robot
    robotLayer.trailGroup.visible = store.layers.trail
  }
  scanLayer?.setLayerVisible(store.layers.scan)
}

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  closeCtx()
  registerRobotLayer(null)
  store.stopLivePreview()
  scene?.dispose()
  scene = null
})

defineExpose({
  fitView: () => {
    if (scene?.mapBounds) scene.fitView(scene.mapBounds)
  },
  /** 相机飞到某个点（保持当前缩放） */
  flyToPoint: (x: number, y: number) => scene?.flyTo(x, y),
  /** 相机飞到一组点构成的包围盒 */
  flyToBounds: (pts: { x: number; y: number }[]) => {
    if (!scene || pts.length === 0) return
    const xs = pts.map((p) => p.x)
    const ys = pts.map((p) => p.y)
    scene.fitView({
      minX: Math.min(...xs),
      minY: Math.min(...ys),
      maxX: Math.max(...xs),
      maxY: Math.max(...ys),
    })
  },
})

// ---------- watchers ----------
watch(
  () => [store.grid, store.liveGrid, store.livePreview] as const,
  () => applyGrid(store.livePreview ? store.liveGrid : store.grid),
)

watch(
  () => [pointLikes.value, store.selectedWaypoint?.id, draftHighlightIds.value] as const,
  ([pts, sel, hi]) => pointLayer?.sync(pts, sel ?? null, props.editable, hi ?? undefined),
)

watch(pathItems, (items) => pathLayer?.render(items), { deep: true })

watch(polygonItems, (items) => polygonLayer?.render(items), { deep: true })

watch(
  () => [store.layers.map, store.layers.points, store.layers.paths, store.layers.zones, store.layers.shelves, store.layers.robot, store.layers.trail, store.layers.scan, store.livePreview] as const,
  applyLayers,
)

watch(
  () => robot.scan1Cloud,
  (scan) => scanLayer?.setScan(1, scan?.points ?? null, scan?.pose ?? null),
)
watch(
  () => robot.scan2Cloud,
  (scan) => scanLayer?.setScan(2, scan?.points ?? null, scan?.pose ?? null),
)

watch(
  () => robot.pose,
  (p) => robotLayer?.setTarget(p),
)

watch(
  () => robot.joints,
  (j) => {
    robotLayer?.setJointStates(j)
    if (j) void robotLayer?.tryLoadUrdf()
  },
)

watch(
  () => robot.tfFrames,
  (f) => robotLayer?.setTfFrames(f),
)

watch(
  () => robot.trail,
  (t) => robotLayer?.setTrail(t),
)

watch(
  () => [store.measure.a, store.measure.b] as const,
  ([a, b]) => scene?.showMeasure(a, b),
)

watch(
  () => store.tool,
  (tool) => {
    polygonLayer?.hideDraft()
    pathLayer?.showPreview(null, null)
    hidePosePreview()
    if (scene) {
      scene.leftDragPanEnabled = tool !== 'zone' && tool !== 'shelf' && tool !== 'initialPose'
    }
  },
)

// 路线草稿清空（完成/取消）时清理预览线
watch(
  () => store.draftPath.length,
  (len) => {
    if (len === 0) pathLayer?.showPreview(null, null)
  },
)

// 机器人退出建图：实时预览随之结束（/map 已停更，继续显示实时帧会误导编辑）
watch(
  () => robot.status?.mode,
  (mode, was) => {
    if (was === 'MAPPING' && mode && mode !== 'MAPPING' && store.livePreview) {
      store.stopLivePreview()
    }
  },
)

watch(
  () => store.currentFloorId,
  () => {
    polygonLayer?.hideDraft()
    pathLayer?.showPreview(null, null)
  },
)

// ---------- 交互 ----------
function hidePosePreview() {
  if (posePreview) posePreview.visible = false
  poseAnchor = null
  pendingReloc.value = null
  scanLayer?.setRelocalizePreview(null)
}

function onDown(hit: PickResult | null, world: THREE.Vector3) {
  if (!scene) return
  if (store.tool === 'initialPose') {
    // 重定位是机器人操作：浏览模式（主页）同样允许
    poseAnchor = { x: world.x, y: world.y }
    if (posePreview) {
      posePreview.position.set(world.x, world.y, 0)
      posePreview.visible = true
    }
    return
  }
  // 区域/货架绘制模式：按下即记录矩形锚点（忽略已有实体，绘制优先）
  if ((store.tool === 'zone' || store.tool === 'shelf') && props.editable) {
    rectDraft.anchor = { x: world.x, y: world.y }
    rectDraft.active = false
    return
  }
  if (store.tool !== 'select' || !props.editable) return
  if (!hit) return
  if ((hit.type === 'point' || hit.type === 'pointArrow') && hit.id != null) {
    // 圆盘体 = 拖动改 x/y；朝向箭头 = 拖动绕圆心旋转改 yaw。
    // 箭头命中易误触（占前半圆，拖位置时经常从圆盘前侧下手）：onUp 时根据实际拖动方向判别——
    // 按下点在箭头区、但拖动主要发生在垂直于箭头的方向 → 视为移动点位而非旋转。
    const p = store.waypointById.get(hit.id)
    if (p) {
      if (hit.type === 'point') {
        drag = { kind: 'point', id: hit.id, originX: p.x, originY: p.y, originYaw: p.yaw }
      } else {
        drag = {
          kind: 'rotateMaybe',
          id: hit.id,
          originX: p.x,
          originY: p.y,
          originYaw: p.yaw,
          pressYaw: Math.atan2(world.y - p.y, world.x - p.x),
        }
      }
    }
  } else if (hit.type === 'rotateHandle' && hit.id != null) {
    drag = { kind: 'rotate', id: hit.id }
  } else if (hit.type === 'control' && hit.edgeIdx != null && hit.cpIdx != null) {
    drag = { kind: 'control', edgeIdx: hit.edgeIdx, cpIdx: hit.cpIdx }
  } else if (hit.type === 'endpointHandle' && hit.edgeIdx != null && hit.end) {
    drag = { kind: 'endpointHandle', edgeIdx: hit.edgeIdx, end: hit.end as 'src' | 'tgt' }
  } else if ((hit.type === 'zoneVertex' || hit.type === 'shelfVertex') && hit.id != null && hit.vIdx != null) {
    drag = { kind: 'vertex', kindOf: hit.type === 'zoneVertex' ? 'zone' : 'shelf', id: hit.id, vIdx: hit.vIdx }
  } else if ((hit.type === 'zoneMove' || hit.type === 'shelfMove') && hit.id != null) {
    const kindOf = hit.type === 'zoneMove' ? 'zone' : 'shelf'
    const entity = kindOf === 'zone'
      ? store.zones.find((z) => z.id === hit.id)
      : store.shelves.find((s) => s.id === hit.id)
    if (entity) {
      drag = {
        kind: 'move',
        kindOf,
        id: hit.id,
        anchor: { x: world.x, y: world.y },
        snapshot: (entity.vertices ?? []).map((v) => [...v]),
      }
    }
  }
}

function onMove(world: THREE.Vector3) {
  cursorWorld = { x: world.x, y: world.y }
  // 重定位拖拽预览
  if (store.tool === 'initialPose' && poseAnchor && posePreview && posePreviewArrow) {
    const yaw = Math.atan2(world.y - poseAnchor.y, world.x - poseAnchor.x)
    posePreview.position.set(poseAnchor.x, poseAnchor.y, 0)
    posePreviewArrow.rotation.z = yaw - Math.PI / 2
    scanLayer?.setRelocalizePreview({ x: poseAnchor.x, y: poseAnchor.y, yaw })
    return
  }
  // 矩形拖拽预览
  if (rectDraft.anchor) {
    const dist = Math.hypot(world.x - rectDraft.anchor.x, world.y - rectDraft.anchor.y)
    if (dist > 0.15) {
      rectDraft.active = true
      polygonLayer?.showRect(rectDraft.anchor, { x: world.x, y: world.y }, draftColor())
    }
    return
  }
  // 路线链预览线
  if (store.tool === 'path' && store.draftPath.length > 0) {
    const last = store.waypointById.get(store.draftPath[store.draftPath.length - 1])
    if (last) pathLayer?.showPreview({ x: last.x, y: last.y }, { x: world.x, y: world.y })
    return
  }
  // 区域/货架多边形草稿预览
  if ((store.tool === 'zone' || store.tool === 'shelf') && store.draftVertices.length > 0) {
    polygonLayer?.showDraft(store.draftVertices, { x: world.x, y: world.y }, draftColor())
    return
  }
  if (!drag) return
  const d = drag
  // 箭头区按下后的意图判别（rotateMaybe）：移动距离超过阈值时根据方向定夺——
  // 拖动方向与箭头轴夹角小（沿箭头方向来回拉）= 旋转；垂直于箭头方向拖 = 移动位置（误触纠正）
  if (d.kind === 'rotateMaybe' && !d.settled) {
    const p = store.waypointById.get(d.id)
    if (!p) return
    const moveDx = world.x - p.x
    const moveDy = world.y - p.y
    const moveDist = Math.hypot(moveDx, moveDy)
    if (moveDist < 0.12) return // 未超过判别阈值：保持待定（不产生预览）
    // 拖动向量与箭头轴的夹角：|cos| > 0.72（约±44°）视为沿箭头方向 → 旋转
    const cos = Math.abs((moveDx * Math.cos(d.pressYaw) + moveDy * Math.sin(d.pressYaw)) / moveDist)
    d.settled = cos > 0.72 ? 'rotate' : 'point'
    if (d.settled === 'point') {
      // 转为移动：直接开始跟随光标
      pointLayer?.setPose(d.id, world.x, world.y, d.originYaw)
    }
  }
  if (d.kind === 'rotateMaybe' && d.settled === 'point') {
    const snapped = snapToAlign(d.id, world.x, world.y)
    d.snappedX = snapped.x
    d.snappedY = snapped.y
    pointLayer?.setPose(d.id, snapped.x, snapped.y, d.originYaw)
    showAlignGuides(snapped.alignX, snapped.alignY, snapped.x, snapped.y, snapped.collinear)
    return
  }
  if (d.kind === 'point') {
    // 对齐吸附：x/y 轴对齐（蓝十字）优先，其次共线吸附（与任意两点连线共线，绿连线）
    const snapped = snapToAlign(d.id, world.x, world.y)
    d.snappedX = snapped.x
    d.snappedY = snapped.y
    pointLayer?.setPose(d.id, snapped.x, snapped.y, d.originYaw)
    showAlignGuides(snapped.alignX, snapped.alignY, snapped.x, snapped.y, snapped.collinear)
  } else if (d.kind === 'rotate' || (d.kind === 'rotateMaybe' && d.settled === 'rotate')) {
    const p = store.waypointById.get(d.id)
    if (p) {
      // 旋转角度吸附：接近 45° 倍数时轻微吸附（±3°），方便摆正
      let yaw = Math.atan2(world.y - p.y, world.x - p.x)
      const snapRad = (Math.PI / 4) * Math.round(yaw / (Math.PI / 4))
      if (Math.abs(yaw - snapRad) < (3 * Math.PI) / 180) yaw = snapRad
      pointLayer?.setPose(d.id, p.x, p.y, yaw)
    }
  } else if (d.kind === 'control') {
    const path = store.selectedPath
    const segment = path?.segments?.[d.edgeIdx]
    if (segment) {
      // 直线段拖中点 → 即时转为曲线，在 1/4 和 3/4 位置生成两个控制点（始终 2 个）
      if (!segment.control_points || segment.control_points.length === 0) {
        segment.segment_type = 'CURVE'
        const src = store.waypointById.get(segment.source_waypoint_id)
        const tgt = store.waypointById.get(segment.target_waypoint_id)
        if (src && tgt) {
          const cp1x = src.x + (tgt.x - src.x) * 0.25
          const cp1y = src.y + (tgt.y - src.y) * 0.25
          const cp2x = src.x + (tgt.x - src.x) * 0.75
          const cp2y = src.y + (tgt.y - src.y) * 0.75
          segment.control_points = [
            [+cp1x.toFixed(4), +cp1y.toFixed(4)],
            [+cp2x.toFixed(4), +cp2y.toFixed(4)],
          ]
        } else {
          segment.control_points = [[+world.x.toFixed(4), +world.y.toFixed(4)], [+world.x.toFixed(4), +world.y.toFixed(4)]]
        }
      }
      // 更新拖动中的控制点：替换整个 control_points 数组引用（确保 Vue 深层响应式可靠触发 → 曲线+虚线实时重绘）
      const updated = segment.control_points.map((cp, i) =>
        i === d.cpIdx ? [+world.x.toFixed(4), +world.y.toFixed(4)] : [...cp],
      )
      segment.control_points = updated
    }
  } else if (d.kind === 'endpointHandle') {
    // 端点切线手柄：拖动即把该段转为三次贝塞尔，拖动位置 = 该端的控制点
    const path = store.selectedPath
    const segment = path?.segments?.[d.edgeIdx]
    if (segment) {
      const cx = +world.x.toFixed(4)
      const cy = +world.y.toFixed(4)
      segment.segment_type = 'CURVE'
      if (!segment.control_points || segment.control_points.length === 0) {
        segment.control_points = d.end === 'src' ? [[cx, cy], [cx, cy]] : [[cx, cy], [cx, cy]]
      } else if (segment.control_points.length === 1) {
        const existing = segment.control_points[0]
        segment.control_points = d.end === 'src'
          ? [[cx, cy], [existing[0], existing[1]]]
          : [[existing[0], existing[1]], [cx, cy]]
      } else {
        // 替换整个数组引用确保响应式可靠触发（曲线+虚线实时重绘）
        const idx = d.end === 'src' ? 0 : segment.control_points.length - 1
        segment.control_points = segment.control_points.map((cp, i) =>
          i === idx ? [cx, cy] : [...cp],
        )
      }
    }
  } else if (d.kind === 'vertex') {
    const entity = d.kindOf === 'zone'
      ? store.zones.find((z) => z.id === d.id)
      : store.shelves.find((s) => s.id === d.id)
    if (entity?.vertices && entity.vertices[d.vIdx]) {
      entity.vertices[d.vIdx] = [+world.x.toFixed(4), +world.y.toFixed(4)]
    }
  } else if (d.kind === 'move') {
    const dx = world.x - d.anchor.x
    const dy = world.y - d.anchor.y
    const entity = d.kindOf === 'zone'
      ? store.zones.find((z) => z.id === d.id)
      : store.shelves.find((s) => s.id === d.id)
    if (entity) {
      entity.vertices = d.snapshot.map(([x, y]) => [+(x + dx).toFixed(4), +(y + dy).toFixed(4)])
    }
  }
}

async function onUp(_hit: PickResult | null, world: THREE.Vector3) {
  // 重定位：拖出位姿后不立即提交——保留雷达点云预览（青色），确认贴合后再应用
  if (store.tool === 'initialPose' && poseAnchor) {
    const anchor = poseAnchor
    poseAnchor = null
    const dist = Math.hypot(world.x - anchor.x, world.y - anchor.y)
    const yaw = dist < 0.15 ? (pendingReloc.value?.yaw ?? 0) : Math.atan2(world.y - anchor.y, world.x - anchor.x)
    pendingReloc.value = { x: anchor.x, y: anchor.y, yaw }
    scanLayer?.setRelocalizePreview(pendingReloc.value)
    return
  }
  // 区域/货架绘制：矩形拖拽 or 单击加点
  if ((store.tool === 'zone' || store.tool === 'shelf') && props.editable) {
    if (rectDraft.anchor) {
      const anchor = rectDraft.anchor
      const kind = store.tool
      rectDraft.anchor = null
      polygonLayer?.hideDraft()
      if (rectDraft.active) {
        rectDraft.active = false
        const w = Math.abs(world.x - anchor.x)
        const h = Math.abs(world.y - anchor.y)
        if (w > 0.15 && h > 0.15) {
          try {
            await store.submitRect(kind, anchor, { x: world.x, y: world.y })
            ElMessage.success(kind === 'zone' ? '矩形区域已创建，可在右侧调整类型/限速' : '矩形货架已创建，可在右侧编辑层与格位')
          } catch {
            /* 实时预览保护等已在 store 层提示 */
          }
          return
        }
      } else {
        // 单击：加点 / 闭合 / 双击完成
        try {
          await addDraftVertex(kind, { x: world.x, y: world.y })
        } catch {
          /* 保护提示已由 store 层给出 */
        }
      }
    }
    return
  }
  if (!drag) return
  const d = drag
  drag = null
  hideAlignGuides()
  try {
    if (d.kind === 'point' || (d.kind === 'rotateMaybe' && d.settled === 'point')) {
      const p = store.waypointById.get(d.id)
      const finalX = d.snappedX ?? d.originX
      const finalY = d.snappedY ?? d.originY
      if (p && (Math.abs(finalX - d.originX) > 1e-4 || Math.abs(finalY - d.originY) > 1e-4)) {
        await store.patchWaypoint(d.id, { x: +finalX.toFixed(4), y: +finalY.toFixed(4), yaw: p.yaw, modified_by: store.modifiedBy })
      } else {
        pointLayer?.setPose(d.id, d.originX, d.originY, d.originYaw)
      }
    } else if (d.kind === 'rotate' || (d.kind === 'rotateMaybe' && d.settled === 'rotate')) {
      // 旋转终值取图层上的最新朝向（拖拽中已更新）
      const p = store.waypointById.get(d.id)
      if (p) {
        const layerYaw = pointLayer?.getVisualYaw(d.id)
        await store.patchWaypoint(d.id, { yaw: +(layerYaw ?? p.yaw).toFixed(4), modified_by: store.modifiedBy })
      }
    } else if (d.kind === 'control' || d.kind === 'endpointHandle') {
      const path = store.selectedPath
      if (path && path.segments?.length) {
        // 始终保存全部控制点（含未拖动的默认位置 CP）——渲染层根据 CP2 是否在默认位置决定二阶/三阶
        await store.patchPath(path.id, {
          waypoint_ids: path.waypoint_ids,
          segments: path.segments.map((segment) => ({
            source_waypoint_id: segment.source_waypoint_id,
            target_waypoint_id: segment.target_waypoint_id,
            segment_type: segment.segment_type,
            control_points: segment.control_points?.length ? segment.control_points : undefined,
            max_speed: segment.max_speed,
            back_up: segment.back_up,
            reverse: segment.reverse,
          })),
          modified_by: store.modifiedBy,
        })
      }
    } else if (d.kind === 'vertex' || d.kind === 'move') {
      const entity = d.kindOf === 'zone'
        ? store.zones.find((z) => z.id === d.id)
        : store.shelves.find((s) => s.id === d.id)
      if (entity) {
        if (d.kindOf === 'zone') await store.saveZoneVertices(d.id, entity.vertices)
        else await store.saveShelfVertices(d.id, entity.vertices)
      }
    }
  } catch {
    if (d.kind === 'point' || d.kind === 'rotateMaybe') pointLayer?.setPose(d.id, d.originX, d.originY, d.originYaw)
    await store.reloadMap()
  }
}

/** 多边形草稿加点：近首点闭合 / 双击（近上一点）完成。 */
async function addDraftVertex(kind: 'zone' | 'shelf', p: { x: number; y: number }) {
  const vs = store.draftVertices
  const done = () => ElMessage.success(kind === 'zone' ? '区域已创建，可在右侧调整类型/限速' : '货架已创建，可在右侧编辑层与格位')
  if (vs.length >= 3 && Math.hypot(p.x - vs[0][0], p.y - vs[0][1]) < 0.3) {
    await store.submitDraftPolygon(kind)
    done()
    return
  }
  if (vs.length > 0 && Math.hypot(p.x - vs[vs.length - 1][0], p.y - vs[vs.length - 1][1]) < 0.05) {
    await store.submitDraftPolygon(kind)
    done()
    return
  }
  vs.push([+p.x.toFixed(4), +p.y.toFixed(4)])
  polygonLayer?.showDraft(vs, cursorWorld, draftColor())
}

function onClick(hit: PickResult | null, world: THREE.Vector3) {
  // 区域/货架的加点在 onUp 统一处理（与矩形拖拽互斥），这里只负责其余工具
  if (store.tool === 'measure') {
    const m = store.measure
    if (!m.a || (m.a && m.b)) store.measure = { a: { x: world.x, y: world.y }, b: null }
    else store.measure = { a: m.a, b: { x: world.x, y: world.y } }
    return
  }
  if (store.tool === 'waypoint' && props.editable) {
    openPlaceDialog({ x: world.x, y: world.y })
    return
  }
  if (store.tool === 'path' && props.editable) {
    if (hit?.type === 'point' && hit.id != null) {
      // 再点链尾最后一点 = 完成绘制；其余点击（含重复点，支持回环 A→B→C→A）追加
      if (store.draftPath.length > 0 && store.draftPath[store.draftPath.length - 1] === hit.id) {
        store.submitDraftPath().catch(() => {
          /* 保护提示已由 store 层给出 */
        })
        return
      }
      store.draftPath.push(hit.id)
      polygonLayer?.hideDraft()
    } else {
      ElMessage.warning('请点击一个点位作为路线节点')
    }
    return
  }
  if (store.tool === 'select') {
    if ((hit?.type === 'point' || hit?.type === 'pointArrow' || hit?.type === 'rotateHandle') && hit.id != null) {
      store.select('waypoint', hit.id)
    } else if (hit?.type === 'path' && hit.id != null) {
      store.select('path', hit.id)
    } else if (hit?.type === 'zone' && hit.id != null) {
      store.select('zone', hit.id)
    } else if (hit?.type === 'shelf' && hit.id != null) {
      store.select('shelf', hit.id)
    } else {
      store.select('waypoint', null)
    }
  }
}

/** 应用重定位预览：提交临时位姿（/agv/relocalize），结束预览 */
async function applyReloc() {
  const pending = pendingReloc.value
  if (!pending) return
  try {
    await robot.setInitialPose(+pending.x.toFixed(4), +pending.y.toFixed(4), +pending.yaw.toFixed(4))
    ElMessage.success('初始位姿已发送，等待重定位…')
    store.setTool('select')
    hidePosePreview()
  } catch {
    /* http 层已提示 */
  }
}

/** 取消重定位预览（退出重定位模式） */
function cancelReloc() {
  store.setTool('select')
  hidePosePreview()
}

// ---------- 建点确认弹窗（v1 交互：类型点选 + 类型首字母自动编码 + 连续创建） ----------
const placeDialog = reactive({
  visible: false,
  saving: false,
  code: '',
  type: 'path' as WaypointType,
  x: 0,
  y: 0,
  yawDeg: 0,
  /** 来源：robot = 机器人右键建点（坐标/朝向预填，提示文案不同） */
  fromRobot: false,
})

/** 类型切换时自动重算编码（编码未被手动改过时，v1 同款逻辑）。 */
watch(
  () => placeDialog.type,
  (type, previous) => {
    if (!placeDialog.visible) return
    if (!placeDialog.code.trim() || codeIsAuto(placeDialog.code, previous)) {
      placeDialog.code = store.nextWaypointCode(type)
    }
  },
  { flush: 'sync' },
)

function codeIsAuto(code: string, type?: WaypointType): boolean {
  if (!type) return false
  return code === store.nextWaypointCode(type)
}

function openPlaceDialog(p: { x: number; y: number; yawDeg?: number; fromRobot?: boolean }) {
  placeDialog.x = +p.x.toFixed(4)
  placeDialog.y = +p.y.toFixed(4)
  placeDialog.yawDeg = p.yawDeg ?? 0
  placeDialog.type = 'path'
  placeDialog.code = store.nextWaypointCode('path')
  placeDialog.fromRobot = !!p.fromRobot
  placeDialog.visible = true
}

async function savePlacePoint() {
  const code = placeDialog.code.trim()
  if (!code) {
    ElMessage.warning('请输入点位编码')
    return
  }
  placeDialog.saving = true
  try {
    const vo = await store.addWaypoint(placeDialog.x, placeDialog.y, (placeDialog.yawDeg * Math.PI) / 180, {
      waypoint_code: code,
      wp_type: placeDialog.type,
    })
    placeDialog.visible = false
    ElMessage.success(
      `点位「${vo?.waypoint_code ?? code}」已创建${placeDialog.fromRobot ? '（机器人位姿，含朝向）' : ''}，可继续点击地图放置下一个点位（Esc 退出）`,
    )
  } catch {
    /* http 层已提示 */
  } finally {
    placeDialog.saving = false
  }
}

async function onKey(ev: KeyboardEvent) {
  const tag = document.activeElement?.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA') return
  // 建点弹窗打开时 Esc 只关弹窗（el-dialog 自行处理），不退出工具/清空选择
  if (ev.key === 'Escape' && placeDialog.visible) return
  if (ev.key === 'Escape') {
    if (drag) {
      const d = drag
      drag = null
      if (d.kind === 'point') pointLayer?.setPose(d.id, d.originX, d.originY, d.originYaw)
      hidePosePreview()
      return
    }
    rectDraft.anchor = null
    rectDraft.active = false
    if (store.draftPath.length > 0 || store.draftVertices.length > 0) {
      store.cancelDraft()
      polygonLayer?.hideDraft()
      pathLayer?.showPreview(null, null)
      return
    }
    if (store.tool !== 'select') {
      store.setTool('select')
      return
    }
    store.select('waypoint', null)
  } else if (ev.key === 'Enter') {
    if (!props.editable) return
    try {
      if (store.tool === 'path' && store.draftPath.length >= 2) {
        await store.submitDraftPath()
      } else if ((store.tool === 'zone' || store.tool === 'shelf') && store.draftVertices.length >= 3) {
        await store.submitDraftPolygon(store.tool)
      }
    } catch {
      /* 保护提示已由 store 层给出 */
    }
  } else if (ev.key === 'Backspace') {
    if (store.tool === 'path') store.popDraftPath()
    else if (store.tool === 'zone' || store.tool === 'shelf') store.popDraftVertex()
    if (store.tool === 'path' && store.draftPath.length === 0) pathLayer?.showPreview(null, null)
    if ((store.tool === 'zone' || store.tool === 'shelf') && store.draftVertices.length === 0) polygonLayer?.hideDraft()
  } else if (ev.key === 'Delete') {
    if (!props.editable) return
    const selection = store.selection
    if (!selection) return
    const labels: Record<string, () => string | null> = {
      waypoint: () => store.selectedWaypoint?.waypoint_code ?? null,
      zone: () => store.selectedZone?.name ?? null,
      shelf: () => store.selectedShelf?.name ?? null,
      path: () => store.selectedPath?.path_code ?? null,
      conn: () => null,
    }
    const label = labels[selection.kind]?.()
    if (!label) return
    const kindLabel = { waypoint: '点位', zone: '区域', shelf: '货架', path: '路线', conn: '连接' }[selection.kind]
    await confirmDelete(kindLabel, label, async () => {
      if (selection.kind === 'waypoint') await store.removeWaypoint(selection.id)
      else if (selection.kind === 'zone') await store.removeZone(selection.id)
      else if (selection.kind === 'shelf') await store.removeShelf(selection.id)
      else if (selection.kind === 'path') await store.removePath(selection.id)
    })
  }
}

// ---------- 右键菜单（对齐 v1：机器人建点 / 定位 / 删除 / 空白建点） ----------
interface CtxItem {
  label: string
  danger?: boolean
  action: () => void
}
const ctx = reactive({ visible: false, x: 0, y: 0, items: [] as CtxItem[] })

function closeCtx() {
  ctx.visible = false
  window.removeEventListener('click', closeCtx)
}

async function confirmDelete(kindLabel: string, label: string, run: () => Promise<void>) {
  await ElMessageBox.confirm(`确认删除${kindLabel}「${label}」？`, `删除${kindLabel}`, { type: 'warning' })
  await run()
  ElMessage.success('已删除')
}

function flyToBoundsInternal(pts: { x: number; y: number }[]) {
  if (!scene || pts.length === 0) return
  if (pts.length === 1) {
    scene.flyTo(pts[0].x, pts[0].y)
    return
  }
  const xs = pts.map((p) => p.x)
  const ys = pts.map((p) => p.y)
  scene.fitView({ minX: Math.min(...xs), minY: Math.min(...ys), maxX: Math.max(...xs), maxY: Math.max(...ys) })
}

function entityBounds(kind: 'zone' | 'shelf' | 'path', id: number): { x: number; y: number }[] {
  if (kind === 'path') {
    const path = store.paths.find((p) => p.id === id)
    const pts: { x: number; y: number }[] = []
    for (const segment of path?.segments ?? []) {
      const source = store.waypointById.get(segment.source_waypoint_id)
      const target = store.waypointById.get(segment.target_waypoint_id)
      if (source) pts.push({ x: source.x, y: source.y })
      if (target) pts.push({ x: target.x, y: target.y })
    }
    return pts
  }
  const entity = kind === 'zone'
    ? store.zones.find((z) => z.id === id)
    : store.shelves.find((s) => s.id === id)
  return (entity?.vertices ?? []).map((v) => ({ x: v[0], y: v[1] }))
}

function mapBoundsFit() {
  if (scene?.mapBounds) scene.fitView(scene.mapBounds)
}

// ---------- 拓扑导航：调后端 Dijkstra 路径规划（禁行区/减速区/跨楼层） ----------
import { planRouteV2 } from '@/api/map2'

/** 调后端规划 API，返回路线 ID 序列；无路返回 null */
async function planRoute(targetWaypointId: number): Promise<{ pathIds: number[]; totalCost: number } | null> {
  const pose = robot.pose
  if (!pose || !store.map) return null
  // 找机器人最近的点位作为起点
  let nearest: { id: number; dist: number } | null = null
  for (const w of store.waypoints) {
    const dist = Math.hypot(w.x - pose.x, w.y - pose.y)
    if (!nearest || dist < nearest.dist) {
      nearest = { id: w.id, dist }
    }
  }
  if (!nearest) return null
  try {
    const plan = await planRouteV2(store.map.id, nearest.id, targetWaypointId)
    if (!plan.found || plan.steps.length === 0) return null
    // 提取非跨楼层的路线段 ID（跨楼层段由后续迭代支持）
    const pathIds = plan.steps.filter(s => !s.is_cross_floor && s.path_id > 0).map(s => s.path_id)
    if (pathIds.length === 0) return null
    return { pathIds, totalCost: plan.total_cost }
  } catch {
    return null
  }
}

/** 执行路线链：逐段 FOLLOW_PATH，前一段完成后自动发起下一段；防重入 */
let routeExecuting = false

async function executeTopologyRoute(pathIds: number[], targetLabel: string) {
  if (routeExecuting) {
    ElMessage.warning('导航已在执行中，请等待完成或先取消')
    return
  }
  routeExecuting = true
  try {
    // 去除连续重复的路线 ID（多段路线可能包含同一路线的多个段）
    const deduped = pathIds.filter((id, i) => i === 0 || id !== pathIds[i - 1])
    const total = deduped.length
    for (let i = 0; i < total; i++) {
      const pathId = deduped[i]
      const path = store.paths.find((p) => p.id === pathId)
      if (!path) {
        ElMessage.error(`路线 ${pathId} 不存在，导航中断`)
        return
      }
      const isLast = i === total - 1
      try {
        await tasks.followPath(pathId)
        if (total > 1) {
          ElMessage.info(`导航 ${i + 1}/${total}：${path.path_code}${isLast ? `（终点 ${targetLabel}）` : '，到达后自动继续'}`)
        }
        if (!isLast) {
          const completed = await waitForTaskCompletion()
          if (!completed) {
            ElMessage.warning(`导航在第 ${i + 1} 段中断，已到达 ${path.path_code}`)
            return
          }
        }
      } catch {
        ElMessage.error(`导航失败：${path.path_code}`)
        return
      }
    }
    if (total > 1) {
      ElMessage.success(`导航完成：共 ${total} 段，已到达 ${targetLabel}`)
    }
  } finally {
    routeExecuting = false
  }
}

/** 等待当前移动任务到达终态（成功/失败/取消/超时），最多等 300s */
function waitForTaskCompletion(): Promise<boolean> {
  return new Promise((resolve) => {
    const timeout = setTimeout(() => {
      cleanup()
      resolve(false)
    }, 300_000)
    const check = () => {
      const task = tasks.activeMoveTask
      if (!task || ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT'].includes(task.status)) {
        cleanup()
        resolve(task?.status === 'SUCCEEDED')
      }
    }
    const unwatch = watch(() => tasks.activeMoveTask?.status, check, { immediate: true })
    const interval = setInterval(check, 1000)
    const cleanup = () => {
      clearTimeout(timeout)
      clearInterval(interval)
      unwatch()
    }
  })
}

function onCtx(hit: PickResult | null, world: THREE.Vector3, ev: MouseEvent) {  // 绘制/测量等工具态不弹菜单（右键留给后续可能的工具快捷操作）
  if (store.tool !== 'select') return
  const items: CtxItem[] = []
  if (hit?.type === 'robot') {
    // 「以机器人当前位置创建点位」是编辑动作：仅编辑模式提供；浏览模式右键机器人不弹菜单
    const pose = robot.pose
    if (pose && props.editable) {
      items.push({
        label: `以机器人当前位置创建点位（θ ${Math.round((pose.yaw * 180) / Math.PI)}°）`,
        action: () => openPlaceDialog({
          x: pose.x,
          y: pose.y,
          yawDeg: (pose.yaw * 180) / Math.PI,
          fromRobot: true,
        }),
      })
    }
  } else if ((hit?.type === 'point' || hit?.type === 'pointArrow') && hit.id != null) {
    const waypoint = store.waypointById.get(hit.id)
    if (waypoint) {
      store.select('waypoint', waypoint.id)
      // 浏览模式（主页）：沿路线导航（后端 Dijkstra 规划）+ 直线导航
      if (!props.editable) {
        const guardNav = () => {
          if (!robot.status?.pose_initialized) {
            ElMessage.warning('机器人位姿未初始化，无法导航')
            return false
          }
          if (tasks.moveTaskInFlight) {
            ElMessage.warning('已有执行中的移动任务，请先取消')
            return false
          }
          return true
        }
        // 沿路线导航：调后端规划，有路则显示，无路提示
        items.push({
          label: '沿路线导航',
          action: async () => {
            if (!guardNav()) return
            const route = await planRoute(waypoint.id)
            if (!route || route.pathIds.length === 0) {
              ElMessage.warning('没有已部署的路线可到达此点位，请先绘制并部署路线')
              return
            }
            // planRoute 是异步的，期间可能有任务在途 → 二次校验
            if (tasks.moveTaskInFlight) {
              ElMessage.warning('已有执行中的移动任务，请先取消')
              return
            }
            void executeTopologyRoute(route.pathIds, waypoint.waypoint_code)
          },
        })
        items.push({
          label: '直线导航',
          action: () => {
            if (guardNav()) void tasks.navigateToPose(waypoint.x, waypoint.y, waypoint.yaw)
          },
        })
        // 自由到点：同空白处右键，但目标为点位位姿（朝向取点位朝向）
        items.push({
          label: `自由到点（θ ${Math.round((waypoint.yaw * 180) / Math.PI)}°）`,
          action: () => {
            if (guardNav()) void tasks.freeNavigate(waypoint.x, waypoint.y, waypoint.yaw)
          },
        })
      }
      items.push({ label: '定位到此', action: () => scene?.flyTo(waypoint.x, waypoint.y) })
      if (props.editable) {
        items.push({
          label: '删除点位',
          danger: true,
          action: () => void confirmDelete('点位', waypoint.waypoint_code, () => store.removeWaypoint(waypoint.id)),
        })
      }
    }
  } else if (hit?.type === 'path' && hit.id != null) {
    const path = store.paths.find((p) => p.id === hit.id)
    if (path) {
      store.select('path', path.id)
      items.push({ label: '定位到路线', action: () => flyToBoundsInternal(entityBounds('path', path.id)) })
      if (props.editable) {
        items.push({
          label: '删除路线',
          danger: true,
          action: () => void confirmDelete('路线', path.path_code, () => store.removePath(path.id)),
        })
      }
    }
  } else if (hit?.type === 'zone' && hit.id != null) {
    const zone = store.zones.find((z) => z.id === hit.id)
    if (zone) {
      store.select('zone', zone.id)
      items.push({ label: '定位到区域', action: () => flyToBoundsInternal(entityBounds('zone', zone.id)) })
      if (props.editable) {
        items.push({
          label: '删除区域',
          danger: true,
          action: () => void confirmDelete('区域', zone.name, () => store.removeZone(zone.id)),
        })
      }
    }
  } else if (hit?.type === 'shelf' && hit.id != null) {
    const shelf = store.shelves.find((s) => s.id === hit.id)
    if (shelf) {
      store.select('shelf', shelf.id)
      items.push({ label: '定位到货架', action: () => flyToBoundsInternal(entityBounds('shelf', shelf.id)) })
      if (props.editable) {
        items.push({
          label: '删除货架',
          danger: true,
          action: () => void confirmDelete('货架', shelf.name, () => store.removeShelf(shelf.id)),
        })
      }
    }
  } else if (props.editable) {
    items.push({
      label: '在此创建点位',
      action: () => openPlaceDialog({ x: world.x, y: world.y }),
    })
    items.push({ label: '适应视图', action: () => mapBoundsFit() })
  } else {
    // 浏览模式：空白处右键 → 自由到点（机器人自主规划路径） + 适应视图
    items.push({
      label: `自由到点 (${world.x.toFixed(1)}, ${world.y.toFixed(1)})`,
      action: () => {
        if (!robot.status?.pose_initialized) {
          ElMessage.warning('机器人位姿未初始化，无法导航')
          return
        }
        if (tasks.moveTaskInFlight) {
          ElMessage.warning('已有执行中的移动任务，请先取消')
          return
        }
        void tasks.freeNavigate(+world.x.toFixed(4), +world.y.toFixed(4))
      },
    })
    items.push({ label: '适应视图', action: () => mapBoundsFit() })
  }
  if (items.length === 0) return
  ctx.items = items
  ctx.x = ev.clientX
  ctx.y = ev.clientY
  ctx.visible = true
  window.addEventListener('click', closeCtx, { once: true })
}
</script>

<template>
  <div ref="containerRef" class="map-canvas">
    <div class="nd-overlay nd-overlay--tl">
      <span class="nd-chip">{{ coordsText || '0.00 , 0.00 m' }}</span>
      <span v-if="hint" class="nd-chip nd-chip--hint">{{ hint }}</span>
      <span
        v-if="robot.status && !robot.status.pose_initialized
          && robot.status.mode !== 'MAPPING' && robot.status.mode !== 'RELOCALIZING'"
        class="nd-chip nd-chip--warn"
        title="机器人当前无建图无定位，画面是上位机存档地图（仅为浏览）"
      >⚠ 机器人未定位 · 仅为地图浏览</span>
    </div>
    <div class="nd-overlay nd-overlay--tr">
      <button class="nd-chip nd-chip--btn" :disabled="!map3dName" @click="show3d = true">三维地图</button>
      <button
        class="nd-chip nd-chip--btn"
        :class="{ 'nd-chip--active': orbit3d }"
        title="RViz 式 3D 视角：左键拖动旋转、右键平移、滚轮缩放；关闭恢复俯视"
        @click="toggleOrbit3d"
      >3D 视角</button>
      <button
        class="nd-chip nd-chip--btn"
        :class="{ 'nd-chip--active': followCam }"
        title="镜头跟随机器人：快出画时自动平移保持可见；手动拖拽视图或点定位后自动退出"
        @click="toggleFollowCam"
      >◎ 跟随</button>
      <button
        class="nd-chip nd-chip--btn"
        :class="{ 'nd-chip--active': viewRot !== 0 }"
        :title="`画布顺时针旋转（当前 ${viewRot}°，四次还原）`"
        @click="rotateView"
      >⟳ 旋转 {{ viewRot }}°</button>
      <button
        class="nd-chip nd-chip--btn nd-chip--zoom"
        title="放大"
        @click="zoomIn"
      >＋</button>
      <button
        class="nd-chip nd-chip--btn nd-chip--zoom"
        title="缩小"
        @click="zoomOut"
      >－</button>
      <span v-if="store.livePreview" class="nd-chip nd-chip--live">● 建图实时预览中</span>
    </div>
    <!-- 重定位预览确认条：青色点云 = 按新位姿重绘的雷达，贴合地图障碍物则位姿正确 -->
    <div v-if="pendingReloc" class="nd-overlay nd-overlay--tc">
      <span class="nd-chip nd-chip--hint">重定位预览：青色点云按新位姿重绘，观察与地图障碍物的贴合程度</span>
      <button class="nd-chip nd-chip--btn nd-reloc-apply" @click="applyReloc">✓ 应用重定位</button>
      <button class="nd-chip nd-chip--btn" @click="cancelReloc">✕ 取消</button>
    </div>

    <PointLegend />

    <!-- 右键菜单（v1 风格） -->
    <div v-if="ctx.visible" class="ctx-menu" :style="{ left: ctx.x + 'px', top: ctx.y + 'px' }">
      <div
        v-for="(item, i) in ctx.items"
        :key="i"
        class="ctx-item"
        :class="{ danger: item.danger }"
        @click="item.action(); closeCtx()"
      >
        {{ item.label }}
      </div>
    </div>

    <!-- 建点确认（v1 交互：类型点选 + 类型首字母编码 + 保存后连续创建） -->
    <el-dialog v-model="placeDialog.visible" title="创建点位" width="480px" :append-to-body="true">
      <div class="muted tip-line">
        编码已按类型首字母自动生成（可修改）。保存后仍处于「新建点位」模式，可继续点击地图连续创建；按 Esc 退出。
      </div>
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="编码" required>
          <el-input v-model="placeDialog.code" maxlength="64" placeholder="图内唯一，如 P5" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="placeDialog.type" class="type-radios">
            <el-radio-button v-for="(label, value) in WAYPOINT_TYPE_LABELS" :key="value" :value="value">
              {{ label }} {{ WAYPOINT_CODE_PREFIX[value as WaypointType] }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="坐标">
          <span class="muted">
            ({{ placeDialog.x.toFixed(2) }}, {{ placeDialog.y.toFixed(2) }}) m<span v-if="placeDialog.fromRobot"> · 来自机器人位姿（含朝向）</span>
          </span>
        </el-form-item>
        <el-form-item label="朝向(°)">
          <el-input-number v-model="placeDialog.yawDeg" :min="-180" :max="180" :step="5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="placeDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="placeDialog.saving" @click="savePlacePoint">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="show3d" :title="`三维地图 · ${map3dName}`" width="90%" destroy-on-close append-to-body>
      <Map3DViewer v-if="show3d && map3dName" :key="map3dName" :map-name="map3dName" />
    </el-dialog>
  </div>
</template>

<style scoped>
.map-canvas {
  position: relative;
  width: 100%;
  height: 100%;
}

.map-canvas :deep(canvas) {
  display: block;
}

.ctx-menu {
  position: fixed;
  z-index: 3000;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  padding: 4px;
  min-width: 160px;
}

.ctx-item {
  padding: 7px 12px;
  font-size: 13px;
  border-radius: 4px;
  cursor: pointer;
}

.ctx-item:hover {
  background: #f5f7fa;
}

.ctx-item.danger {
  color: #f56c6c;
}

.muted {
  color: #909399;
}

.tip-line {
  font-size: 12px;
  margin-bottom: 12px;
  line-height: 1.6;
}

/* 8 种类型一行放不下，自动换行 */
.type-radios {
  display: flex;
  flex-wrap: wrap;
  gap: 0;
}

.type-radios :deep(.el-radio-button) {
  margin: 2px 0;
}

.nd-overlay {
  position: absolute;
  z-index: 10;
  display: flex;
  gap: 8px;
  align-items: center;
  pointer-events: none;
}

.nd-overlay--tl {
  top: 10px;
  left: 10px;
}

.nd-overlay--tr {
  top: 10px;
  right: 10px;
}

.nd-overlay--tc {
  top: 44px;
  left: 50%;
  transform: translateX(-50%);
}

.nd-reloc-apply {
  color: #67c23a;
  border-color: #b3e19d;
  background: #f0f9eb;
  font-weight: 600;
}

.nd-chip {
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 2px 10px;
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
  color: #303133;
}

.nd-chip--hint {
  color: #409eff;
}

.nd-chip--warn {
  color: #e6a23c;
  border-color: #f3d19e;
  background: #fdf6ec;
  font-weight: 600;
}

.nd-chip--live {
  color: #67c23a;
  font-weight: 600;
}

.nd-chip--btn {
  pointer-events: auto;
  cursor: pointer;
  font-family: inherit;
}

.nd-chip--btn:hover,
.nd-chip--active {
  color: #409eff;
  border-color: #409eff;
}

.nd-chip--active {
  background: #ecf5ff;
}

.nd-chip--zoom {
  min-width: 26px;
  padding: 2px 0;
  text-align: center;
  font-weight: 600;
}
</style>
