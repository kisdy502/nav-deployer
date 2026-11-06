import * as THREE from 'three'
import { CSS2DObject } from 'three/examples/jsm/renderers/CSS2DRenderer.js'

/** 多边形实体渲染项（区域约束 / 货架占地共用） */
export interface PolygonRenderItem {
  id: number
  /** 'zone' | 'shelf'，决定顶点/移动手柄的拾取类型 */
  kind: 'zone' | 'shelf'
  /** 名称标签（调用方拼好，如「窄通道 ≤0.3m/s」「A区货架(3层)」） */
  label: string
  vertices: number[][]
  stroke: number
  fill: number
  fillOpacity: number
  selected: boolean
}

const CLOSE_SNAP_RADIUS = 0.3

function shapeFrom(vertices: number[][]): THREE.Shape {
  const shape = new THREE.Shape()
  vertices.forEach(([x, y], i) => {
    if (i === 0) shape.moveTo(x, y)
    else shape.lineTo(x, y)
  })
  shape.closePath()
  return shape
}

function centroid(vertices: number[][]): { x: number; y: number } {
  const sum = vertices.reduce((acc, v) => [acc[0] + v[0], acc[1] + v[1]], [0, 0])
  return { x: sum[0] / vertices.length, y: sum[1] / vertices.length }
}

/** 常驻实体视觉：几何/材质/标签 DOM 均长期复用，render 只做原位更新 */
interface PolyVisual {
  root: THREE.Group
  fill: THREE.Mesh
  outline: THREE.LineLoop
  /** 顶点手柄（数量随 vertices 同步增减，非选中时整组隐藏） */
  handles: THREE.Group
  /** 中心整体平移手柄 */
  mover: THREE.Mesh
  label: CSS2DObject
  labelInner: HTMLSpanElement
}

/**
 * 多边形图层（区域/货架）：半透明填充 + 描边 + CSS2D 标签；
 * 选中时显示顶点手柄（拖拽改轮廓）与中心手柄（拖拽整体平移）；
 * 另承载绘制草稿（多边形逐点 / 矩形拖拽预览 + 首点闭合吸附提示）。
 * 实体按 `${kind}:${id}` 常驻复用（标签 DOM 不重建），支持拖拽中 updateItem 单实体轻量刷新。
 */
export class PolygonLayer {
  readonly group = new THREE.Group()
  private visuals = new Map<string, PolyVisual>()

  private static key(item: PolygonRenderItem): string {
    return `${item.kind}:${item.id}`
  }

  // ---------- 实体渲染 ----------
  render(items: PolygonRenderItem[]) {
    const alive = new Set<string>()
    for (const item of items) {
      if (!item.vertices || item.vertices.length < 3) continue
      const key = PolygonLayer.key(item)
      alive.add(key)
      let v = this.visuals.get(key)
      if (!v) {
        v = this.create(item)
        this.visuals.set(key, v)
      }
      this.applyItem(v, item)
    }
    for (const [key, v] of [...this.visuals]) {
      if (!alive.has(key)) {
        this.disposeVisual(v)
        this.visuals.delete(key)
      }
    }
  }

  /** 拖拽顶点/整体平移时的轻量单实体刷新：只重建该实体的填充几何与描边/手柄位置，标签 DOM 不动。 */
  updateItem(item: PolygonRenderItem) {
    const v = this.visuals.get(PolygonLayer.key(item))
    if (v) this.applyItem(v, item)
  }

  private create(item: PolygonRenderItem): PolyVisual {
    const root = new THREE.Group()
    const fill = new THREE.Mesh(
      new THREE.BufferGeometry(),
      new THREE.MeshBasicMaterial({ transparent: true, side: THREE.DoubleSide, depthWrite: false }),
    )
    fill.position.z = item.kind === 'zone' ? 0.028 : 0.034
    const outline = new THREE.LineLoop(new THREE.BufferGeometry(), new THREE.LineBasicMaterial())
    outline.position.z = fill.position.z + 0.012
    const handles = new THREE.Group()
    handles.visible = false
    const mover = new THREE.Mesh(
      new THREE.BoxGeometry(0.22, 0.22, 0.06),
      new THREE.MeshBasicMaterial({ color: 0xffffff }),
    )
    mover.position.z = 0.13
    mover.visible = false
    mover.userData.pick = { type: `${item.kind}Move`, id: item.id }

    const el = document.createElement('div')
    el.className = 'nd-label'
    const inner = document.createElement('span')
    inner.className = 'nd-label-inner'
    el.appendChild(inner)
    const label = new CSS2DObject(el)

    root.add(fill, outline, handles, mover, label)
    this.group.add(root)
    return { root, fill, outline, handles, mover, label, labelInner: inner }
  }

