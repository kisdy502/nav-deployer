import * as THREE from 'three'
import { CSS2DObject } from 'three/examples/jsm/renderers/CSS2DRenderer.js'
import { angleDelta } from '@/utils/coords'

const TRAIL_MAX = 2000
/** 机器人矩形显示尺寸（实际车体 0.4m × 0.32m 的 2.5 倍左右），长边沿 +x，即朝向方向 */
const BODY_L = 1.02
const BODY_W = 0.7
/** 车体显示高度（视觉放大，配合灯光呈现立体感） */
const BODY_H = 0.5
/** 舵轮：三轮 120° 分布（对齐仿真底盘三舵轮构型） */
const WHEEL_R = 0.13
const WHEEL_MOUNT_R = 0.34
/** 机械臂示意连杆长度（参数化模型的兜底显示，URDF 可用时整体替换） */
const ARM_L1 = 0.55
const ARM_L2 = 0.45

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

interface JointSample {
  name: string[]
  position: number[]
}

/**
 * 机器人图层。两套模型按可用性自动切换：
 * - URDF 模型：GET /api/v1/robot/urdf 读到 robot_description 时加载（RViz 同款效果），
 *   mesh 路径 package://… 经 VITE_URDF_MESH_BASE 重写到可访问的 HTTP 地址；
 * - 参数化模型：车体长方体 + 三舵轮（转向角/滚动差分）+ 机械臂连杆示意，
 *   关节名匹配规则：含 steer → 舵轮转向；含 arm 或（joint 且非轮）→ 臂关节依序驱动。
 */
export class RobotLayer {
  readonly group = new THREE.Group()
  readonly trailGroup = new THREE.Group()
  private root = new THREE.Group()
  private bodyGroup = new THREE.Group()
  private paramGroup = new THREE.Group()
  private chevron = new THREE.Group()
  private steerPivots: THREE.Group[] = []
  private wheelSpins: THREE.Mesh[] = []
  private armBase: THREE.Group | null = null
  private armJoints: THREE.Group[] = []
  private urdfRobot: any = null
  private target: { x: number; y: number; yaw: number } | null = null
  private shown: { x: number; y: number; yaw: number } | null = null
  private segmentStart: { x: number; y: number; yaw: number } | null = null
  private segmentStartedAtMs = 0
  private segmentDurationMs = 200
  private lastTargetAtMs = 0
  private joints: JointSample | null = null
  private wheelRoll = 0
  private lastRollAt: { x: number; y: number } | null = null
  private trailLine: THREE.Line
  private trailAttr: THREE.BufferAttribute

