import * as THREE from 'three'
import type { EdgeType, PathStatus } from '@/types/api'

export interface RenderEdge {
  edgeIdx: number
  sx: number
  sy: number
  tx: number
  ty: number
  edgeType: EdgeType
  cps: { x: number; y: number }[]
  selected: boolean
  /** 端点切线手柄位置（选中路线时显示，拖动改弯） */
  srcHandle?: { x: number; y: number } | null
  tgtHandle?: { x: number; y: number } | null
}

export interface PathRenderItem {
  id: number
  status: PathStatus
  editing: boolean
  selected: boolean
  /** 双向路线：中点方向箭头画两个（一来一回） */
  bidirectional?: boolean
  edges: RenderEdge[] | null
}

const STATUS_COLORS: Record<PathStatus, number> = {
  DRAFT: 0x909399,
  DEPLOYED: 0x67c23a,
  DISABLED: 0xb6bac2,
}
const EDITING_COLOR = 0x409eff
const SELECTED_COLOR = 0xf56c6c
const CP_COLOR = 0xff9900
/** 路线带宽（米）：加宽便于点选与拖拽，也更醒目 */
const RIBBON_W = 0.26
const RIBBON_W_EDITING = 0.32

/** 沿采样点生成有宽度的平面条带（俯视呈粗线），便于拾取 */
function ribbonGeometry(pts: THREE.Vector3[], width: number): THREE.BufferGeometry {
  const half = width / 2
  const verts: number[] = []
  const idx: number[] = []
  const dir = new THREE.Vector3()
  for (let i = 0; i < pts.length; i++) {
    if (i === 0) dir.subVectors(pts[1], pts[0])
    else if (i === pts.length - 1) dir.subVectors(pts[i], pts[i - 1])
    else dir.subVectors(pts[i + 1], pts[i - 1])
    dir.z = 0
    if (dir.lengthSq() < 1e-12) dir.set(1, 0, 0)
    dir.normalize()
    const nx = -dir.y * half
    const ny = dir.x * half
    verts.push(pts[i].x + nx, pts[i].y + ny, 0, pts[i].x - nx, pts[i].y - ny, 0)
    if (i > 0) {
      const a = (i - 1) * 2
      idx.push(a, a + 1, a + 2, a + 1, a + 3, a + 2)
    }
  }
  const g = new THREE.BufferGeometry()
  g.setAttribute('position', new THREE.Float32BufferAttribute(verts, 3))
  g.setIndex(idx)
  return g
}

function dashedLine(a: { x: number; y: number }, b: { x: number; y: number }): THREE.Line {
  const g = new THREE.BufferGeometry().setFromPoints([
    new THREE.Vector3(a.x, a.y, 0),
    new THREE.Vector3(b.x, b.y, 0),
  ])
  const l = new THREE.Line(g, new THREE.LineDashedMaterial({ color: CP_COLOR, dashSize: 0.06, gapSize: 0.04 }))
  l.computeLineDistances()
  l.position.z = 0.07
  return l
}

/** 等边三角形方向箭头 mesh（指向 +X，使用时按方向角旋转） */
function directionArrow(color: number, size = 0.22): THREE.Mesh {
  const h = size * Math.sqrt(3) / 2 // 等边三角形高
  const shape = new THREE.Shape()
  shape.moveTo(h, 0)
  shape.lineTo(-h / 2, size / 2)
  shape.lineTo(-h / 2, -size / 2)
  shape.closePath()
  const mesh = new THREE.Mesh(
    new THREE.ShapeGeometry(shape),
    new THREE.MeshBasicMaterial({ color, side: THREE.DoubleSide }),
  )
  mesh.position.z = 0.11
  return mesh
}

/** 在折线采样点序列上按弧长等间距放置方向箭头（至少 1 个，长段每 ~1.5m 一个） */
/**
 * 段中方向箭头（每段只在正中间画一组）：
 * - 单向：一个等边三角形指向行驶方向
 * - 双向：两个三角形底部相接、尖端相反，视觉上形成一个菱形（◆）表示可双向通行
 */
function placeCenterArrow(
  group: THREE.Group,
  pts: THREE.Vector3[],
  color: number,
  bidirectional: boolean,
) {
  if (pts.length < 2) return
  // 折线总长与中点位置（按弧长）
  let total = 0
  for (let i = 1; i < pts.length; i++) total += pts[i].distanceTo(pts[i - 1])
  if (total < 0.3) return
  const half = total / 2
  let acc = 0
  for (let i = 1; i < pts.length; i++) {
    const seg = pts[i].distanceTo(pts[i - 1])
    if (acc + seg >= half) {
      const t = (half - acc) / seg
      const px = pts[i - 1].x + (pts[i].x - pts[i - 1].x) * t
      const py = pts[i - 1].y + (pts[i].y - pts[i - 1].y) * t
      const angle = Math.atan2(pts[i].y - pts[i - 1].y, pts[i].x - pts[i - 1].x)
      if (bidirectional) {
        // 菱形：两个三角形底边完全贴合（尖端相反），不重叠
        // 底边位置 = 三角形中心向前/后各偏移 h/2（h 为三角形高），确保底部精确对接
        const forward = directionArrow(color, 0.26)
        const backward = directionArrow(color, 0.26)
        const h = 0.26 * Math.sqrt(3) / 2
        forward.position.set(px + Math.cos(angle) * (h / 2), py + Math.sin(angle) * (h / 2), 0.11)
        forward.rotation.z = angle
        group.add(forward)
        backward.position.set(px - Math.cos(angle) * (h / 2), py - Math.sin(angle) * (h / 2), 0.11)
        backward.rotation.z = angle + Math.PI
        group.add(backward)
      } else {
        const arrow = directionArrow(color, 0.26)
        arrow.position.set(px, py, 0.11)
        arrow.rotation.z = angle
        group.add(arrow)
      }
      return
    }
    acc += seg
  }
}

