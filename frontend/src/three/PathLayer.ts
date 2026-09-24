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
}

export interface PathRenderItem {
  id: number
  status: PathStatus
  editing: boolean
  selected: boolean
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
/** 路线带宽（米）：加宽便于点选与拖拽 */
const RIBBON_W = 0.09
const RIBBON_W_EDITING = 0.11

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
        // 控制点手柄
        e.cps.forEach((cp, cpIdx) => {
          const m = new THREE.Mesh(
            new THREE.SphereGeometry(0.07, 12, 12),
            new THREE.MeshBasicMaterial({ color: CP_COLOR }),
          )
          m.position.set(cp.x, cp.y, 0.12)
          m.userData.pick = { type: 'control', edgeIdx: e.edgeIdx, cpIdx }
          this.group.add(m)
        })
        // 贝塞尔切点辅助虚线：端点 → 控制点
        if (e.edgeType === 'CURVE' && e.cps.length === 1) {
          this.group.add(dashedLine({ x: e.sx, y: e.sy }, e.cps[0]))
          this.group.add(dashedLine(e.cps[0], { x: e.tx, y: e.ty }))
        } else if (e.edgeType === 'CURVE' && e.cps.length >= 2) {
          this.group.add(dashedLine({ x: e.sx, y: e.sy }, e.cps[0]))
          this.group.add(dashedLine({ x: e.tx, y: e.ty }, e.cps[e.cps.length - 1]))
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
      const c1 = new THREE.Vector3(e.cps[0].x, e.cps[0].y, 0)
      const c2 = new THREE.Vector3(e.cps[1].x, e.cps[1].y, 0)
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
