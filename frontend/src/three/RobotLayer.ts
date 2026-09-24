import * as THREE from 'three'
import { CSS2DObject } from 'three/examples/jsm/renderers/CSS2DRenderer.js'
import { angleDelta } from '@/utils/coords'

const TRAIL_MAX = 2000
/** 机器人矩形显示尺寸（实际车体 0.4m × 0.32m 的 4 倍左右，宽度略收窄避免与 AGV 标签重叠），长边沿 +x，即朝向方向 */
const BODY_L = 1.6
const BODY_W = 1.1

function triangleMesh(a: [number, number], b: [number, number], c: [number, number], color: number): THREE.Mesh {
  const g = new THREE.BufferGeometry()
  g.setAttribute('position', new THREE.Float32BufferAttribute([a[0], a[1], 0, b[0], b[1], 0, c[0], c[1], 0], 3))
  return new THREE.Mesh(g, new THREE.MeshBasicMaterial({ color, side: THREE.DoubleSide }))
}

/** 机器人图层：蓝色矩形车体（整体随朝向旋转，长边即朝向）+ 车内「》」朝向符号 + 几何中心点（观察停靠对齐）+ 轨迹线 */
export class RobotLayer {
  readonly group = new THREE.Group()
  readonly trailGroup = new THREE.Group()
  private root = new THREE.Group()
  private bodyGroup = new THREE.Group()
  private chevron = new THREE.Group()
  private target: { x: number; y: number; yaw: number } | null = null
  private shown: { x: number; y: number; yaw: number } | null = null
  private trailLine: THREE.Line
  private trailAttr: THREE.BufferAttribute

  constructor() {
    this.group.position.z = 0.2

    const body = new THREE.Mesh(
      new THREE.PlaneGeometry(BODY_L, BODY_W),
      new THREE.MeshBasicMaterial({ color: 0x409eff, side: THREE.DoubleSide }),
    )
    body.position.z = 0.01
    const border = new THREE.LineLoop(
      new THREE.BufferGeometry().setFromPoints([
        new THREE.Vector3(-BODY_L / 2, -BODY_W / 2, 0.02),
        new THREE.Vector3(BODY_L / 2, -BODY_W / 2, 0.02),
        new THREE.Vector3(BODY_L / 2, BODY_W / 2, 0.02),
        new THREE.Vector3(-BODY_L / 2, BODY_W / 2, 0.02),
      ]),
      new THREE.LineBasicMaterial({ color: 0x1d6fd1 }),
    )
    // 几何中心点：观察停靠对齐
    const centerDot = new THREE.Mesh(
      new THREE.CircleGeometry(0.096, 16),
      new THREE.MeshBasicMaterial({ color: 0xffffff }),
    )
    centerDot.position.z = 0.03
    // 「》」朝向符号（两个尖角，顶点按车体中心对称取值，随车体整体旋转）
    this.chevron.add(
      triangleMesh([-0.2, 0.18], [0, 0], [-0.2, -0.18], 0xffffff),
      triangleMesh([0, 0.18], [0.2, 0], [0, -0.18], 0xffffff),
    )
    this.chevron.position.z = 0.04

    const el = document.createElement('div')
    el.className = 'nd-label'
    const inner = document.createElement('span')
    inner.className = 'nd-label-inner nd-label-inner--robot'
    inner.textContent = 'AGV'
    el.appendChild(inner)
    const label = new CSS2DObject(el)

    // 车体组：矩形、边框、朝向符号随 yaw 一起旋转；label 挂在 root 上保持文字不随车体转动
    this.bodyGroup.add(body, border, centerDot, this.chevron)
    this.root.add(this.bodyGroup, label)
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

  /** 当前平滑显示中的位姿（供点云等图层跟随），未就绪时为 null */
  get currentPose(): { x: number; y: number; yaw: number } | null {
    return this.shown
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
    // 机器人朝向 = 车体矩形的朝向：矩形与「》」符号整体随 yaw 旋转，二者永远一致
    this.bodyGroup.rotation.z = this.shown.yaw
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
