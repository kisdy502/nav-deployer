<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MapScene, type PickResult } from '@/three/MapScene'
import { PointLayer } from '@/three/PointLayer'
import { PathLayer, type PathRenderItem } from '@/three/PathLayer'
import { RobotLayer } from '@/three/RobotLayer'
import { useEditorStore } from '@/stores/editor'
import { useRobotStore } from '@/stores/robot'

const emit = defineEmits<{
  (e: 'place-point', xy: { x: number; y: number }): void
  (e: 'context', payload: { hit: PickResult | null; x: number; y: number; world: { x: number; y: number } }): void
}>()

// editable=false 为浏览模式：可选中点位/路线，但不可拖拽编辑
const props = withDefaults(defineProps<{ editable?: boolean }>(), { editable: true })

const editor = useEditorStore()
const robot = useRobotStore()

const containerRef = ref<HTMLDivElement>()
const coordsText = ref('')
const hint = computed(() => {
  switch (editor.mode) {
    case 'placePoint':
      return '在地图上点击放置点位'
    case 'drawPath':
      return editor.pathEdit
        ? editor.pathEdit.pendingSourceId == null
          ? '点击一个点位作为路线节点（链式连接）'
          : '点击下一个点位以连接（Esc 取消起点）'
        : '请先在左侧选择或新建路线'
    case 'initialPose':
      return '按下并拖动：设置机器人初始位姿（方向 = 拖动方向）'
    case 'measure':
      return '点击两点测量距离（Esc 退出）'
    default:
      return ''
  }
})

let scene: MapScene | null = null
let pointLayer: PointLayer
let pathLayer: PathLayer
let robotLayer: RobotLayer

// 拖拽状态
type DragState =
  | { kind: 'point'; id: number; originX: number; originY: number; originYaw: number }
  | { kind: 'rotate'; id: number }
  | { kind: 'control'; edgeIdx: number; cpIdx: number }
let drag: DragState | null = null
let poseAnchor: { x: number; y: number } | null = null
let lastFitMapId = 0
let posePreview: THREE.Group | null = null
let posePreviewArrow: THREE.Mesh | null = null

// ---------- 渲染数据 ----------
const pathItems = computed<PathRenderItem[]>(() => {
  const byId = editor.pointsById
  const toEdge = (e: {
    source_point_id: number
    target_point_id: number
    edge_type: 'STRAIGHT' | 'CURVE'
    control_points: { x: number; y: number }[] | null
  }, idx: number, editing: boolean) => {
    const s = byId.get(e.source_point_id)
    const t = byId.get(e.target_point_id)
    if (!s || !t) return null
    return {
      edgeIdx: idx,
      sx: s.x,
      sy: s.y,
      tx: t.x,
      ty: t.y,
      edgeType: e.edge_type,
      cps: e.control_points ?? [],
      selected: editing && editor.selectedEdgeIdx === idx,
    }
  }
  return editor.paths.map((path) => {
    const editing = editor.pathEdit?.pathId === path.id
    if (editing && editor.pathEdit) {
      const pe = editor.pathEdit
      const edges = pe.edges
        .map((e, i) => toEdge(e, i, true))
        .filter((x): x is NonNullable<ReturnType<typeof toEdge>> => x != null)
      return { id: path.id, status: path.status, editing: true, selected: true, edges }
    }
    const detail = editor.pathDetails[path.id]
    return {
      id: path.id,
      status: path.status,
      editing: false,
      selected: editor.selectedPathId === path.id,
      edges: detail ? detail.map((e, i) => toEdge(e, i, false)).filter((x): x is NonNullable<ReturnType<typeof toEdge>> => x != null) : null,
    }
  })
})

