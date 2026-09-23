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

/** 路线图层：每条边渲染为折线（直线/贝塞尔采样曲线），编辑中路线附加可拖拽控制点 */
export class PathLayer {
  readonly group = new THREE.Group()

  render(items: PathRenderItem[]) {
    this.clearGroup()
    for (const item of items) {
      if (!item.edges) continue
      const base = item.editing ? EDITING_COLOR : STATUS_COLORS[item.status] ?? STATUS_COLORS.DRAFT
      for (const e of item.edges) {
        const color = e.selected ? SELECTED_COLOR : base
        const pts = this.sampleEdge(e)
        if (pts.length < 2) continue
        const line = new THREE.Line(
          new THREE.BufferGeometry().setFromPoints(pts),
          new THREE.LineBasicMaterial({ color }),
        )
        line.position.z = 0.06
        this.group.add(line)
        // 控制点（仅编辑中路线）
        if (item.editing) {
          e.cps.forEach((cp, cpIdx) => {
            const m = new THREE.Mesh(
              new THREE.SphereGeometry(0.08, 12, 12),
              new THREE.MeshBasicMaterial({ color: CP_COLOR }),
            )
            m.position.set(cp.x, cp.y, 0.12)
            m.userData.pick = { type: 'control', edgeIdx: e.edgeIdx, cpIdx }
            this.group.add(m)
          })
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

  /** 画路线模式预览：从待连接起点到光标的虚线 */
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