  /** 把渲染项原位应用到既有视觉（填充几何随顶点重建三角化，其余全部复用）。 */
  private applyItem(v: PolyVisual, item: PolygonRenderItem) {
    v.fill.geometry.dispose()
    v.fill.geometry = new THREE.ShapeGeometry(shapeFrom(item.vertices))
    const fillMat = v.fill.material as THREE.MeshBasicMaterial
    fillMat.color.setHex(item.fill)
    fillMat.opacity = item.fillOpacity
    v.fill.userData.pick = { type: item.kind, id: item.id }

    // 描边：顶点数不变时原位写 buffer，变化时重建
    const pos = v.outline.geometry.attributes.position as THREE.BufferAttribute | undefined
    if (!pos || pos.count !== item.vertices.length) {
      v.outline.geometry.dispose()
      v.outline.geometry = new THREE.BufferGeometry().setFromPoints(
        item.vertices.map(([x, y]) => new THREE.Vector3(x, y, 0)),
      )
    } else {
      for (let i = 0; i < item.vertices.length; i++) {
        pos.setXYZ(i, item.vertices[i][0], item.vertices[i][1], 0)
      }
      pos.needsUpdate = true
    }
    ;(v.outline.material as THREE.LineBasicMaterial).color.setHex(item.stroke)

    // 顶点手柄：数量对齐后原位更新位置/颜色/拾取信息
    while (v.handles.children.length < item.vertices.length) {
      const handle = new THREE.Mesh(new THREE.SphereGeometry(0.09, 12, 12), new THREE.MeshBasicMaterial())
      handle.position.z = 0.14
      v.handles.add(handle)
    }
    while (v.handles.children.length > item.vertices.length) {
      const handle = v.handles.children[v.handles.children.length - 1] as THREE.Mesh
      v.handles.remove(handle)
      handle.geometry.dispose()
      ;(handle.material as THREE.Material).dispose()
    }
    v.handles.visible = item.selected
    if (item.selected) {
      item.vertices.forEach(([x, y], vIdx) => {
        const handle = v.handles.children[vIdx] as THREE.Mesh
        handle.position.set(x, y, 0.14)
        ;(handle.material as THREE.MeshBasicMaterial).color.setHex(item.stroke)
        handle.userData.pick = { type: `${item.kind}Vertex`, id: item.id, vIdx }
      })
    }

    // 中心手柄 + 名称标签（DOM 节点复用，只改文本/颜色/位置）
    const center = centroid(item.vertices)
    v.mover.visible = item.selected
    v.mover.userData.pick = { type: `${item.kind}Move`, id: item.id }
    v.mover.position.set(center.x, center.y, 0.13)
    v.label.position.set(center.x, center.y, 0.2)
    if (v.labelInner.textContent !== item.label) v.labelInner.textContent = item.label
    v.labelInner.style.borderColor = `#${item.stroke.toString(16).padStart(6, '0')}`
  }

  private disposeVisual(v: PolyVisual) {
    this.group.remove(v.root)
    v.root.traverse((o) => {
      const m = o as THREE.Mesh
      m.geometry?.dispose()
      const mat = m.material as THREE.Material | undefined
      mat?.dispose()
    })
    v.label.element.remove()
  }

  // ---------- 绘制草稿（多边形逐点 / 矩形拖拽） ----------

  private draftObjects = new Set<THREE.Object3D>()
  private draftLine = new THREE.Line(
    new THREE.BufferGeometry(),
    new THREE.LineBasicMaterial({ color: 0x409eff }),
  )
  private draftFill = new THREE.Mesh(
    new THREE.BufferGeometry(),
    new THREE.MeshBasicMaterial({ color: 0x409eff, transparent: true, opacity: 0.12, side: THREE.DoubleSide, depthWrite: false }),
  )
  private draftDots = new THREE.Group()
  private closeHint = new THREE.Mesh(
    new THREE.RingGeometry(0.16, 0.24, 24),
    new THREE.MeshBasicMaterial({ color: 0x67c23a, side: THREE.DoubleSide }),
  )