/** 路线图层：有宽度条带（可点击选中），编辑中路线显示控制点与贝塞尔切点辅助虚线 */
export class PathLayer {
  readonly group = new THREE.Group()

  render(items: PathRenderItem[]) {
    this.clearGroup()
    for (const item of items) {
      if (!item.edges) continue
      const base = item.editing ? EDITING_COLOR : (STATUS_COLORS[item.status] ?? STATUS_COLORS.DRAFT)
      /** 选中路线（非编辑态）整体高亮 */
      const pathColor = !item.editing && item.selected ? SELECTED_COLOR : base
      const nodeSet = new Set<string>()
      for (const e of item.edges) {
        const color = e.selected ? SELECTED_COLOR : pathColor
        const pts = this.sampleEdge(e)
        if (pts.length < 2) continue
        const mesh = new THREE.Mesh(
          ribbonGeometry(pts, item.editing ? RIBBON_W_EDITING : RIBBON_W),
          new THREE.MeshBasicMaterial({ color, side: THREE.DoubleSide }),
        )
        mesh.position.z = 0.05
        mesh.userData.pick = { type: 'path', id: item.id, edgeIdx: e.edgeIdx }
        this.group.add(mesh)

        // 段中方向箭头：单向 = 一个三角形；双向 = 菱形（底对底双三角）；与站点箭头同色系
        placeCenterArrow(this.group, pts, 0x52667a, !!item.bidirectional)

        // 选中路线的节点辅助点（去重后的顶点）
        if (!item.editing && item.selected) {
          for (const p of [
            { x: e.sx, y: e.sy },
            { x: e.tx, y: e.ty },
          ]) {
            const key = `${p.x.toFixed(3)},${p.y.toFixed(3)}`
            if (nodeSet.has(key)) continue
            nodeSet.add(key)
            const dot = new THREE.Mesh(
              new THREE.CircleGeometry(0.06, 16),
              new THREE.MeshBasicMaterial({ color: CP_COLOR }),
            )
            dot.position.set(p.x, p.y, 0.09)
            this.group.add(dot)
          }
        }

        if (!item.editing) continue
        // ===== 曲线段控制点手柄（绿色，直径与路线同宽，可拖动调整弯曲） =====
        if (e.edgeType === 'CURVE' && e.cps.length >= 1) {
          const cpRadius = RIBBON_W / 2

          // 判断是否为二阶模式（CP2 仍在默认 3/4 直线位置）
          const defCp2x = e.sx + (e.tx - e.sx) * 0.75
          const defCp2y = e.sy + (e.ty - e.sy) * 0.75
          const isQuadratic = e.cps.length === 2
            && Math.abs(e.cps[1].x - defCp2x) < 0.02
            && Math.abs(e.cps[1].y - defCp2y) < 0.02

          // CP1 手柄：在实际控制点位置
          const cp1m = new THREE.Mesh(
            new THREE.SphereGeometry(cpRadius, 14, 14),
            new THREE.MeshBasicMaterial({ color: 0x67c23a }),
          )
          cp1m.position.set(e.cps[0].x, e.cps[0].y, 0.12)
          cp1m.userData.pick = { type: 'control', edgeIdx: e.edgeIdx, cpIdx: 0 }
          this.group.add(cp1m)

          // CP2 手柄：二阶模式时显示在曲线上 t=0.75 处（跟随曲线移动），三阶模式在实际位置
          if (e.cps.length >= 2) {
            let cp2x = e.cps[1].x
            let cp2y = e.cps[1].y
            if (isQuadratic) {
              // 二次贝塞尔曲线在 t=0.75 处的点（跟随 CP1 拖动后的曲线形态）
              const t = 0.75
              const mt = 1 - t
              cp2x = mt * mt * e.sx + 2 * mt * t * e.cps[0].x + t * t * e.tx
              cp2y = mt * mt * e.sy + 2 * mt * t * e.cps[0].y + t * t * e.ty
            }
            const cp2m = new THREE.Mesh(
              new THREE.SphereGeometry(cpRadius, 14, 14),
              new THREE.MeshBasicMaterial({ color: 0x67c23a }),
            )
            cp2m.position.set(cp2x, cp2y, 0.12)
            cp2m.userData.pick = { type: 'control', edgeIdx: e.edgeIdx, cpIdx: 1 }
            this.group.add(cp2m)
          }

          // 端点 → 控制点 → 控制点 → 端点 完整贝塞尔辅助连线（控制多边形）
          this.group.add(dashedLine({ x: e.sx, y: e.sy }, e.cps[0]))
          if (e.cps.length === 1) {
            this.group.add(dashedLine(e.cps[0], { x: e.tx, y: e.ty }))
          } else if (isQuadratic) {
            // 二阶模式：CP2→终点 虚线用曲线上的显示位置
            const t = 0.75
            const mt = 1 - t
            const cp2x = mt * mt * e.sx + 2 * mt * t * e.cps[0].x + t * t * e.tx
            const cp2y = mt * mt * e.sy + 2 * mt * t * e.cps[0].y + t * t * e.ty
            this.group.add(dashedLine(e.cps[0], { x: cp2x, y: cp2y }))
            this.group.add(dashedLine({ x: cp2x, y: cp2y }, { x: e.tx, y: e.ty }))
          } else {
            // 三阶模式：起点→CP1→CP2→终点 完整控制多边形
            this.group.add(dashedLine(e.cps[0], e.cps[1]))
            this.group.add(dashedLine(e.cps[1], { x: e.tx, y: e.ty }))
          }
        }
        // ===== 直线段：中点辅助手柄（绿色，拖动快速转曲线） =====
        if (e.edgeType === 'STRAIGHT' || e.cps.length === 0) {
          const mx = (e.sx + e.tx) / 2
          const my = (e.sy + e.ty) / 2
          const cpRadius = RIBBON_W / 2
          const m = new THREE.Mesh(
            new THREE.SphereGeometry(cpRadius, 14, 14),
            new THREE.MeshBasicMaterial({ color: 0x67c23a }),
          )
          m.position.set(mx, my, 0.12)
          m.userData.pick = { type: 'control', edgeIdx: e.edgeIdx, cpIdx: 0 }
          this.group.add(m)
          this.group.add(dashedLine({ x: e.sx, y: e.sy }, { x: mx, y: my }))
          this.group.add(dashedLine({ x: mx, y: my }, { x: e.tx, y: e.ty }))
        }
      }
    }
  }

