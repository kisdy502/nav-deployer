import * as THREE from 'three'
import { CSS2DObject } from 'three/examples/jsm/renderers/CSS2DRenderer.js'
import { pointStyle } from '@/utils/pointStyle'

/** 圆盘半径（加大：点位更醒目）；前半圆=朝向箭头（v1 经典样式），后半圆=类型符号（偏移避让）。 */
const R = 0.5
/** 后半圆符号缩放：配合 -0.5R 偏移，与箭头半圆互不重叠 */
const SYMBOL_SCALE = 0.55
const SYMBOL_OFFSET = 0.5
/** 图形/箭头配色：温和的青灰（替代扎眼的深藏青 #172b4d） */
const GLYPH_COLOR = 0x52667a
/** 类型串（v2 小写 / v1 大写）→ 颜色，未知类型回退普通点样式 */
export const pointTypeColor = (t: string) => new THREE.Color(pointStyle(t).color).getHex()

function symbolGeometry(type: string): THREE.BufferGeometry {
  const outline = pointStyle(type).outline
  // 路径点等无符号类型：返回空 BufferGeometry（mesh 已置 invisible，不会参与渲染）。
  // 不能对空 Shape 调 closePath()——THREE 内部访问 curves[0].getPoint(0) 会抛错并拖垮整个画布。
  if (outline.length === 0) {
    return new THREE.BufferGeometry()
  }
  const shape = new THREE.Shape()
  outline.forEach(([x, y], i) => {
    if (i === 0) shape.moveTo(x * R * SYMBOL_SCALE, y * R * SYMBOL_SCALE)
    else shape.lineTo(x * R * SYMBOL_SCALE, y * R * SYMBOL_SCALE)
  })
  shape.closePath()
  return new THREE.ShapeGeometry(shape)
}

/** 点位渲染输入：v1 NavPointVO 与 v2 WaypointV2VO（映射后）均结构兼容 */
export interface MapPointLike {
  id: number
  x: number
  y: number
  yaw: number
  point_code: string
  point_type: string
}

interface PointVisual {
  root: THREE.Group
  disc: THREE.Mesh
  discMat: THREE.MeshBasicMaterial
  symbol: THREE.Mesh
  pointType: string
  arrow: THREE.Mesh
  ring: THREE.Mesh
  halo: THREE.Mesh
  handle: THREE.Mesh
  labelInner: HTMLElement
  x: number
  y: number
  yaw: number
}

/** 点位图层：小圆盘 + 朝向小箭头 + 选中环 + 路线编辑高亮环 + 旋转手柄 + CSS2D 名称标签 */
export class PointLayer {
  readonly group = new THREE.Group()
  private visuals = new Map<number, PointVisual>()

  /** showHandles=false（浏览模式）时不显示旋转手柄；highlightIds=路线编辑中已选中的起终点，蓝色外环高亮 */
  sync(points: MapPointLike[], selectedId: number | null, showHandles = true, highlightIds?: Set<number>) {
    const alive = new Set<number>()
    for (const p of points) {
      alive.add(p.id)
      let v = this.visuals.get(p.id)
      if (!v) {
        v = this.create(p)
        this.visuals.set(p.id, v)
      }
      this.updateVisual(v, p, p.id === selectedId)
      v.handle.visible = showHandles && p.id === selectedId
      v.halo.visible = highlightIds?.has(p.id) ?? false
    }
    for (const [id, v] of [...this.visuals]) {
      if (!alive.has(id)) {
        this.disposeVisual(id, v)
        this.visuals.delete(id)
      }
    }
  }

