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
  /** 最近一帧 base_link 系原始点（重定位预览复用：换临时位姿重新投影） */
  rawFlat: number[] | null
  baseOpacity: number
}

/** 重定位预览槽：只有点（青色高亮），不带连线 */
interface PreviewSlot {
  points: THREE.Points
  attr: THREE.BufferAttribute
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
  private previewSlots: PreviewSlot[] = []
  /** 重定位预览生效中的临时位姿（null = 未预览） */
  private previewPose: { x: number; y: number; yaw: number } | null = null

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

      this.slots.push({ points: pts, attr, count: 0, link, linkAttr, rawFlat: null, baseOpacity: 0.9 })
    }

    // 重定位预览点云（青色）：以"重定位临时位姿"替代上报位姿投影同一份 base 系雷达数据，
    // 与地图障碍物的贴合程度即可判断新位姿/朝向是否正确
    for (let i = 0; i < 2; i++) {
      const buf = new Float32Array(MAX_POINTS * 3)
      const attr = new THREE.BufferAttribute(buf, 3)
      attr.setUsage(THREE.DynamicDrawUsage)
      const geo = new THREE.BufferGeometry()
      geo.setAttribute('position', attr)
      const pts = new THREE.Points(
        geo,
        new THREE.PointsMaterial({
          color: 0x00e5ff,
          size: POINT_SIZE + 1,
          sizeAttenuation: false,
          transparent: true,
          opacity: 0.95,
        }),
      )
      pts.frustumCulled = false
      pts.visible = false
      pts.renderOrder = 5
      this.group.add(pts)
      this.previewSlots.push({ points: pts, attr })
    }
  }

  setScan(index: 1 | 2, flat: number[] | null, pose: { x: number; y: number; yaw: number } | null) {
    const slot = this.slots[index - 1]
    if (!flat || flat.length < 2 || !pose) {
      slot.points.visible = false
      slot.count = 0
      slot.link.visible = false
      slot.rawFlat = null
    } else {
      slot.rawFlat = flat
      this.projectInto(slot, flat, pose)
      slot.points.visible = !this.previewPose
      slot.link.visible = !this.previewPose
    }
    // 预览期间新雷达帧到达：用最新数据刷新预览（机器人在重定位时通常静止，帧间差异小）
    if (this.previewPose) {
      this.applyPreview(this.previewPose)
    }
  }

  /** base 系点按指定位姿投影进槽位缓冲（点 + 到中心的连线）。 */
  private projectInto(slot: ScanSlot, flat: number[], pose: { x: number; y: number; yaw: number }) {
    const n = Math.min(Math.floor(flat.length / 2), MAX_POINTS)
    const cos = Math.cos(pose.yaw)
    const sin = Math.sin(pose.yaw)
    const cx = pose.x
    const cy = pose.y
    for (let i = 0; i < n; i++) {
      const px = flat[i * 2]
      const py = flat[i * 2 + 1]
      const wx = cx + cos * px - sin * py
      const wy = cy + sin * px + cos * py
      slot.attr.setXYZ(i, wx, wy, 0)
      slot.linkAttr.setXYZ(i * 2, cx, cy, 0)
      slot.linkAttr.setXYZ(i * 2 + 1, wx, wy, 0)
    }
    slot.attr.needsUpdate = true
    slot.count = n
    slot.points.geometry.setDrawRange(0, n)
    slot.linkAttr.needsUpdate = true
    slot.link.geometry.setDrawRange(0, n * 2)
  }

  /**
   * 重定位预览：以临时位姿替代上报位姿，把最近一帧 base 系雷达数据重新投影为青色点云；
   * 原（按上报位姿绘制的）点云变暗避让。传 null 结束预览并恢复。
   */
  setRelocalizePreview(pose: { x: number; y: number; yaw: number } | null) {
    this.previewPose = pose
    if (!pose) {
      for (const preview of this.previewSlots) preview.points.visible = false
      for (const slot of this.slots) {
        ;(slot.points.material as THREE.PointsMaterial).opacity = slot.baseOpacity
        if (slot.count > 0) {
          slot.points.visible = true
          slot.link.visible = true
        }
      }
      return
    }
    for (const slot of this.slots) {
      ;(slot.points.material as THREE.PointsMaterial).opacity = 0.18
      slot.link.visible = false
    }
    this.applyPreview(pose)
  }

  private applyPreview(pose: { x: number; y: number; yaw: number }) {
    const cos = Math.cos(pose.yaw)
    const sin = Math.sin(pose.yaw)
    for (let i = 0; i < this.slots.length; i++) {
      const preview = this.previewSlots[i]
      const raw = this.slots[i].rawFlat
      if (!raw || raw.length < 4) {
        preview.points.visible = false
        continue
      }
      const n = Math.min(Math.floor(raw.length / 2), MAX_POINTS)
      for (let k = 0; k < n; k++) {
        const px = raw[k * 2]
        const py = raw[k * 2 + 1]
        preview.attr.setXYZ(k, pose.x + cos * px - sin * py, pose.y + sin * px + cos * py, 0)
      }
      preview.attr.needsUpdate = true
      preview.points.geometry.setDrawRange(0, n)
      preview.points.visible = true
    }
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
    for (const p of this.previewSlots) {
      p.points.geometry.dispose()
      ;(p.points.material as THREE.Material).dispose()
    }
    this.slots = []
    this.previewSlots = []
    this.group.clear()
  }
}
