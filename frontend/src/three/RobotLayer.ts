import * as THREE from 'three'
import { CSS2DObject } from 'three/examples/jsm/renderers/CSS2DRenderer.js'
import { angleDelta } from '@/utils/coords'
import { registerRobotLayer } from './robotLayerRef'

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

type TfFrameData = import('@/types/api').TfFrame

/** 关节快照等值（逐值比较，早退）：10Hz 数据链里跳过未变化帧的场景更新 */
function jointsEqual(a: JointSample | null, b: JointSample | null): boolean {
  if (a === b) return true
  if (!a || !b || a.name.length !== b.name.length) return false
  for (let i = 0; i < a.name.length; i++) {
    if (a.name[i] !== b.name[i] || a.position[i] !== b.position[i]) return false
  }
  return true
}

/** TF 帧列表等值（长度 + 逐帧数值比较，早退） */
function tfFramesEqual(a: TfFrameData[] | null, b: TfFrameData[]): boolean {
  if (a === b) return true
  if (!a || a.length !== b.length) return false
  for (let i = 0; i < b.length; i++) {
    const x = a[i]
    const y = b[i]
    if (x.child !== y.child || x.parent !== y.parent
      || x.x !== y.x || x.y !== y.y || x.z !== y.z
      || x.qx !== y.qx || x.qy !== y.qy || x.qz !== y.qz || x.qw !== y.qw) {
      return false
    }
  }
  return true
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
  private urdfLoading = false
  private urdfRetryAt = 0
  private disposed = false
  private target: { x: number; y: number; yaw: number } | null = null
  private shown: { x: number; y: number; yaw: number } | null = null
  private segmentStart: { x: number; y: number; yaw: number } | null = null
  private segmentStartedAtMs = 0
  private segmentDurationMs = 200
  private lastTargetAtMs = 0
  private joints: JointSample | null = null
  private lastTfFrames: TfFrameData[] | null = null
  private wheelRoll = 0
  private lastRollAt: { x: number; y: number } | null = null
  private trailLine: THREE.Line
  private trailAttr: THREE.BufferAttribute

  constructor() {
    registerRobotLayer(this)
    // 轮心高度为 WHEEL_R，轮胎最低点落在地图 z=0。
    this.group.position.z = 0

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
    // 机器人可拾取（右键菜单等：以机器人当前位置创建点位）
    this.root.userData.pick = { type: 'robot' }
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
    if (this.disposed || this.urdfRobot || this.urdfLoading || performance.now() < this.urdfRetryAt) return
    this.urdfLoading = true
    this.urdfRetryAt = performance.now() + 5000
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
      // 等异步 mesh 就绪后按模型最低点落地，兼容不同 URDF 原点。
      const groundRobot = () => {
        if (this.disposed || !this.urdfRobot) return
        const model = this.urdfRobot as THREE.Object3D
        model.position.z = 0
        model.updateMatrixWorld(true)
        // 优先按轮组接地，防止机械臂低位姿影响底盘高度。
        const bounds = new THREE.Box3()
        model.traverse((node) => {
          if (/wheel/i.test(node.name)) bounds.union(new THREE.Box3().setFromObject(node))
        })
        if (bounds.isEmpty()) bounds.setFromObject(model)
        if (!bounds.isEmpty() && Number.isFinite(bounds.min.z)) model.position.z = -bounds.min.z
      }
      manager.onLoad = groundRobot
      const robot = loader.parse(xml)
      if (!robot || this.disposed) return
      // URDF 本身就是 z-up、x-forward（ROS 语义），与本场景坐标约定一致，直接挂载
      this.urdfRobot = robot
      this.paramGroup.visible = false
      this.chevron.visible = false
      this.armSkeletonGroup.visible = false
      this.bodyGroup.add(robot)
      this.applyJoints()
      groundRobot()
    } catch {
      // URDF 不可用时保持参数化模型，后续遥测到达后重试。
    } finally {
      this.urdfLoading = false
    }
  }

  /** 关节状态到达（telemetry 快照 10Hz）：按名字规则分发到舵轮/机械臂或 URDF 关节 */
  setJointStates(joints: JointSample | null) {
    if (jointsEqual(this.joints, joints)) return
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
    // 10Hz 快照里 TF 大多数时刻不变（静态树）：等值短路，跳过整棵矩阵树重算与对象分配
    if (tfFramesEqual(this.lastTfFrames, frames)) return
    this.lastTfFrames = frames
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
    if (!this.urdfRobot) this.updateArmSkeleton(frames, resolve)
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
    this.disposed = true
    registerRobotLayer(null)
    this.group.clear()
    this.trailGroup.clear()
    this.trailLine.geometry.dispose()
  }

  // ==================== 机械臂数值逆解（示教笛卡尔点动） ====================

  /** URDF 是否可用（笛卡尔点动依赖它做正运动学） */
  get hasUrdf(): boolean {
    return this.urdfRobot != null
  }

    /** 指定侧臂的 URDF 关节名（排序后，与 /joint_states 分组顺序一致）；单臂 profile 回退 ur5e_* 命名 */
    private urdfArmJoints(side: 'left' | 'right'): string[] {
      if (!this.urdfRobot) return []
      const all = Object.keys(this.urdfRobot.joints ?? {})
      // 只取可动关节：URDF 里 ur5e_*_joint 会命中 6 个 revolute + 3 个 fixed
      // （base/link 挂接），fixed 混进逆解会让关节数变 9、arm_move 直接拒绝
      const movable = all.filter((n) => {
        const joint = this.urdfRobot.joints[n]
        const type = joint ? String(joint.jointType ?? '') : ''
        return type !== 'fixed'
      })
      const dual = movable
        .filter((n) => {
          const l = n.toLowerCase()
          return l.includes('arm') && l.includes(side)
        })
        .sort()
      if (dual.length > 0) {
        return dual
      }
      // 单臂 profile：ur5e_shoulder_pan_joint 等原始命名（无 arm/left 标记），
      // 左右控制同一物理臂——空列表会让数值逆解直接失败（此前"逆解未收敛"的真实原因）
      return movable
        .filter((n) => n.startsWith('ur5e_') && n.endsWith('_joint'))
        .sort()
    }

  /**
   * 笛卡尔点动数值逆解（机器人基座系）：dx/dy/dz 单位米，返回该臂排序后的新关节角（弧度）。
   * 以 URDF 场景树为正运动学，阻尼最小二乘迭代（每步毫米级增量，数次收敛）；
   * baseJoints 传"上一条已下发目标"时从它出发（开环指令链，消除遥测回读延迟造成的步间跳变），
   * 缺省用实时 /joint_states。无 URDF / 无关节数据 / 不收敛时返回 null。
   */
  armIkSolve(side: 'left' | 'right', dx: number, dy: number, dz: number,
             baseJoints?: number[] | null): number[] | null {
    const robot: any = this.urdfRobot
    if (!robot || !this.joints) return null
    const names = this.urdfArmJoints(side)
    if (names.length === 0) return null

    const byName = new Map(this.joints.name.map((n, i) => [n, this.joints!.position[i] ?? 0]))
    const q = new Map<string, number>()
    const limits = new Map<string, { lower: number; upper: number }>()
    for (let k = 0; k < names.length; k++) {
      const n = names[k]
      const base = baseJoints && baseJoints.length === names.length ? baseJoints[k] : byName.get(n) ?? 0
      q.set(n, base)
      const limit = robot.joints[n]?.limit
      limits.set(n, limit && Number.isFinite(limit.lower) && Number.isFinite(limit.upper)
        ? { lower: limit.lower, upper: limit.upper }
        : { lower: -Infinity, upper: Infinity })
    }
    const last = robot.joints[names[names.length - 1]]

    const rootInv = new THREE.Matrix4()
    const tcp = new THREE.Vector3()
    const pj = new THREE.Vector3()
    const axisWorld = new THREE.Vector3()

    const applyQ = () => {
      for (const n of names) robot.joints[n].setJointValue(q.get(n)!)
      robot.updateMatrixWorld(true)
    }

    // Solve on temporary joint transforms; only telemetry may move the displayed robot.
    const displayedAngles = names.map((name) => robot.joints[name].angle)
    try {
      applyQ()
      rootInv.copy(robot.matrixWorld).invert()
      last.getWorldPosition(tcp).applyMatrix4(rootInv)
      const target = tcp.clone().add(new THREE.Vector3(dx, dy, dz))

        const n = names.length
        const J: number[][] = [new Array<number>(n).fill(0), new Array<number>(n).fill(0), new Array<number>(n).fill(0)]
        // 自适应阻尼（Levenberg-Marquardt）：常规位姿 λ 极小保证精度；
        // 腕部奇异（如 wrist_2≈0 时腕三轴退化）附近条件数爆炸，小 λ 的 det→0，
        // 此时放大 λ 以最小范数解继续收敛而不是直接放弃（此前"逆解未收敛"的主因之一）
        let lambda = 1e-6
        const MAX_DQ = 0.05
        const TOL = 5e-4

        for (let iter = 0; iter < 50; iter++) {
          applyQ()
          last.getWorldPosition(tcp).applyMatrix4(rootInv)
          const ex = target.x - tcp.x
          const ey = target.y - tcp.y
          const ez = target.z - tcp.z
          if (Math.hypot(ex, ey, ez) < TOL) return names.map((name) => q.get(name)!)

          for (let k = 0; k < n; k++) {
            const joint = robot.joints[names[k]]
            joint.getWorldPosition(pj)
            pj.applyMatrix4(rootInv)
            joint.getWorldQuaternion(TMP_QUAT)
            axisWorld.set(0, 0, 1)
            if (joint.axis) axisWorld.copy(joint.axis)
            axisWorld.applyQuaternion(TMP_QUAT).transformDirection(rootInv)
            axisWorld.normalize()
            // 旋副：列 = axis × (tcp − joint)；UR5e 全旋副，柱副按轴向量处理即可
            const rx = tcp.x - pj.x
            const ry = tcp.y - pj.y
            const rz = tcp.z - pj.z
            J[0][k] = axisWorld.y * rz - axisWorld.z * ry
            J[1][k] = axisWorld.z * rx - axisWorld.x * rz
            J[2][k] = axisWorld.x * ry - axisWorld.y * rx
          }

          // dq = Jᵀ (J Jᵀ + λI)⁻¹ err（3×3 克拉默求解）；det 过小时放大 λ 重试
          let det = 0
          let A: number[][] = []
          let solved = false
          for (let attempt = 0; attempt < 5; attempt++) {
            A = [
              [0, 0, 0],
              [0, 0, 0],
              [0, 0, 0],
            ]
            for (let r = 0; r < 3; r++) {
              for (let c = 0; c < 3; c++) {
                let s = 0
                for (let k = 0; k < n; k++) s += J[r][k] * J[c][k]
                A[r][c] = s + (r === c ? lambda : 0)
              }
            }
            det = A[0][0] * (A[1][1] * A[2][2] - A[1][2] * A[2][1])
              - A[0][1] * (A[1][0] * A[2][2] - A[1][2] * A[2][0])
              + A[0][2] * (A[1][0] * A[2][1] - A[1][1] * A[2][0])
            if (Number.isFinite(det) && Math.abs(det) > 1e-10) {
              solved = true
              break
            }
            lambda *= 100
          }
          if (!solved) {
            console.info('[armIk] 逆解发散：雅可比奇异且阻尼 escalated 无效', { lambda })
            return null
          }
          const b = [ex, ey, ez]
          const y = [0, 0, 0]
          for (let r = 0; r < 3; r++) {
            const M = A.map((row, i) => row.map((v, j) => (j === r ? b[i] : v)))
            y[r] = (M[0][0] * (M[1][1] * M[2][2] - M[1][2] * M[2][1])
              - M[0][1] * (M[1][0] * M[2][2] - M[1][2] * M[2][0])
              + M[0][2] * (M[1][0] * M[2][1] - M[1][1] * M[2][0])) / det
          }

        for (let k = 0; k < n; k++) {
          let dq = J[0][k] * y[0] + J[1][k] * y[1] + J[2][k] * y[2]
          dq = Math.min(MAX_DQ, Math.max(-MAX_DQ, dq))
          const limit = limits.get(names[k])!
          const next = Math.min(limit.upper, Math.max(limit.lower, q.get(names[k])! + dq))
          q.set(names[k], next)
        }
      }
      return null
    } finally {
      names.forEach((name, i) => robot.joints[name].setJointValue(displayedAngles[i]))
      robot.updateMatrixWorld(true)
    }
  }

}

const TMP_QUAT = new THREE.Quaternion()

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