  private create(p: MapPointLike): PointVisual {
    const root = new THREE.Group()
    root.position.z = 0.1
    const discMat = new THREE.MeshBasicMaterial({ color: pointTypeColor(p.point_type) })
    const disc = new THREE.Mesh(new THREE.CircleGeometry(R, 32), discMat)
    disc.position.z = 0.01
    disc.userData.pick = { type: 'point', id: p.id }
    const symbol = new THREE.Mesh(symbolGeometry(p.point_type),
      new THREE.MeshBasicMaterial({ color: GLYPH_COLOR, side: THREE.DoubleSide }))
    symbol.position.z = 0.025
    symbol.userData.pick = { type: 'point', id: p.id }
    // 朝向箭头占前半圆（圆心→边缘的大三角，v1 经典样式），后半圆留给类型符号。
    // 仅箭头与符号随 yaw 旋转（符号保持在朝向反侧的后半圆内）。
    const arrowShape = new THREE.Shape()
    arrowShape.moveTo(R, 0)
    arrowShape.lineTo(0, R)
    arrowShape.lineTo(0, -R)
    arrowShape.closePath()
    const arrow = new THREE.Mesh(
      new THREE.ShapeGeometry(arrowShape),
      new THREE.MeshBasicMaterial({ color: GLYPH_COLOR, side: THREE.DoubleSide }),
    )
    arrow.position.z = 0.02
    // 箭头可交互：拖动 = 绕圆心旋转改 yaw（onDown 走 rotate 分支）；单击仍选中点位
    arrow.userData.pick = { type: 'pointArrow', id: p.id }
    const ring = new THREE.Mesh(
      new THREE.RingGeometry(R + 0.03, R + 0.09, 32),
      new THREE.MeshBasicMaterial({ color: 0xf56c6c, side: THREE.DoubleSide }),
    )
    ring.position.z = 0.005
    ring.visible = false
    // 路线编辑高亮环（选中的起终点）：蓝色，位于红色选中环外侧
    const halo = new THREE.Mesh(
      new THREE.RingGeometry(R + 0.12, R + 0.18, 32),
      new THREE.MeshBasicMaterial({ color: 0x409eff, side: THREE.DoubleSide }),
    )
    halo.position.z = 0.006
    halo.visible = false
    const handle = new THREE.Mesh(
      new THREE.SphereGeometry(0.08, 12, 12),
      new THREE.MeshBasicMaterial({ color: 0xe6a23c }),
    )
    handle.position.z = 0.02
    handle.visible = false
    handle.userData.pick = { type: 'rotateHandle', id: p.id }

    const el = document.createElement('div')
    el.className = 'nd-label'
    const inner = document.createElement('span')
    inner.className = 'nd-label-inner'
    el.appendChild(inner)
    const label = new CSS2DObject(el)

    root.add(disc, symbol, arrow, ring, halo, handle, label)
    this.group.add(root)
    return { root, disc, discMat, symbol, pointType: p.point_type, arrow, ring, halo, handle, labelInner: inner, x: p.x, y: p.y, yaw: p.yaw }
  }

  private updateVisual(v: PointVisual, p: MapPointLike, selected: boolean) {
    v.x = p.x
    v.y = p.y
    v.yaw = p.yaw
    v.root.position.x = p.x
    v.root.position.y = p.y
    v.discMat.color.setHex(pointTypeColor(p.point_type))
    // 路径点（及 outline 为空的类型）不画符号，只留朝向箭头
    v.symbol.visible = pointStyle(p.point_type).outline.length > 0
    if (v.pointType !== p.point_type) {
      v.symbol.geometry.dispose()
      v.symbol.geometry = symbolGeometry(p.point_type)
      v.pointType = p.point_type
    }
    this.setArrow(v, p.yaw)
    v.ring.visible = selected
    v.handle.visible = selected
    if (v.labelInner.textContent !== p.point_code) v.labelInner.textContent = p.point_code
  }

  private setArrow(v: PointVisual, yaw: number) {
    v.yaw = yaw
    v.arrow.rotation.z = yaw
    // 类型符号位于箭头反侧的后半圆内（随朝向旋转，保持"后半圆"语义），与箭头互不重叠
    v.symbol.position.x = -Math.cos(yaw) * R * SYMBOL_OFFSET
    v.symbol.position.y = -Math.sin(yaw) * R * SYMBOL_OFFSET
    v.symbol.rotation.z = yaw
    // 旋转手柄位于高亮环外侧。
    v.handle.position.x = Math.cos(yaw) * (R + 0.36)
    v.handle.position.y = Math.sin(yaw) * (R + 0.36)
  }

  /** 拖拽预览：不落库仅更新视觉 */
  setPose(id: number, x: number, y: number, yaw: number) {
    const v = this.visuals.get(id)
    if (!v) return
    v.root.position.x = x
    v.root.position.y = y
    this.setArrow(v, yaw)
  }

  /** 取图层当前显示朝向（旋转拖拽松手时保存用；无视觉返回 null） */
  getVisualYaw(id: number): number | null {
    return this.visuals.get(id)?.yaw ?? null
  }

  private disposeVisual(_id: number, v: PointVisual) {
    this.group.remove(v.root)
    v.root.traverse((o) => {
      const m = o as THREE.Mesh
      m.geometry?.dispose()
      const mat = m.material as THREE.Material | undefined
      mat?.dispose()
    })
    const el = (v.root.children.find((c) => c instanceof CSS2DObject) as CSS2DObject | undefined)?.element
    el?.remove()
  }

  dispose() {
    for (const [id, v] of this.visuals) this.disposeVisual(id, v)
    this.visuals.clear()
  }
}