  constructor() {
    this.group.position.z = 0.2

    const body = new THREE.Mesh(
      new THREE.BoxGeometry(BODY_L, BODY_W, BODY_H),
      new THREE.MeshLambertMaterial({ color: 0x409eff }),
    )
    body.position.z = BODY_H / 2 + 0.01
    const edges = new THREE.LineSegments(
      new THREE.EdgesGeometry(body.geometry),
      new THREE.LineBasicMaterial({ color: 0x1d6fd1 }),
    )
    edges.position.z = BODY_H / 2 + 0.01
    // 前部深色"头部"：侧视/斜视时也能分辨朝向
    const nose = new THREE.Mesh(
      new THREE.BoxGeometry(BODY_L * 0.22, BODY_W * 0.96, BODY_H * 1.02),
      new THREE.MeshLambertMaterial({ color: 0x1d6fd1 }),
    )
    nose.position.set(BODY_L / 2 - BODY_L * 0.11, 0, BODY_H / 2 + 0.01)
    // 几何中心点：观察行驶时与路线的偏离（贴顶面，小圆点减少遮挡）
    const centerDot = new THREE.Mesh(
      new THREE.CircleGeometry(0.05, 16),
      new THREE.MeshBasicMaterial({ color: 0xffffff }),
    )
    centerDot.position.z = BODY_H + 0.02
    // 「>>」朝向符号：两个空心 V 形紧邻排列（尾箭头前移贴住首箭头），位于机器人前部
    this.chevron.add(
      chevronMesh(0.16, 0.14, 0.11, 0.032, 0xffffff),
      chevronMesh(0.30, 0.14, 0.11, 0.032, 0xffffff),
    )
    this.chevron.position.z = BODY_H + 0.02

    // 三舵轮：120° 分布（0° 前左、120° 后、240° 前右近似仿真构型）。
    // 场景 z-up：圆柱默认轴沿 y 恰为左右方向，滚动即 rotation.y，转向为 steer 绕 z
    for (let i = 0; i < 3; i++) {
      const steer = new THREE.Group()
      const angle = (Math.PI / 3) * 2 * i + Math.PI / 6
      steer.position.set(
        Math.cos(angle) * WHEEL_MOUNT_R,
        Math.sin(angle) * WHEEL_MOUNT_R,
        WHEEL_R,
      )
      const wheel = new THREE.Mesh(
        new THREE.CylinderGeometry(WHEEL_R, WHEEL_R, 0.09, 20),
        new THREE.MeshLambertMaterial({ color: 0x2b3440 }),
      )
      steer.add(wheel)
      this.steerPivots.push(steer)
      this.wheelSpins.push(wheel)
      this.paramGroup.add(steer)
    }

    // 机械臂示意（参数化兜底）：底座回转 j1（绕 z）→ 大臂俯仰 j2（绕 y）→ 小臂俯仰 j3（绕 y）→ 末端
    this.armBase = buildParametricArm(this.armJoints)
    this.armBase.position.set(-BODY_L * 0.3, 0, BODY_H)
    this.armBase.visible = false
    this.paramGroup.add(this.armBase)

    this.paramGroup.add(body, edges, nose, centerDot)
    this.bodyGroup.add(this.paramGroup, this.chevron)

    const el = document.createElement('div')
    el.className = 'nd-label'
    const inner = document.createElement('span')
    inner.className = 'nd-label-inner nd-label-inner--robot'
    inner.textContent = 'AGV'
    el.appendChild(inner)
    const label = new CSS2DObject(el)
    // 标签挂在 root 上不随车体旋转，浮在车体上方（俯视与 3D 视角都清晰）
    label.position.set(0, 0.55, BODY_H + 0.25)

    // 车体组：模型、描边、朝向符号随 yaw 一起旋转；label 挂在 root 上保持文字不随车体转动
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

  /** 尝试加载机器人 URDF（成功则替换参数化模型，失败静默回退） */
  async tryLoadUrdf() {
    try {
      const res = await fetch('/api/v1/robot/urdf')
      if (!res.ok) return
      const xml = await res.text()
      const URDFLoader = (await import('urdf-loader')).default
      const manager = new THREE.LoadingManager()
      // 默认走上位机后端代理（host 取 ws-url、端口 sim-agv.urdf-mesh-port），
      // 浏览器同源加载免 CORS；VITE_URDF_MESH_BASE 可覆盖为直连机器人地址
      const base = ((import.meta.env.VITE_URDF_MESH_BASE as string | undefined) ?? '/api/v1/robot/mesh')
        .replace(/\/+$/, '')
      manager.setURLModifier((url) => {
        if (url.startsWith('package://')) {
          const path = url.slice('package://'.length)
          return base ? `${base}/${path}` : path
        }
        return url
      })
      const loader = new URDFLoader(manager)
      const robot = loader.parse(xml)
      if (!robot) return
      // URDF 本身就是 z-up、x-forward（ROS 语义），与本场景坐标约定一致，直接挂载
      this.urdfRobot = robot
      this.paramGroup.visible = false
      this.chevron.visible = false
      this.bodyGroup.add(robot)
      this.applyJoints()
    } catch {
      // URDF 不可用（机器人未连接 / 无 robot_description / mesh 加载失败）：保持参数化模型
    }
  }

  /** 关节状态到达（telemetry 快照 10Hz）：按名字规则分发到舵轮/机械臂或 URDF 关节 */
  setJointStates(joints: JointSample | null) {
    this.joints = joints
    this.applyJoints()
  }

  private applyJoints() {
    const joints = this.joints
    if (!joints) return
    if (this.urdfRobot) {
      const byName = new Map(joints.name.map((n, i) => [n, joints.position[i] ?? 0]))
      for (const name of Object.keys(this.urdfRobot.joints ?? {})) {
        if (byName.has(name)) {
          this.urdfRobot.joints[name].setJointValue(byName.get(name)!)
        }
      }
      return
    }
    let steerIndex = 0
    const armAngles: number[] = []
    joints.name.forEach((name, i) => {
      const lower = name.toLowerCase()
      const value = joints.position[i] ?? 0
      if (lower.includes('steer') && steerIndex < this.steerPivots.length) {
        this.steerPivots[steerIndex++].rotation.z = value
      } else if (isArmJoint(lower)) {
        armAngles.push(value)
      }
    })
    if (this.armBase) {
      this.armBase.visible = armAngles.length > 0
      const axisFor = (i: number) => (i === 0 ? 'z' : 'y')
      this.armJoints.forEach((pivot, i) => {
        const axis = axisFor(i)
        const value = armAngles[i] ?? (axis === 'z' ? 0 : 0.35)
        if (axis === 'z') pivot.rotation.z = value
        else pivot.rotation.y = value
      })
    }
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
      this.lastRollAt = { x: pose.x, y: pose.y }
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

  /** 每帧调用：沿相邻遥测样本做匀速时间插值 + 轮子滚动差分累计。 */
  tick(_dt: number) {
    if (!this.target || !this.shown || !this.segmentStart) return
    const t = Math.min(1, Math.max(0,
      (performance.now() - this.segmentStartedAtMs) / this.segmentDurationMs,
    ))
    this.shown.x = this.segmentStart.x + (this.target.x - this.segmentStart.x) * t
    this.shown.y = this.segmentStart.y + (this.target.y - this.segmentStart.y) * t
    this.shown.yaw = this.segmentStart.yaw + angleDelta(this.segmentStart.yaw, this.target.yaw) * t

    // 轮子滚动：由插值位姿差分累计（真实关节反馈缺失时的近似，前进为正）
    if (this.lastRollAt) {
      const ds = Math.hypot(this.shown.x - this.lastRollAt.x, this.shown.y - this.lastRollAt.y)
      if (ds > 0.0005) {
        this.wheelRoll += ds / WHEEL_R
        this.lastRollAt = { x: this.shown.x, y: this.shown.y }
        for (const wheel of this.wheelSpins) wheel.rotation.y = this.wheelRoll
      }
    } else {
      this.lastRollAt = { x: this.shown.x, y: this.shown.y }
    }

    this.apply()
  }

  private apply() {
    if (!this.shown) return
    this.root.position.x = this.shown.x
    this.root.position.y = this.shown.y
    // 机器人朝向 = 车体模型的朝向：模型与「》」符号整体随 yaw 旋转，二者永远一致
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

/** 臂关节判定：名字含 arm，或含 joint 但不属于轮组（steer/wheel） */
function isArmJoint(lowerName: string): boolean {
  if (lowerName.includes('arm')) return true
  if (lowerName.includes('steer') || lowerName.includes('wheel')) return false
  return lowerName.includes('joint')
}

/** 参数化机械臂：底座 → j1 回转（绕 z）→ 大臂 → j2 俯仰（绕 y）→ 小臂 → j3 俯仰（绕 y）→ 末端 */
function buildParametricArm(jointsOut: THREE.Group[]): THREE.Group {
  const base = new THREE.Group()
  const dark = new THREE.MeshLambertMaterial({ color: 0x2b3440 })
  const mid = new THREE.MeshLambertMaterial({ color: 0x67c23a })

  const pedestal = new THREE.Mesh(new THREE.CylinderGeometry(0.09, 0.12, 0.1, 16), dark)
  // 场景 z-up：圆柱默认轴沿 y，旋转到沿 z
  pedestal.rotation.x = Math.PI / 2
  base.add(pedestal)

  const j1 = new THREE.Group()
  const shoulder = new THREE.Mesh(new THREE.SphereGeometry(0.07, 14, 12), dark)
  j1.add(shoulder)
  base.add(j1)

  const link1Pivot = new THREE.Group()
  const link1 = new THREE.Mesh(new THREE.BoxGeometry(0.09, 0.09, ARM_L1), mid)
  link1.position.z = ARM_L1 / 2
  link1Pivot.add(link1)
  const elbow = new THREE.Mesh(new THREE.SphereGeometry(0.055, 12, 10), dark)
  elbow.position.z = ARM_L1
  link1Pivot.add(elbow)
  j1.add(link1Pivot)

  const j2 = new THREE.Group()
  j2.position.z = ARM_L1
  const link2 = new THREE.Mesh(new THREE.BoxGeometry(0.07, 0.07, ARM_L2), mid)
  link2.position.z = ARM_L2 / 2
  j2.add(link2)
  const wrist = new THREE.Mesh(new THREE.SphereGeometry(0.045, 12, 10), dark)
  wrist.position.z = ARM_L2
  j2.add(wrist)
  link1Pivot.add(j2)

  const j3 = new THREE.Group()
  j3.position.z = ARM_L2
  const gripper = new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.12, 0.05), dark)
  j3.add(gripper)
  j2.add(j3)

  jointsOut.push(j1, link1Pivot, j2)
  return base
}
