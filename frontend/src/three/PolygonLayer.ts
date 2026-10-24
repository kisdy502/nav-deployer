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

/**
 * 多边形图层（区域/货架）：半透明填充 + 描边 + CSS2D 标签；
 * 选中时显示顶点手柄（拖拽改轮廓）与中心手柄（拖拽整体平移）；
 * 另承载绘制草稿（多边形逐点 / 矩形拖拽预览 + 首点闭合吸附提示）。
 */
export class PolygonLayer {
  readonly group = new THREE.Group()

  // ---------- 实体渲染 ----------
  render(items: PolygonRenderItem[]) {
    this.clearEntities()
    for (const item of items) {
      if (!item.vertices || item.vertices.length < 3) continue
      const z = item.kind === 'zone' ? 0.028 : 0.034
      const fill = new THREE.Mesh(
        new THREE.ShapeGeometry(shapeFrom(item.vertices)),
        new THREE.MeshBasicMaterial({
          color: item.fill,
          transparent: true,
          opacity: item.fillOpacity,
          side: THREE.DoubleSide,
          depthWrite: false,
        }),
      )
      fill.position.z = z
      fill.userData.pick = { type: item.kind, id: item.id }
      this.group.add(fill)

      const outline = new THREE.LineLoop(
        new THREE.BufferGeometry().setFromPoints(
          item.vertices.map(([x, y]) => new THREE.Vector3(x, y, 0)),
        ),
        new THREE.LineBasicMaterial({ color: item.stroke }),
      )
      outline.position.z = z + 0.012
      this.group.add(outline)

      const center = centroid(item.vertices)
      // 选中：顶点手柄 + 中心移动手柄
      if (item.selected) {
        item.vertices.forEach(([x, y], vIdx) => {
          const handle = new THREE.Mesh(
            new THREE.SphereGeometry(0.09, 12, 12),
            new THREE.MeshBasicMaterial({ color: item.stroke }),
          )
          handle.position.set(x, y, 0.14)
          handle.userData.pick = { type: `${item.kind}Vertex`, id: item.id, vIdx }
          this.group.add(handle)
        })
        const mover = new THREE.Mesh(
          new THREE.BoxGeometry(0.22, 0.22, 0.06),
          new THREE.MeshBasicMaterial({ color: 0xffffff }),
        )
        mover.position.set(center.x, center.y, 0.13)
        mover.userData.pick = { type: `${item.kind}Move`, id: item.id }
        this.group.add(mover)
      }

      // 名称标签
      const el = document.createElement('div')
      el.className = 'nd-label'
      const inner = document.createElement('span')
      inner.className = 'nd-label-inner'
      inner.style.borderColor = `#${item.stroke.toString(16).padStart(6, '0')}`
      inner.textContent = item.label
      el.appendChild(inner)
      const label = new CSS2DObject(el)
      label.position.set(center.x, center.y, 0.2)
      this.group.add(label)
    }
  }

  private clearEntities() {
    for (const child of [...this.group.children]) {
      if (this.draftObjects.has(child)) continue
      this.group.remove(child)
      if (child instanceof CSS2DObject) {
        child.element.remove()
        continue
      }
      child.traverse((o) => {
        const m = o as THREE.Mesh
        m.geometry?.dispose()
        const mat = m.material as THREE.Material | undefined
        mat?.dispose()
      })
    }
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
    this.clearEntities()
    this.draftObjects.clear()
    this.group.clear()
  }
}