// ---------- 生命周期 ----------
onMounted(() => {
  const el = containerRef.value!
  // 先创建图层再创建场景：MapScene 构造即启动渲染循环，避免回调引用未初始化的图层
  pointLayer = new PointLayer()
  pathLayer = new PathLayer()
  robotLayer = new RobotLayer()
  scene = new MapScene(el, {
    onLeftDown: onDown,
    onLeftMove: onMove,
    onLeftUp: onUp,
    onLeftClick: onClick,
    onContext: onCtx,
    onFrame: (dt) => robotLayer?.tick(dt),
  })
  scene.pickRoot.add(pointLayer.group, pathLayer.group, robotLayer.group, robotLayer.trailGroup)

  // 重定位模式的方向预览箭头
  posePreview = new THREE.Group()
  const previewMat = new THREE.MeshBasicMaterial({ color: 0xd946ef, transparent: true, opacity: 0.8 })
  const base = new THREE.Mesh(new THREE.CircleGeometry(0.26, 24), previewMat.clone())
  base.position.z = 0.08
  posePreviewArrow = new THREE.Mesh(new THREE.ConeGeometry(0.1, 0.4, 12), previewMat)
  posePreviewArrow.position.z = 0.09
  posePreview.add(base, posePreviewArrow)
  posePreview.visible = false
  scene.scene.add(posePreview)

  window.addEventListener('keydown', onKey)

  // 指针移动显示坐标
  el.addEventListener('pointermove', (ev: PointerEvent) => {
    if (!scene) return
    const w = scene.screenToWorld(ev.clientX, ev.clientY)
    coordsText.value = `${w.x.toFixed(2)} , ${w.y.toFixed(2)} m`
  })

  // 组件重挂载（如编辑器返回浏览页）时场景是新的，需主动同步一次已有数据
  syncAll()
})

/** 把 store 中的当前状态整体刷进场景 */
function syncAll() {
  if (!scene) return
  if (editor.grid) {
    scene.setMap(editor.grid)
    if (scene.mapBounds) scene.fitView(scene.mapBounds)
  } else {
    scene.hideMap()
  }
  pointLayer?.sync(editor.points, editor.selectedPointId ?? null, props.editable)
  pathLayer?.render(pathItems.value)
  robotLayer?.setTarget(robot.pose)
  robotLayer?.setTrail(robot.trail)
  scene.mapGroup.visible = editor.layers.map
  if (pointLayer) pointLayer.group.visible = editor.layers.points
  if (pathLayer) pathLayer.group.visible = editor.layers.paths
  if (robotLayer) {
    robotLayer.group.visible = editor.layers.robot
    robotLayer.trailGroup.visible = editor.layers.trail
  }
  scene.showMeasure(editor.measure.a, editor.measure.b)
}

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  editor.stopLivePolling()
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
  () => editor.grid,
  (g) => {
    if (!g || !scene) return
    scene.setMap(g)
    if (scene.mapBounds && lastFitMapId !== editor.mapId) {
      scene.fitView(scene.mapBounds)
      lastFitMapId = editor.mapId
    }
  },
)

watch(
  () => [editor.points, editor.selectedPointId, props.editable] as const,
  ([pts, sel, editable]) => pointLayer?.sync(pts, sel ?? null, editable),
  { deep: false },
)

watch(pathItems, (items) => pathLayer?.render(items), { deep: true })

watch(
  () => [editor.layers.map, editor.layers.points, editor.layers.paths, editor.layers.robot, editor.layers.trail] as const,
  ([map, points, paths, rb, trail]) => {
    if (!scene) return
    scene.mapGroup.visible = map
    if (pointLayer) pointLayer.group.visible = points
    if (pathLayer) pathLayer.group.visible = paths
    if (robotLayer) {
      robotLayer.group.visible = rb
      robotLayer.trailGroup.visible = trail
    }
  },
)

watch(
  () => robot.pose,
  (p) => robotLayer?.setTarget(p),
)

watch(
  () => robot.trail,
  (t) => robotLayer?.setTrail(t),
  { deep: true },
)

watch(
  () => [editor.measure.a, editor.measure.b] as const,
  ([a, b]) => scene?.showMeasure(a, b),
)