  private ensureDraftObjects() {
    if (this.draftObjects.size > 0) return
    this.draftLine.frustumCulled = false
    this.draftFill.position.z = 0.03
    this.draftLine.position.z = 0.09
    this.draftFill.frustumCulled = false
    this.closeHint.position.z = 0.1
    this.closeHint.visible = false
    this.group.add(this.draftLine, this.draftFill, this.draftDots, this.closeHint)
    this.draftObjects.add(this.draftLine)
    this.draftObjects.add(this.draftFill)
    this.draftObjects.add(this.draftDots)
    this.draftObjects.add(this.closeHint)
  }

  /**
   * 多边形草稿：已落顶点折线 + 到光标的预览段；接近首点时显示绿色闭合吸附环。
   * vertices 为空/null 时隐藏草稿。
   */
  showDraft(vertices: number[][], cursor: { x: number; y: number } | null, color: number) {
    this.hideDraft()
    if (!vertices || vertices.length === 0) return
    this.ensureDraftObjects()
    ;(this.draftLine.material as THREE.LineBasicMaterial).color.setHex(color)
    ;(this.draftFill.material as THREE.MeshBasicMaterial).color.setHex(color)
    const pts = vertices.map(([x, y]) => new THREE.Vector3(x, y, 0))
    if (cursor) pts.push(new THREE.Vector3(cursor.x, cursor.y, 0))
    if (vertices.length >= 3) {
      pts.push(new THREE.Vector3(vertices[0][0], vertices[0][1], 0))
      this.draftFill.geometry.dispose()
      this.draftFill.geometry = new THREE.ShapeGeometry(shapeFrom(pts.slice(0, -1).map((p) => [p.x, p.y])))
      this.draftFill.visible = true
    } else {
      this.draftFill.visible = false
    }
    this.draftLine.geometry.dispose()
    this.draftLine.geometry = new THREE.BufferGeometry().setFromPoints(pts)
    this.draftLine.visible = true
    // 顶点圆点
    for (const child of [...this.draftDots.children]) {
      this.draftDots.remove(child)
      ;(child as THREE.Mesh).geometry?.dispose()
    }
    vertices.forEach(([x, y], i) => {
      const dot = new THREE.Mesh(
        new THREE.CircleGeometry(0.07, 12),
        new THREE.MeshBasicMaterial({ color: i === 0 ? 0x67c23a : color }),
      )
      dot.position.set(x, y, 0.11)
      this.draftDots.add(dot)
    })
    this.draftDots.visible = true
    // 首点闭合吸附提示
    const closeable = vertices.length >= 3 && cursor != null
      && Math.hypot(cursor.x - vertices[0][0], cursor.y - vertices[0][1]) < CLOSE_SNAP_RADIUS
    this.closeHint.visible = closeable
    if (closeable) this.closeHint.position.set(vertices[0][0], vertices[0][1], 0.1)
  }

  /** 矩形拖拽草稿（区域/货架快捷矩形绘制）。 */
  showRect(a: { x: number; y: number }, b: { x: number; y: number }, color: number) {
    this.hideDraft()
    this.ensureDraftObjects()
    const rect = [
      [Math.min(a.x, b.x), Math.min(a.y, b.y)],
      [Math.max(a.x, b.x), Math.min(a.y, b.y)],
      [Math.max(a.x, b.x), Math.max(a.y, b.y)],
      [Math.min(a.x, b.x), Math.max(a.y, b.y)],
    ]
    ;(this.draftLine.material as THREE.LineBasicMaterial).color.setHex(color)
    ;(this.draftFill.material as THREE.MeshBasicMaterial).color.setHex(color)
    this.draftFill.geometry.dispose()
    this.draftFill.geometry = new THREE.ShapeGeometry(shapeFrom(rect))
    this.draftFill.visible = true
    this.draftLine.geometry.dispose()
    this.draftLine.geometry = new THREE.BufferGeometry().setFromPoints(
      rect.map(([x, y]) => new THREE.Vector3(x, y, 0)).concat(new THREE.Vector3(rect[0][0], rect[0][1], 0)),
    )
    this.draftLine.visible = true
    this.draftDots.visible = false
    this.closeHint.visible = false
  }

  hideDraft() {
    this.draftLine.visible = false
    this.draftFill.visible = false
    this.draftDots.visible = false
    this.closeHint.visible = false
  }

  dispose() {
    for (const v of this.visuals.values()) this.disposeVisual(v)
    this.visuals.clear()
    this.draftObjects.clear()
    this.group.clear()
  }
}
