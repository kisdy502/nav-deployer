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
  private armSkeletonGroup = new THREE.Group()
  private skeletonKey = ''
  private skeletonNodes: { name: string; mesh: THREE.Mesh }[] = []
  private skeletonEdges: { a: string; b: string; line: THREE.Line }[] = []
  private chassisL = BODY_L
  private chassisW = BODY_W
  private chassisParts: THREE.Group | null = null
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

    // 底盘几何由 rebuildChassis 生成（初始按常量，TF 轮位到达后按包络自适应）
    this.rebuildChassis(BODY_L, BODY_W)
    this.bodyGroup.add(this.paramGroup, this.chevron, this.armSkeletonGroup)

    // 三舵轮轮组（front / left / right）：黑色舵轮（轴沿 y，滚动 rotation.y，转向 steer 绕 z），
    // 位置初始按常量，TF 雷达包络到达后按真实车长重排
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
      steer.add(wheel)
      this.steerPivots.set(site, steer)
      this.wheelMeshes.set(site, wheel)
      this.paramGroup.add(steer)
    }

    // 机械臂参数化示意（TF 骨架不可用时的兜底）
    this.armBase = buildParametricArm(this.armJoints)
    this.armBase.position.set(-BODY_L * 0.28, 0, WHEEL_R + BODY_H)
    this.armBase.visible = false
    this.paramGroup.add(this.armBase)

    const el = document.createElement('div')
    el.className = 'nd-label'
    const inner = document.createElement('span')
    inner.className = 'nd-label-inner nd-label-inner--robot'
    inner.textContent = 'AGV'
    el.appendChild(inner)
    const label = new CSS2DObject(el)
    // 标签挂在 root 上不随车体旋转，浮在车体上方（俯视与 3D 视角都清晰）
    label.position.set(0, 0.55, WHEEL_R + BODY_H + 0.25)

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
        // urdf-loader 内部会先剥掉 package:// 前缀再请求 manager（相对路径 "包名/meshes/..."），
        // 该形态同样重写到代理，否则相对路径会打到页面服务器拿到 SPA fallback 的 HTML
        if (base && /^[\w.-]+\/meshes\//.test(url)) {
          return `${base}/${url}`
        }
        return url
      })
      const loader = new URDFLoader(manager)
      // 官方包路径解析机制：package://<pkg>/... → <base>/<pkg>/...（上位机 mesh 代理）。
      // 不设置时 urdf-loader 会剥成相对路径打到页面服务器（SPA fallback 返回 HTML，mesh 全挂）
      ;(loader as any).packages = (pkg: string) => `${base}/${pkg}`
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

  /**
   * TF 树帧到达（10Hz）：真实构型驱动的三件事——
   * 1) 轮组按 steering frame 的 base 系坐标精确定位（front/left/right 按名匹配）；
   * 2) 底盘尺寸按轮位包络自适应（轮组永不外露）；
   * 3) 机械臂改为 TF 骨架（各关节位置 = ur5e 各 frame 的 base 系真实坐标，天然不会拖地/错位），
   *    有骨架时隐藏参数化示意臂。
   */
  setTfFrames(frames: import('@/types/api').TfFrame[] | null) {
    if (!frames || frames.length === 0) return
    const byChild = new Map(frames.map((f) => [f.child, f]))
    const baseName = findBaseFrame(frames)
    if (!baseName) return

    const resolved = new Map<string, THREE.Matrix4>()
    const resolve = (name: string, depth = 0): THREE.Matrix4 | null => {
      if (resolved.has(name)) return resolved.get(name)!
      if (depth > 16) return null
      const frame = byChild.get(name)
      if (!frame) return null
      const local = new THREE.Matrix4().compose(
        new THREE.Vector3(frame.x, frame.y, frame.z),
        new THREE.Quaternion(frame.qx, frame.qy, frame.qz, frame.qw),
        new THREE.Vector3(1, 1, 1),
      )
      if (frame.parent === baseName) {
        resolved.set(name, local)
        return local
      }
      const parentMatrix = resolve(frame.parent, depth + 1)
      if (!parentMatrix) return null
      const matrix = parentMatrix.clone().multiply(local)
      resolved.set(name, matrix)
      return matrix
    }

    // 1) 轮组真实定位 + 2) 底盘包络
    let minX = Infinity; let maxX = -Infinity; let minY = Infinity; let maxY = -Infinity
    let wheelSeen = 0
    for (const frame of frames) {
      if (!/steer/i.test(frame.child)) continue
      const site = wheelSite(frame.child.toLowerCase())
      const pivot = site ? this.steerPivots.get(site) : null
      const matrix = resolve(frame.child)
      if (!pivot || !matrix) continue
      const pos = new THREE.Vector3().setFromMatrixPosition(matrix)
      pivot.position.set(pos.x, pos.y, 0)
      minX = Math.min(minX, pos.x - WHEEL_R); maxX = Math.max(maxX, pos.x + WHEEL_R)
      minY = Math.min(minY, pos.y - WHEEL_R); maxY = Math.max(maxY, pos.y + WHEEL_R)
      wheelSeen++
    }
    if (wheelSeen >= 2) {
      const len = maxX - minX + 0.10
      const wid = maxY - minY + 0.08
      if (Math.abs(len - this.chassisL) > 0.02 || Math.abs(wid - this.chassisW) > 0.02) {
        this.rebuildChassis(len, wid)
      }
    } else {
      // 兜底：仿真侧未发布轮子 TF 时，用雷达安装位（车体两端）反推真实车长，
      // 底盘按雷达包络自适应，轮位按新车长重排（前中 + 后两侧）
      const lidarX: number[] = []
      for (const frame of frames) {
        if (!/lidar/i.test(frame.child)) continue
        const matrix = resolve(frame.child)
        if (matrix) lidarX.push(new THREE.Vector3().setFromMatrixPosition(matrix).x)
      }
      if (lidarX.length >= 2) {
        const span = Math.max(...lidarX) - Math.min(...lidarX)
        const len = span + 0.16
        const wid = Math.max(this.chassisW, len * 0.42)
        if (Math.abs(len - this.chassisL) > 0.02) {
          this.rebuildChassis(len, wid)
          this.repositionWheels(len, wid)
        }
      }
    }

    // 3) 臂骨架
    this.updateArmSkeleton(frames, resolve)
  }

  /** 无轮 TF 时的轮位重排：按底盘尺寸比例摆三舵轮（前中 + 后两侧）。 */
  private repositionWheels(len: number, wid: number) {
    this.steerPivots.get('front')?.position.set(len / 2 - 0.09, 0, 0)
    this.steerPivots.get('left')?.position.set(-len / 2 + 0.09, wid / 2 - 0.06, 0)
    this.steerPivots.get('right')?.position.set(-len / 2 + 0.09, -(wid / 2 - 0.06), 0)
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
    if (this.armBase && this.skeletonKey === '') {
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

  /** 按轮位包络重建底盘几何（浅灰车体 + 蓝描边 + 前部蓝色饰条 + 朝向符号 + 中心点）。 */
  private rebuildChassis(len: number, wid: number) {
    this.chassisL = len
    this.chassisW = wid
    if (this.chassisParts) {
      this.chassisParts.traverse((obj) => {
        if (obj instanceof THREE.Mesh) obj.geometry.dispose()
      })
      this.paramGroup.remove(this.chassisParts)
    }
    const chassisZ = WHEEL_R
    const parts = new THREE.Group()
    const body = new THREE.Mesh(
      new THREE.BoxGeometry(len, wid, BODY_H),
      new THREE.MeshLambertMaterial({ color: 0xd7dde6 }),
    )
    body.position.z = chassisZ + BODY_H / 2
    const edges = new THREE.LineSegments(
      new THREE.EdgesGeometry(body.geometry),
      new THREE.LineBasicMaterial({ color: 0x1d6fd1 }),
    )
    edges.position.z = chassisZ + BODY_H / 2
    const centerDot = new THREE.Mesh(
      new THREE.CircleGeometry(0.018, 12),
      new THREE.MeshBasicMaterial({ color: 0xffffff }),
    )
    centerDot.position.z = chassisZ + BODY_H + 0.005
    parts.add(body, edges, centerDot)
    this.chassisParts = parts
    this.paramGroup.add(parts)

    // 朝向符号跟随车长（贴顶面前部）
    this.chevron.clear()
    this.chevron.add(
      chevronMesh(len * 0.14, len * 0.11, len * 0.08, len * 0.028, 0x409eff),
      chevronMesh(len * 0.25, len * 0.11, len * 0.08, len * 0.028, 0x409eff),
    )
    this.chevron.position.set(len * 0.1, 0, chassisZ + BODY_H + 0.006)
  }

  /**
   * 机械臂 TF 骨架：臂相关 frame（名含 ur5e/arm/tool）按 parent-child 连成
   * 关节球+连线的骨架，位置全部取自 TF 真实坐标（相对 base 系累积），
   * 结构不变时仅更新位置（10Hz 高频复用，无 GC 压力）。
   */
  private updateArmSkeleton(
    frames: import('@/types/api').TfFrame[],
    resolve: (name: string) => THREE.Matrix4 | null,
  ) {
    const isArmFrame = (name: string) => /ur5e|arm|tool/i.test(name)
    const armFrames = frames.filter((f) => isArmFrame(f.child))
    if (armFrames.length === 0) return
    const key = armFrames.map((f) => f.child).sort().join('|')
    if (key !== this.skeletonKey) {
      this.skeletonKey = key
      this.armSkeletonGroup.clear()
      this.skeletonNodes = []
      this.skeletonEdges = []
      const nodeGeo = new THREE.SphereGeometry(0.028, 12, 10)
      const nodeMat = new THREE.MeshLambertMaterial({ color: 0x2b3440 })
      const edgeMat = new THREE.LineBasicMaterial({ color: 0x2b3440 })
      for (const frame of armFrames) {
        const node = new THREE.Mesh(nodeGeo, nodeMat)
        this.skeletonNodes.push({ name: frame.child, mesh: node })
        this.armSkeletonGroup.add(node)
        if (isArmFrame(frame.parent)) {
          const geometry = new THREE.BufferGeometry()
          geometry.setAttribute('position', new THREE.BufferAttribute(new Float32Array(6), 3))
          const line = new THREE.Line(geometry, edgeMat)
          this.skeletonEdges.push({ a: frame.parent, b: frame.child, line })
          this.armSkeletonGroup.add(line)
        }
      }
      this.armSkeletonGroup.visible = true
      if (this.armBase) this.armBase.visible = false
    }
    const tmp = new THREE.Vector3()
    for (const node of this.skeletonNodes) {
      const matrix = resolve(node.name)
      if (matrix) {
        node.mesh.position.setFromMatrixPosition(matrix)
      }
    }
    for (const edge of this.skeletonEdges) {
      const ma = resolve(edge.a)
      const mb = resolve(edge.b)
      if (!ma || !mb) continue
      const attr = edge.line.geometry.getAttribute('position') as THREE.BufferAttribute
      attr.setXYZ(0, tmp.setFromMatrixPosition(ma).x, tmp.y, tmp.z)
      attr.setXYZ(1, tmp.setFromMatrixPosition(mb).x, tmp.y, tmp.z)
      attr.needsUpdate = true
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

/** 找 TF 树的 base 根 frame：优先 base_footprint，其次任意 base_link */
function findBaseFrame(frames: import('@/types/api').TfFrame[]): string | null {
  const footprint = frames.find((f) => /base_footprint/i.test(f.child))
  if (footprint) return footprint.child
  const baseLink = frames.find((f) => /base_link/i.test(f.child))
  return baseLink ? baseLink.child : null
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
