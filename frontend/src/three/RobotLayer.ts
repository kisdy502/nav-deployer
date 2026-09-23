import * as THREE from 'three'
import { CSS2DObject } from 'three/examples/jsm/renderers/CSS2DRenderer.js'
import { angleDelta } from '@/utils/coords'

const TRAIL_MAX = 2000

/** 机器人图层：位姿箭头（平滑趋近目标）+ 历史轨迹线 */
export class RobotLayer {
  readonly group = new THREE.Group()
  readonly trailGroup = new THREE.Group()
  private root = new THREE.Group()
  private arrow: THREE.Mesh
  private target: { x: number; y: number; yaw: number } | null = null
  private shown: { x: number; y: number; yaw: number } | null = null
  private trailLine: THREE.Line
  private trailAttr: THREE.BufferAttribute

  constructor() {
    this.group.position.z = 0.2
    const base = new THREE.Mesh(
      new THREE.CircleGeometry(0.28, 32),
      new THREE.MeshBasicMaterial({ color: 0x409eff, transparent: true, opacity: 0.95 }),
    )
    base.position.z = 0.01
    this.arrow = new THREE.Mesh(
      new THREE.ConeGeometry(0.12, 0.42, 12),
      new THREE.MeshBasicMaterial({ color: 0xd946ef }),
    )
    this.arrow.position.z = 0.03
    const el = document.createElement('div')
    el.className = 'nd-label'
    const inner = document.createElement('span')
    inner.className = 'nd-label-inner nd-label-inner--robot'
    inner.textContent = 'AGV'
    el.appendChild(inner)
    const label = new CSS2DObject(el)
    this.root.add(base, this.arrow, label)
    this.group.add(this.root)

    const buf = new Float32Array(TRAIL_MAX * 3)
    const geo = new THREE.BufferGeometry()
    this.trailAttr = new THREE.BufferAttribute(buf, 3)
    this.trailAttr.setUsage(THREE.DynamicDrawUsage)
    geo.setAttribute('position', this.trailAttr)
    this.trailLine = new THREE.Line(geo, new THREE.LineBasicMaterial({ color: 0xff9a3c }))
    this.trailLine.frustumCulled = false
    this.trailLine.position.z = 0.04
    this.trailGroup.add(this.trailLine)

    this.group.visible = false
  }

  setTarget(pose: { x: number; y: number; yaw: number } | null) {
    this.target = pose
    this.group.visible = !!pose
    if (pose && !this.shown) {
      this.shown = { ...pose }
      this.apply()
    }
  }

  /** 每帧调用：向目标位姿指数趋近 */
  tick(dt: number) {
    if (!this.target || !this.shown) return
    const k = 1 - Math.exp(-dt * 10)
    this.shown.x += (this.target.x - this.shown.x) * k
    this.shown.y += (this.target.y - this.shown.y) * k
    this.shown.yaw += angleDelta(this.shown.yaw, this.target.yaw) * k
    this.apply()
  }

  private apply() {
    if (!this.shown) return
    this.root.position.x = this.shown.x
    this.root.position.y = this.shown.y
    this.arrow.rotation.z = this.shown.yaw - Math.PI / 2
  }

  setTrail(points: { x: number; y: number }[]) {
    const n = Math.min(points.length, TRAIL_MAX)
    const start = points.length > TRAIL_MAX ? points.length - TRAIL_MAX : 0
    for (let i = 0; i < n; i++) {
      this.trailAttr.setXYZ(i, points[start + i].x, points[start + i].y, 0)
    }
    this.trailAttr.needsUpdate = true
    this.trailLine.geometry.setDrawRange(0, n)
  }

  clearTrail() {
    this.trailLine.geometry.setDrawRange(0, 0)
  }

  dispose() {
    this.group.clear()
    this.trailGroup.clear()
    this.trailLine.geometry.dispose()
  }
}
