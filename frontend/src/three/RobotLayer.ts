import * as THREE from 'three'
import { CSS2DObject } from 'three/examples/jsm/renderers/CSS2DRenderer.js'
import { angleDelta } from '@/utils/coords'

const TRAIL_MAX = 2000
/** 车体真实比例（对齐 Gazebo 三舵轮 AGV，长边沿 +x 即朝向方向）；全局视图偏小时用缩放工具放大 */
const BODY_L = 0.42
const BODY_W = 0.32
const BODY_H = 0.14
/** 三舵轮布局（对齐 /joint_states 命名：front / left / right，前 1 + 后 2） */
const WHEEL_R = 0.06
const WHEEL_W = 0.04
const WHEEL_FRONT = { x: 0.15, y: 0 }
const WHEEL_LEFT = { x: -0.14, y: 0.1 }
const WHEEL_RIGHT = { x: -0.14, y: -0.1 }
/** 机械臂尺寸（对齐 UR5e 真实构型：底座 + 大臂 0.42 + 前臂 0.39 + 腕） */
const ARM_BASE_H = 0.08
const ARM_L1 = 0.42
const ARM_L2 = 0.39

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
  private steerPivots = new Map<string, THREE.Group>()
  private wheelMeshes = new Map<string, THREE.Mesh>()
  private hasWheelFeedback = false
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

    // 底盘（Gazebo 风格：浅灰车体 + 蓝色描边 + 前部蓝色饰条分辨朝向），底面离地 = 轮半径
    const chassisZ = WHEEL_R
    const body = new THREE.Mesh(
      new THREE.BoxGeometry(BODY_L, BODY_W, BODY_H),
      new THREE.MeshLambertMaterial({ color: 0xd7dde6 }),
    )
    body.position.z = chassisZ + BODY_H / 2
    const edges = new THREE.LineSegments(
      new THREE.EdgesGeometry(body.geometry),
      new THREE.LineBasicMaterial({ color: 0x1d6fd1 }),
    )
    edges.position.z = chassisZ + BODY_H / 2
    const nose = new THREE.Mesh(
      new THREE.BoxGeometry(BODY_L * 0.18, BODY_W * 0.98, BODY_H * 0.6),
      new THREE.MeshLambertMaterial({ color: 0x409eff }),
    )
    nose.position.set(BODY_L / 2 - BODY_L * 0.09, 0, chassisZ + BODY_H)
    const centerDot = new THREE.Mesh(
      new THREE.CircleGeometry(0.018, 12),
      new THREE.MeshBasicMaterial({ color: 0xffffff }),
    )
    centerDot.position.z = chassisZ + BODY_H + 0.005
    // 「>>」朝向符号：贴顶面前部
    this.chevron.add(
      chevronMesh(0.06, 0.05, 0.04, 0.012, 0x409eff),
      chevronMesh(0.115, 0.05, 0.04, 0.012, 0x409eff),
    )
    this.chevron.position.z = chassisZ + BODY_H + 0.006

    // 三舵轮轮组（front / left / right，布局对齐 /joint_states 命名）：
    // 转向罩（银灰小圆柱）+ 舵轮（黑圆柱，轴沿 y，滚动 rotation.y，转向 steer 绕 z）
    const wheelSites: Array<[string, { x: number; y: number }]> = [
      ['front', WHEEL_FRONT], ['left', WHEEL_LEFT], ['right', WHEEL_RIGHT],
    ]
    for (const [site, pos] of wheelSites) {
      const steer = new THREE.Group()
      steer.position.set(pos.x, pos.y, 0)
      const wheel = new THREE.Mesh(
        new THREE.CylinderGeometry(WHEEL_R, WHEEL_R, WHEEL_W, 20),
        new THREE.MeshLambertMaterial({ color: 0x2b3440 }),
      )
      wheel.position.z = WHEEL_R
      const hub = new THREE.Mesh(
        new THREE.CylinderGeometry(WHEEL_R + 0.015, WHEEL_R + 0.015, 0.035, 20),
        new THREE.MeshLambertMaterial({ color: 0x9aa4b1 }),
      )
      hub.position.z = WHEEL_R * 2 + 0.02
      steer.add(wheel, hub)
      this.steerPivots.set(site, steer)
      this.wheelMeshes.set(site, wheel)
      this.paramGroup.add(steer)
    }

    // 机械臂（UR5e 风格立式构型：底座 + 肩 + 大臂 + 肘 + 前臂 + 腕），立于车顶后部
    this.armBase = buildParametricArm(this.armJoints)
    this.armBase.position.set(-BODY_L * 0.28, 0, chassisZ + BODY_H)
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
    label.position.set(0, 0.55, chassisZ + BODY_H + 0.25)

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
    const armAngles: number[] = []
    this.hasWheelFeedback = false
    joints.name.forEach((name, i) => {
      const lower = name.toLowerCase()
      const value = joints.position[i] ?? 0
      if (lower.includes('steer')) {
        // 转向关节：按 front/left/right 定位轮组
        const pivot = this.steerPivots.get(wheelSite(lower) ?? '')
        if (pivot) pivot.rotation.z = value
      } else if (lower.includes('wheel')) {
        // 滚动关节：仿真侧给了真实累计角，直接驱动
        const wheel = this.wheelMeshes.get(wheelSite(lower) ?? '')
        if (wheel) {
          wheel.rotation.y = value
          this.hasWheelFeedback = true
        }
      } else if (isArmJoint(lower)) {
        armAngles.push(value)
      }
    })
    if (this.armBase) {
      this.armBase.visible = armAngles.length > 0
      // UR5e 构型：j1 回转(绕 z)，j2/j3/j4 俯仰(绕 y)；缺省角取 UR5e 典型姿态
      const defaults = [0, 0.6, -1.2, 0.5]
      this.armJoints.forEach((pivot, i) => {
        const value = armAngles[i] ?? defaults[i] ?? 0
        if (i === 0) pivot.rotation.z = value
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

    // 轮子滚动兜底：仿真侧未提供滚动关节反馈时，由插值位姿差分近似（前进为正）
    if (this.lastRollAt) {
      const ds = Math.hypot(this.shown.x - this.lastRollAt.x, this.shown.y - this.lastRollAt.y)
      if (ds > 0.0005) {
        this.wheelRoll += ds / WHEEL_R
        this.lastRollAt = { x: this.shown.x, y: this.shown.y }
        if (!this.hasWheelFeedback) {
          for (const wheel of this.wheelMeshes.values()) wheel.rotation.y = this.wheelRoll
        }
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

/** 关节名 → 轮组站点（front/left/right），未知返回 null */
function wheelSite(lowerName: string): string | null {
  if (lowerName.includes('front')) return 'front'
  if (lowerName.includes('left')) return 'left'
  if (lowerName.includes('right')) return 'right'
  return null
}

/** 参数化机械臂（UR5e 风格立式构型）：
 * 底座 → j1 回转(绕 z) → 肩 j2 俯仰(绕 y) → 大臂 → 肘 j3 俯仰(绕 y) → 前臂 → 腕 j4 俯仰(绕 y)。
 * 白色连杆 + 深灰关节球对齐 UR5e 配色；连杆沿 z 立式展开，3D 环绕视角完整可见。 */
function buildParametricArm(jointsOut: THREE.Group[]): THREE.Group {
  const base = new THREE.Group()
  const dark = new THREE.MeshLambertMaterial({ color: 0x2b3440 })
  const white = new THREE.MeshLambertMaterial({ color: 0xeef1f6 })
  const radius = 0.036
  const jointR = 0.045

  // 底座圆柱（轴沿 z：默认轴沿 y，转到 z）
  const pedestal = new THREE.Mesh(new THREE.CylinderGeometry(0.05, 0.06, ARM_BASE_H, 16), dark)
  pedestal.rotation.x = Math.PI / 2
  pedestal.position.z = ARM_BASE_H / 2
  base.add(pedestal)

  const j1 = new THREE.Group()
  const shoulder = new THREE.Mesh(new THREE.SphereGeometry(jointR, 14, 12), dark)
  shoulder.position.z = ARM_BASE_H
  j1.add(shoulder)
  base.add(j1)

  const j2 = new THREE.Group()
  j2.position.z = ARM_BASE_H
  const link1 = new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.06, ARM_L1), white)
  link1.position.z = ARM_L1 / 2
  j2.add(link1)
  const elbow = new THREE.Mesh(new THREE.SphereGeometry(jointR, 14, 12), dark)
  elbow.position.z = ARM_L1
  j2.add(elbow)
  j1.add(j2)

  const j3 = new THREE.Group()
  j3.position.z = ARM_L1
  const link2 = new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.05, ARM_L2), white)
  link2.position.z = ARM_L2 / 2
  j3.add(link2)
  const wrist = new THREE.Mesh(new THREE.SphereGeometry(jointR * 0.9, 12, 10), dark)
  wrist.position.z = ARM_L2
  j3.add(wrist)
  j2.add(j3)

  const j4 = new THREE.Group()
  j4.position.z = ARM_L2
  const forearm = new THREE.Mesh(new THREE.CylinderGeometry(radius, radius, 0.12, 12), white)
  forearm.rotation.x = Math.PI / 2
  forearm.position.z = 0.06
  j4.add(forearm)
  j3.add(j4)

  jointsOut.push(j1, j2, j3, j4)
  return base
}