  private sampleEdge(e: RenderEdge): THREE.Vector3[] {
    const v0 = new THREE.Vector3(e.sx, e.sy, 0)
    const v2 = new THREE.Vector3(e.tx, e.ty, 0)
    if (e.edgeType === 'CURVE' && e.cps.length === 1) {
      const c = new THREE.Vector3(e.cps[0].x, e.cps[0].y, 0)
      return new THREE.QuadraticBezierCurve3(v0, c, v2).getPoints(28)
    }
    if (e.edgeType === 'CURVE' && e.cps.length >= 2) {
      // CP2 仍在默认 3/4 位置（容差 2cm）→ 二阶贝塞尔（只看 CP1），否则三阶
      const defCp2x = e.sx + (e.tx - e.sx) * 0.75
      const defCp2y = e.sy + (e.ty - e.sy) * 0.75
      const cp2 = e.cps[e.cps.length - 1]
      if (Math.abs(cp2.x - defCp2x) < 0.02 && Math.abs(cp2.y - defCp2y) < 0.02) {
        const c = new THREE.Vector3(e.cps[0].x, e.cps[0].y, 0)
        return new THREE.QuadraticBezierCurve3(v0, c, v2).getPoints(28)
      }
      const c1 = new THREE.Vector3(e.cps[0].x, e.cps[0].y, 0)
      const c2 = new THREE.Vector3(cp2.x, cp2.y, 0)
      return new THREE.CubicBezierCurve3(v0, c1, c2, v2).getPoints(32)
    }
    return [v0, v2.clone()]
  }

  /** 画路线模式预览：从待连接起点到光标的指示线 */
  private previewLine: THREE.Line | null = null
  showPreview(from: { x: number; y: number } | null, to: { x: number; y: number } | null) {
    if (!from || !to) {
      if (this.previewLine) this.previewLine.visible = false
      return
    }
    if (!this.previewLine) {
      this.previewLine = new THREE.Line(
        new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()]),
        new THREE.LineBasicMaterial({ color: 0x409eff, transparent: true, opacity: 0.6 }),
      )
      this.previewLine.position.z = 0.05
      this.previewLine.frustumCulled = false
      this.group.add(this.previewLine)
    }
    const pos = this.previewLine.geometry.attributes.position as THREE.BufferAttribute
    pos.setXYZ(0, from.x, from.y, 0)
    pos.setXYZ(1, to.x, to.y, 0)
    pos.needsUpdate = true
    this.previewLine.visible = true
  }

  private clearGroup() {
    for (const child of [...this.group.children]) {
      if (child === this.previewLine) continue
      this.group.remove(child)
      child.traverse((o) => {
        const m = o as THREE.Mesh
        m.geometry?.dispose()
        const mat = m.material as THREE.Material | undefined
        mat?.dispose()
      })
    }
  }

  dispose() {
    this.previewLine = null
    this.clearGroup()
  }
}