watch(
  () => editor.mode,
  (m) => {
    hidePreviews()
    if (m !== 'drawPath') pathLayer?.showPreview(null, null)
  },
)

// ---------- 交互 ----------
function hidePreviews() {
  if (posePreview) posePreview.visible = false
  poseAnchor = null
}

function onDown(_hit: PickResult | null, world: THREE.Vector3, ev: PointerEvent) {
  if (!scene) return
  if (editor.mode === 'initialPose') {
    poseAnchor = { x: world.x, y: world.y }
    if (posePreview) {
      posePreview.position.set(world.x, world.y, 0)
      posePreview.visible = true
    }
    return
  }
  if (editor.mode !== 'idle' || editor.pathEdit || !props.editable) return
  const hit = scene.pick(ev.clientX, ev.clientY)
  if (!hit) return
  if (hit.type === 'point' && hit.id != null) {
    const p = editor.pointsById.get(hit.id)
    if (p) drag = { kind: 'point', id: hit.id, originX: p.x, originY: p.y, originYaw: p.yaw }
  } else if (hit.type === 'rotateHandle' && hit.id != null) {
    drag = { kind: 'rotate', id: hit.id }
  } else if (hit.type === 'control' && hit.edgeIdx != null && hit.cpIdx != null) {
    editor.selectedEdgeIdx = hit.edgeIdx
    drag = { kind: 'control', edgeIdx: hit.edgeIdx, cpIdx: hit.cpIdx }
  }
}

function onMove(world: THREE.Vector3, _ev: PointerEvent) {
  if (editor.mode === 'initialPose' && poseAnchor && posePreview && posePreviewArrow) {
    const yaw = Math.atan2(world.y - poseAnchor.y, world.x - poseAnchor.x)
    posePreview.position.set(poseAnchor.x, poseAnchor.y, 0)
    posePreviewArrow.rotation.z = yaw - Math.PI / 2
    return
  }
  if (editor.mode === 'drawPath' && editor.pathEdit?.pendingSourceId != null) {
    const src = editor.pointsById.get(editor.pathEdit.pendingSourceId)
    if (src) pathLayer?.showPreview({ x: src.x, y: src.y }, { x: world.x, y: world.y })
    return
  }
  if (!drag) return
  if (drag.kind === 'point') {
    pointLayer?.setPose(drag.id, world.x, world.y, drag.originYaw)
  } else if (drag.kind === 'rotate') {
    const p = editor.pointsById.get(drag.id)
    if (p) pointLayer?.setPose(drag.id, p.x, p.y, Math.atan2(world.y - p.y, world.x - p.x))
  } else if (drag.kind === 'control') {
    editor.updateControlPoint(drag.edgeIdx, drag.cpIdx, { x: world.x, y: world.y })
  }
}

async function onUp(_hit: PickResult | null, world: THREE.Vector3) {
  // 重定位提交
  if (editor.mode === 'initialPose' && poseAnchor) {
    const dist = Math.hypot(world.x - poseAnchor.x, world.y - poseAnchor.y)
    const yaw = dist < 0.15 ? 0 : Math.atan2(world.y - poseAnchor.y, world.x - poseAnchor.x)
    hidePreviews()
    editor.setMode('idle')
    try {
      await robot.setInitialPose(+poseAnchor.x.toFixed(4), +poseAnchor.y.toFixed(4), +yaw.toFixed(4))
      ElMessage.success('初始位姿已发送，等待重定位…')
    } catch {
      /* http 层已提示 */
    }
    return
  }
  // 画路线预览清理（点击已由 click 处理）
  if (!drag) return
  const d = drag
  drag = null
  try {
    if (d.kind === 'point') {
      const p = editor.pointsById.get(d.id)
      if (p && (Math.abs(p.x - world.x) > 1e-4 || Math.abs(p.y - world.y) > 1e-4)) {
        await editor.updatePoint(d.id, { x: +world.x.toFixed(4), y: +world.y.toFixed(4) })
      } else {
        pointLayer?.setPose(d.id, p?.x ?? world.x, p?.y ?? world.y, d.originYaw)
      }
    } else if (d.kind === 'rotate') {
      const p = editor.pointsById.get(d.id)
      if (p) {
        // 取图层上的最新朝向（拖拽中已更新）
        await editor.updatePoint(d.id, { yaw: +p.yaw.toFixed(4) })
      }
    }
  } catch {
    if (d.kind === 'point') pointLayer?.setPose(d.id, d.originX, d.originY, d.originYaw)
  }
}

