import * as THREE from 'three'
import { CSS2DObject } from 'three/examples/jsm/renderers/CSS2DRenderer.js'
import { angleDelta } from '@/utils/coords'

const TRAIL_MAX = 2000
/** 机器人矩形显示尺寸（实际车体 0.4m × 0.32m 的 2.5 倍左右），长边沿 +x，即朝向方向 */
const BODY_L = 1.02
const BODY_W = 0.7

/** ">" V 形符号（空心，粗线条由外/内两条 V 边之间的填充区域构成） */
function chevronMesh(x: number, size: number, halfH: number, thickness: number, color: number): THREE.Mesh {
  const s = new THREE.Shape()
  s.moveTo(x, halfH)
  s.lineTo(x + size, 0)
  s.lineTo(x, -halfH)
  s.lineTo(x, -halfH + thickness)
  s.lineTo(x + size - thickness * 1.8, 0)
  s.lineTo(x, halfH - thickness)
  s.closePath()
  return new THREE.Mesh(
    new THREE.ShapeGeometry(s),
    new THREE.MeshBasicMaterial({ color, side: THREE.DoubleSide }),
  )
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
  private segmentStart: { x: number; y: number; yaw: number } | null = null
  private segmentStartedAtMs = 0
  private segmentDurationMs = 200
  private lastTargetAtMs = 0
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
      new THREE.CircleGeometry(0.077, 16),
      new THREE.MeshBasicMaterial({ color: 0xffffff }),
    )
    centerDot.position.z = 0.03
    // 「>>」朝向符号：两个空心 V 形，位于机器人前部（不与中心圆点重叠）
    this.chevron.add(
      chevronMesh(0.10, 0.14, 0.11, 0.032, 0xffffff),
      chevronMesh(0.30, 0.14, 0.11, 0.032, 0xffffff),
    )
    this.chevron.position.z = 0.04

    const el = document.createElement('div')
    el.className = 'nd-label'
    const inner = document.createElement('span')
    inner.className = 'nd-label-inner nd-label-inner--robot'
    inner.textContent = 'AGV'
    el.appendChild(inner)
    const label = new CSS2DObject(el)
    // 标签上移出车体（挂在 root 上不随车体旋转，世界系固定在机器人上方）
    label.position.set(0, 0.55, 0)

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
    this.group.visible = !!pose
    if (!pose) {
      this.target = null
      this.segmentStart = null
      this.lastTargetAtMs = 0
      return
    }

    const now = performance.now()
    const gapMs = this.lastTargetAtMs > 0 ? now - this.lastTargetAtMs : 0
    const jumpDistance = this.shown ? Math.hypot(pose.x - this.shown.x, pose.y - this.shown.y) : 0
    const jumpYaw = this.shown ? Math.abs(angleDelta(this.shown.yaw, pose.yaw)) : 0

    // 首帧、长时间断流或重定位跳变直接落位；普通遥测样本按上一个到达周期匀速插值。
    if (!this.shown || gapMs > 1000 || jumpDistance > 2 || jumpYaw > Math.PI / 2) {
      this.shown = { ...pose }
      this.segmentStart = { ...pose }
      this.target = { ...pose }
      this.segmentStartedAtMs = now
      this.segmentDurationMs = 1
      this.apply()
    } else if (!this.target
      || pose.x !== this.target.x
      || pose.y !== this.target.y
      || pose.yaw !== this.target.yaw) {
      this.segmentStart = { ...this.shown }
      this.target = { ...pose }
      this.segmentStartedAtMs = now
      // 当前观测到的消息周期，是下一段持续时间的最佳估计；限制范围抵抗偶发网络抖动。
      this.segmentDurationMs = Math.min(350, Math.max(60, gapMs || 200))
    }
    this.lastTargetAtMs = now
  }

  /** 每帧调用：沿相邻遥测样本做匀速时间插值，避免低频目标下的指数“追一下、停一下”。 */
  tick(_dt: number) {
    if (!this.target || !this.shown || !this.segmentStart) return
    const t = Math.min(1, Math.max(0,
      (performance.now() - this.segmentStartedAtMs) / this.segmentDurationMs,
    ))
    this.shown.x = this.segmentStart.x + (this.target.x - this.segmentStart.x) * t
    this.shown.y = this.segmentStart.y + (this.target.y - this.segmentStart.y) * t
    this.shown.yaw = this.segmentStart.yaw + angleDelta(this.segmentStart.yaw, this.target.yaw) * t
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
