import * as THREE from 'three'

// 统一红色系，前后略微区分：前雷达亮红 / 后雷达深红
const SCAN_COLORS = [0xff3030, 0xc01010]
const MAX_POINTS = 2000
const POINT_SIZE = 4 // 半径+1（原 3）

interface ScanSlot {
  points: THREE.Points
  attr: THREE.BufferAttribute
  count: number
  link: THREE.LineSegments
  linkAttr: THREE.BufferAttribute
}

/**
 * 双雷达点云图层。
 * ScanCloud.points 为 base_link 系扁平 [x0,y0,x1,y1,...]（米），
 * 必须用该帧捕获时的 map 系位姿（pose）投影到地图，pose 为 null 时不绘制。
 * 每个点云点附带一条到机器人中心的半透明连线（LineSegments，逐点成段）。
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
          size: POINT_SIZE,
          sizeAttenuation: false,
          transparent: true,
          opacity: 0.9,
        }),
      )
      pts.frustumCulled = false
      pts.visible = false
      this.group.add(pts)

      // 每个点云点一段连线：(机器人中心, 点) 成对顶点，LineSegments 绘制
      const linkBuf = new Float32Array(MAX_POINTS * 2 * 3)
      const linkAttr = new THREE.BufferAttribute(linkBuf, 3)
      linkAttr.setUsage(THREE.DynamicDrawUsage)
      const linkGeo = new THREE.BufferGeometry()
      linkGeo.setAttribute('position', linkAttr)
      const link = new THREE.LineSegments(linkGeo, new THREE.LineBasicMaterial({
        color: 0xc8e6c9, // 接近白色的浅绿
        transparent: true,
        opacity: 0.2,
      }))
      link.frustumCulled = false
      link.visible = false
      this.group.add(link)

      this.slots.push({ points: pts, attr, count: 0, link, linkAttr })
    }
  }

  setScan(index: 1 | 2, flat: number[] | null, pose: { x: number; y: number; yaw: number } | null) {
    const slot = this.slots[index - 1]
    if (!flat || flat.length < 2 || !pose) {
      slot.points.visible = false
      slot.count = 0
      slot.link.visible = false
      return
    }
    const n = Math.min(Math.floor(flat.length / 2), MAX_POINTS)
    const cos = Math.cos(pose.yaw)
    const sin = Math.sin(pose.yaw)
    // 本组挂在场景根（map 系）：机器人中心 = 该帧绑定的位姿。
    // 点和连线使用完全相同的捕获时位姿，连线只负责可视化，不参与坐标计算。
    const cx = pose.x
    const cy = pose.y
    for (let i = 0; i < n; i++) {
      const px = flat[i * 2]
      const py = flat[i * 2 + 1]
      const wx = cx + cos * px - sin * py
      const wy = cy + sin * px + cos * py
      slot.attr.setXYZ(i, wx, wy, 0)
      // 连线段顶点成对写入：[中心, 点]
      slot.linkAttr.setXYZ(i * 2, cx, cy, 0)
      slot.linkAttr.setXYZ(i * 2 + 1, wx, wy, 0)
    }
    slot.attr.needsUpdate = true
    slot.count = n
    slot.points.geometry.setDrawRange(0, n)
    slot.points.visible = true
    slot.linkAttr.needsUpdate = true
    slot.link.geometry.setDrawRange(0, n * 2)
    slot.link.visible = true
  }

  setLayerVisible(visible: boolean) {
    this.group.visible = visible
  }

  dispose() {
    for (const s of this.slots) {
      s.points.geometry.dispose()
      ;(s.points.material as THREE.Material).dispose()
      s.link.geometry.dispose()
      ;(s.link.material as THREE.Material).dispose()
    }
    this.slots = []
    this.group.clear()
  }
}
