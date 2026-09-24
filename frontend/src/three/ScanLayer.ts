import * as THREE from 'three'

const SCAN_COLORS = [0x36cfc9, 0xffa940] // 雷达1 青色 / 雷达2 橙色
const MAX_POINTS = 2000

interface ScanSlot {
  points: THREE.Points
  attr: THREE.BufferAttribute
  count: number
}

/**
 * 双雷达点云图层。
 * ScanCloud.points 为 base_link 系扁平 [x0,y0,x1,y1,...]（米），
 * 必须用该帧捕获时的 map 系位姿（pose）投影到地图，pose 为 null 时不绘制。
 */
export class ScanLayer {
  readonly group = new THREE.Group()
  private slots: ScanSlot[] = []

  constructor() {
    this.group.position.z = 0.12
    for (let i = 0; i < 2; i++) {
      const buf = new Float32Array(MAX_POINTS * 3)
      const attr = new THREE.BufferAttribute(buf, 3)
      attr.setUsage(THREE.DynamicDrawUsage)
      const geo = new THREE.BufferGeometry()
      geo.setAttribute('position', attr)
      const pts = new THREE.Points(
        geo,
        new THREE.PointsMaterial({
          color: SCAN_COLORS[i],
          size: 3,
          sizeAttenuation: false,
          transparent: true,
          opacity: 0.85,
        }),
      )
      pts.frustumCulled = false
      pts.visible = false
      this.group.add(pts)
      this.slots.push({ points: pts, attr, count: 0 })
    }
  }

  setScan(index: 1 | 2, flat: number[] | null, pose: { x: number; y: number; yaw: number } | null) {
    const slot = this.slots[index - 1]
    if (!flat || flat.length < 2 || !pose) {
      slot.points.visible = false
      slot.count = 0
      return
    }
    const n = Math.min(Math.floor(flat.length / 2), MAX_POINTS)
    const cos = Math.cos(pose.yaw)
    const sin = Math.sin(pose.yaw)
    for (let i = 0; i < n; i++) {
      const px = flat[i * 2]
      const py = flat[i * 2 + 1]
      slot.attr.setXYZ(
        i,
        pose.x + cos * px - sin * py,
        pose.y + sin * px + cos * py,
        0,
      )
    }
    slot.attr.needsUpdate = true
    slot.count = n
    slot.points.geometry.setDrawRange(0, n)
    slot.points.visible = true
  }

  setLayerVisible(visible: boolean) {
    this.group.visible = visible
  }

  dispose() {
    for (const s of this.slots) {
      s.points.geometry.dispose()
      ;(s.points.material as THREE.Material).dispose()
    }
    this.slots = []
    this.group.clear()
  }
}
