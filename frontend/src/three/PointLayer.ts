import * as THREE from 'three'
import { CSS2DObject } from 'three/examples/jsm/renderers/CSS2DRenderer.js'
import type { NavPointVO, PointType } from '@/types/api'

/** 点位半径 0.11m（直径 0.22），箭头高 = 半径，底面在圆心、尖端抵圆周 */
const R = 0.11
const ARROW_H = R

const TYPE_COLORS: Record<PointType, number> = {
  NORMAL: 0x95d475, // 浅绿
  HOME: 0x95d475, // 浅绿
  CHARGER: 0xe6a23c, // 充电点保留橙色便于辨识
}

export const pointTypeColor = (t: PointType) => TYPE_COLORS[t] ?? TYPE_COLORS.NORMAL

interface PointVisual {
  root: THREE.Group
  disc: THREE.Mesh
  discMat: THREE.MeshBasicMaterial
  arrow: THREE.Mesh
  ring: THREE.Mesh
  handle: THREE.Mesh
  labelInner: HTMLElement
  x: number
  y: number
  yaw: number
}

/** 点位图层：小圆盘 + 朝向小箭头 + 选中环 + 旋转手柄 + CSS2D 名称标签 */
export class PointLayer {
  readonly group = new THREE.Group()
  private visuals = new Map<number, PointVisual>()

  /** showHandles=false（浏览模式）时不显示旋转手柄 */
  sync(points: NavPointVO[], selectedId: number | null, showHandles = true) {
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
    }
    for (const [id, v] of [...this.visuals]) {
      if (!alive.has(id)) {
        this.disposeVisual(id, v)
        this.visuals.delete(id)
      }
    }
  }

  private create(p: NavPointVO): PointVisual {
    const root = new THREE.Group()
    root.position.z = 0.1
    const discMat = new THREE.MeshBasicMaterial({ color: pointTypeColor(p.point_type) })
    const disc = new THREE.Mesh(new THREE.CircleGeometry(R, 32), discMat)
    disc.position.z = 0.01
    disc.userData.pick = { type: 'point', id: p.id }
    // 朝向箭头：高 = 半径，底面在圆心，尖端抵圆周
    const arrow = new THREE.Mesh(
      new THREE.ConeGeometry(0.045, ARROW_H, 12),
      new THREE.MeshBasicMaterial({ color: 0x2f6b3a }),
    )
    arrow.position.z = 0.02
    arrow.userData.pick = { type: 'point', id: p.id }
    const ring = new THREE.Mesh(
      new THREE.RingGeometry(R + 0.02, R + 0.07, 32),
      new THREE.MeshBasicMaterial({ color: 0xf56c6c, side: THREE.DoubleSide }),
    )
    ring.position.z = 0.005
    ring.visible = false
    const handle = new THREE.Mesh(
      new THREE.SphereGeometry(0.04, 12, 12),
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

    root.add(disc, arrow, ring, handle, label)
    this.group.add(root)
    return { root, disc, discMat, arrow, ring, handle, labelInner: inner, x: p.x, y: p.y, yaw: p.yaw }
  }

  private updateVisual(v: PointVisual, p: NavPointVO, selected: boolean) {
    v.x = p.x
    v.y = p.y
    v.yaw = p.yaw
    v.root.position.x = p.x
    v.root.position.y = p.y
    v.discMat.color.setHex(pointTypeColor(p.point_type))
    this.setArrow(v, p.yaw)
    v.ring.visible = selected
    v.handle.visible = selected
    if (v.labelInner.textContent !== p.point_code) v.labelInner.textContent = p.point_code
  }

  private setArrow(v: PointVisual, yaw: number) {
    v.yaw = yaw
    v.arrow.rotation.z = yaw - Math.PI / 2
    // 底面在圆心：沿朝向偏移半个箭头高度
    v.arrow.position.x = Math.cos(yaw) * (ARROW_H / 2)
    v.arrow.position.y = Math.sin(yaw) * (ARROW_H / 2)
    v.handle.position.x = Math.cos(yaw) * (R + 0.18)
    v.handle.position.y = Math.sin(yaw) * (R + 0.18)
  }

  /** 拖拽预览：不落库仅更新视觉 */
  setPose(id: number, x: number, y: number, yaw: number) {
    const v = this.visuals.get(id)
    if (!v) return
    v.root.position.x = x
    v.root.position.y = y
    this.setArrow(v, yaw)
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