function onClick(hit: PickResult | null, world: THREE.Vector3) {
  if (editor.mode === 'placePoint') {
    emit('place-point', { x: +world.x.toFixed(4), y: +world.y.toFixed(4) })
    return
  }
  if (editor.mode === 'drawPath') {
    if (hit?.type === 'point' && hit.id != null) {
      editor.clickPointForPath(hit.id)
    } else {
      ElMessage.warning('请点击一个点位作为路线节点')
    }
    return
  }
  if (editor.mode === 'measure') {
    const m = editor.measure
    if (!m.a || (m.a && m.b)) editor.measure = { a: { x: world.x, y: world.y }, b: null }
    else editor.measure = { a: m.a, b: { x: world.x, y: world.y } }
    return
  }
  // 空闲模式：选中
  if (hit?.type === 'point' && hit.id != null) {
    editor.selectPoint(hit.id)
  } else if (hit?.type === 'rotateHandle' && hit.id != null) {
    editor.selectPoint(hit.id)
  } else if (hit?.type === 'path' && hit.id != null) {
    editor.selectPath(hit.id)
    void editor.ensurePathDetail(hit.id)
  } else if (!props.editable) {
    // 浏览模式：点空白也允许清除选择
    editor.clearSelection()
  } else {
    editor.clearSelection()
  }
}

function onCtx(hit: PickResult | null, world: THREE.Vector3, ev: MouseEvent) {
  emit('context', { hit, x: ev.clientX, y: ev.clientY, world: { x: world.x, y: world.y } })
}

async function onKey(ev: KeyboardEvent) {
  if (ev.key === 'Escape') {
    if (drag) {
      const d = drag
      drag = null
      if (d.kind === 'point') pointLayer?.setPose(d.id, d.originX, d.originY, d.originYaw)
      hidePreviews()
      return
    }
    if (editor.mode !== 'idle') {
      editor.setMode('idle')
      return
    }
    editor.clearSelection()
  } else if (ev.key === 'Delete' || ev.key === 'Backspace') {
    const tag = document.activeElement?.tagName
    if (tag === 'INPUT' || tag === 'TEXTAREA') return
    if (editor.pathEdit) {
      editor.removeLastEdge()
    } else if (editor.selectedPointId != null) {
      const p = editor.selectedPoint
      if (!p) return
      await ElMessageBox.confirm(`确认删除点位「${p.point_code}」？`, '删除点位', { type: 'warning' })
      await editor.deletePoint(p.id)
      ElMessage.success('已删除')
    }
  }
}
</script>

<template>
  <div ref="containerRef" class="map-canvas" :class="`map-cursor-${editor.mode === 'idle' ? 'idle' : 'cross'}`">
    <div class="nd-overlay nd-overlay--tl">
      <span class="nd-chip">{{ coordsText || '0.00 , 0.00 m' }}</span>
      <span v-if="hint" class="nd-chip nd-chip--hint">{{ hint }}</span>
    </div>
    <div v-if="editor.livePolling" class="nd-overlay nd-overlay--tr">
      <span class="nd-chip nd-chip--live">● 建图实时预览中</span>
    </div>
  </div>
</template>

<style scoped>
.map-canvas {
  position: relative;
  width: 100%;
  height: 100%;
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

.nd-chip--live {
  color: #67c23a;
  font-weight: 600;
}
</style>
