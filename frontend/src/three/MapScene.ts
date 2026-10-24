import * as THREE from 'three'
import { CSS2DObject, CSS2DRenderer } from 'three/examples/jsm/renderers/CSS2DRenderer.js'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { Easing, Group, Tween } from '@tweenjs/tween.js'
import { makeGridTexture, updateGridTexture } from './mapTexture'
import type { MapGridVO } from '@/types/api'

export interface PickResult {
  type: string
  id?: number
  edgeIdx?: number
  cpIdx?: number
  /** 多边形顶点索引（zoneVertex/shelfVertex 手柄） */
  vIdx?: number
  /** 端点切线手柄方向（endpointHandle：'src' 起点 / 'tgt' 终点） */
  end?: string
  object: THREE.Object3D
}

export interface MapSceneBounds {
  minX: number
  minY: number
  maxX: number
  maxY: number
}

export interface MapSceneCallbacks {
  onLeftDown?: (hit: PickResult | null, world: THREE.Vector3, ev: PointerEvent) => void
  onLeftMove?: (world: THREE.Vector3, ev: PointerEvent) => void
  onLeftUp?: (hit: PickResult | null, world: THREE.Vector3, ev: PointerEvent) => void
  onLeftClick?: (hit: PickResult | null, world: THREE.Vector3, ev: PointerEvent) => void
  onContext?: (hit: PickResult | null, world: THREE.Vector3, ev: MouseEvent) => void
  onFrame?: (dt: number) => void
}

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v))

/** GridHelper 默认在 XZ 平面（y 向上），旋转到本场景的 XY 平面（z 朝屏幕外） */
function makeWorldGrid(size: number, divisions: number, centerColor: number, color: number, opacity: number) {
  const g = new THREE.GridHelper(size, divisions, centerColor, color)
  g.rotation.x = Math.PI / 2
  g.frustumCulled = false
  const m = g.material as THREE.LineBasicMaterial
  m.transparent = true
  m.opacity = opacity
  m.depthWrite = false
  return g
}

/** 坐标轴箭头（沿 +X 方向，长度 len）：线段 + 三角箭头；Y 轴由调用方旋转 90° */
function makeAxisArrow(len: number, color: number) {
  const g = new THREE.Group()
  const line = new THREE.Line(
    new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(0, 0, 0),
      new THREE.Vector3(len, 0, 0),
    ]),
    new THREE.LineBasicMaterial({ color }),
  )
  line.frustumCulled = false
  const headLen = Math.max(len * 0.2, 0.15)
  const headW = headLen * 0.7
  const shape = new THREE.Shape()
  shape.moveTo(len, 0)
  shape.lineTo(len - headLen, headW / 2)
  shape.lineTo(len - headLen, -headW / 2)
  shape.closePath()
  const head = new THREE.Mesh(new THREE.ShapeGeometry(shape), new THREE.MeshBasicMaterial({ color }))
  head.frustumCulled = false
  g.add(line, head)
  return g
}

/** 坐标轴文字标签（CSS2D，屏幕像素大小恒定，位置锚定世界坐标） */
function makeAxisLabel(text: string, color: number, pos: THREE.Vector3) {
  const el = document.createElement('div')
  el.textContent = text
  el.style.cssText = `color:#${color.toString(16).padStart(6, '0')};font:600 14px/1 ui-monospace,monospace;user-select:none;white-space:nowrap;`
  const label = new CSS2DObject(el)
  label.position.copy(pos)
  return label
}

/**
 * 俯视正交相机三维场景：世界单位 = 米，x 向右、y 向上、z 朝屏幕外。
 * 内置：栅格地图贴合、滚轮缩放（锚点）、拖拽平移（空白左键/中键/右键）、
 * 元素拾取（userData.pick）、相机飞行、测量线、CSS2D 标签层。
 */
export class MapScene {
  readonly scene = new THREE.Scene()
  readonly camera: THREE.OrthographicCamera
  readonly renderer: THREE.WebGLRenderer
  readonly labelRenderer: CSS2DRenderer
  /** 需要被拾取的图层挂到这里 */
  readonly pickRoot = new THREE.Group()
  /** 左键空白拖拽平移开关：绘制类工具（框选/画矩形）接管左键时置 false，中键/右键平移不受影响 */
  leftDragPanEnabled = true
  /** 地图纹理所在组（含 origin 偏移与 origin_yaw 旋转） */
  readonly mapGroup = new THREE.Group()
  private mapMesh: THREE.Mesh | null = null
  private mapBorder: THREE.LineLoop | null = null
  private mapTexture: THREE.DataTexture | null = null
  private mapPixelWidth = 0
  private mapPixelHeight = 0
  private mapResolution = 0
  /** RViz 风格世界系参考网格（1 格 = 1 米，正交相机下随缩放等比，始终 1 米） */
  readonly gridGroup = new THREE.Group()
  private gridMinor: THREE.GridHelper | null = null
  private gridMajor: THREE.GridHelper | null = null
  /** ROS 坐标系方向指示器（map 系原点：+X 红、+Y 绿） */
  readonly axesGroup = new THREE.Group()

  mapBounds: MapSceneBounds | null = null

  private container: HTMLElement
  private opts: MapSceneCallbacks
  private raycaster = new THREE.Raycaster()
  private groundPlane = new THREE.Plane(new THREE.Vector3(0, 0, 1), 0)
  private viewHeight = 12
  private tweens = new Group()
  private rafId = 0
  private lastT = performance.now()
  private ro: ResizeObserver
  private pointer: { button: number; startX: number; startY: number; lastX: number; lastY: number; moved: boolean; downHit: PickResult | null; panning: boolean } | null = null
  private disposed = false
  private viewRot180 = false
  private tmpRight = new THREE.Vector3()
  private tmpUp = new THREE.Vector3()

  // RViz 式 3D 环绕视角（透视相机 + OrbitControls），与俯视正交模式互斥切换
  private perspCamera: THREE.PerspectiveCamera | null = null
  private controls: OrbitControls | null = null
  private orbitMode = false
  private rightStart: { x: number; y: number } | null = null

  // 镜头跟随机器人：RViz 式硬锁（每帧对齐，零延迟零追赶），手动平移/定位后自动退出
  private followEnabled = false

  // 测量
  private measureGroup = new THREE.Group()
  private measureLine: THREE.Line
  private measureA: THREE.Mesh
  private measureB: THREE.Mesh
  private measureLabel: CSS2DObject
  private measureLabelEl: HTMLDivElement

  constructor(container: HTMLElement, opts: MapSceneCallbacks = {}) {
    this.container = container
    this.opts = opts
    const w = container.clientWidth || 800
    const h = container.clientHeight || 600

    this.scene.background = new THREE.Color(0xeef1f6)
    this.camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 4000)
    this.camera.position.set(0, 0, 100)

    // 灯光：仅影响 Lambert/Standard 材质（3D 机器人车体等）；平面贴图用 Basic 材质不受影响
    this.scene.add(new THREE.HemisphereLight(0xffffff, 0x9aa4b1, 1.0))
    const dir = new THREE.DirectionalLight(0xffffff, 0.9)
    dir.position.set(40, -50, 90)
    this.scene.add(dir)

    this.renderer = new THREE.WebGLRenderer({ antialias: true })
    this.renderer.setPixelRatio(window.devicePixelRatio)
    this.renderer.setSize(w, h)
    this.renderer.domElement.style.display = 'block'
    container.appendChild(this.renderer.domElement)

    this.labelRenderer = new CSS2DRenderer()
    this.labelRenderer.setSize(w, h)
    const ld = this.labelRenderer.domElement
    ld.style.position = 'absolute'
    ld.style.top = '0'
    ld.style.left = '0'
    ld.style.pointerEvents = 'none'
    container.appendChild(ld)
    container.style.position = 'relative'
    container.style.overflow = 'hidden'

    this.scene.add(this.mapGroup)
    this.scene.add(this.pickRoot)

    // 世界系网格：细网格 1m / 粗网格 10m（RViz 风格），位于地图之上、实体之下
    this.gridMinor = makeWorldGrid(1000, 1000, 0xa8afbd, 0xdde1e8, 0.5)
    this.gridMajor = makeWorldGrid(1000, 100, 0xa8afbd, 0xd2d7e0, 0.55)
    this.gridGroup.add(this.gridMinor, this.gridMajor)
    this.gridGroup.position.z = 0.06
    this.scene.add(this.gridGroup)

    // ROS 坐标系指示器：横线+竖线+两个箭头（X 红 / Y 绿），世界原点处
    const AXES_LEN = 1.5
    const axesX = makeAxisArrow(AXES_LEN, 0xd64545)
    const axesY = makeAxisArrow(AXES_LEN, 0x3fa34d)
    axesY.rotation.z = Math.PI / 2
    this.axesGroup.add(axesX, axesY)
    this.axesGroup.add(
      makeAxisLabel('x', 0xd64545, new THREE.Vector3(AXES_LEN + 0.2, 0, 0)),
      makeAxisLabel('y', 0x3fa34d, new THREE.Vector3(0, AXES_LEN + 0.2, 0)),
      makeAxisLabel('0', 0x8a919e, new THREE.Vector3(-0.16, -0.16, 0)),
    )
    this.axesGroup.position.z = 0.08
    this.scene.add(this.axesGroup)

    // 测量组件
    this.measureLine = new THREE.Line(
      new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()]),
      new THREE.LineBasicMaterial({ color: 0xf56c6c }),
    )
    const dotGeo = new THREE.SphereGeometry(0.05, 10, 10)
    const dotMat = new THREE.MeshBasicMaterial({ color: 0xf56c6c })
    this.measureA = new THREE.Mesh(dotGeo, dotMat)
    this.measureB = new THREE.Mesh(dotGeo, dotMat.clone())
    this.measureLabelEl = document.createElement('div')
    this.measureLabelEl.className = 'nd-label'
    const inner = document.createElement('span')
    inner.className = 'nd-label-inner nd-label-inner--measure'
    this.measureLabelEl.appendChild(inner)
    this.measureLabel = new CSS2DObject(this.measureLabelEl)
    this.measureGroup.add(this.measureLine, this.measureA, this.measureB, this.measureLabel)
    this.measureGroup.visible = false
    this.scene.add(this.measureGroup)

    this.updateCamera()

    const el = this.renderer.domElement
    el.addEventListener('pointerdown', this.onPointerDown)
    el.addEventListener('pointermove', this.onPointerMove)
    el.addEventListener('pointerup', this.onPointerUp)
    el.addEventListener('wheel', this.onWheel, { passive: false })
    el.addEventListener('contextmenu', this.onContextMenu)

    this.ro = new ResizeObserver(() => this.resize())
    this.ro.observe(container)
    this.loop()
  }

  // ---------- 相机 ----------

  /** 当前生效相机：俯视模式为正交相机，3D 环绕模式为透视相机 */
  get activeCamera(): THREE.Camera {
    return this.orbitMode && this.perspCamera ? this.perspCamera : this.camera
  }

  /**
   * RViz 式 3D 环绕视角开关：开启后左键拖动旋转、右键平移、滚轮缩放（OrbitControls），
   * 点选/拖拽编辑仍按地面平面射线工作；关闭恢复俯视正交视图（状态保留）。
   */
  setOrbitView(on: boolean) {
    if (on === this.orbitMode) return
    this.orbitMode = on
    if (on) {
      if (!this.perspCamera) {
        this.perspCamera = new THREE.PerspectiveCamera(50, 1, 0.1, 4000)
        this.perspCamera.up.set(0, 0, 1)
        this.controls = new OrbitControls(this.perspCamera, this.renderer.domElement)
        this.controls.enableDamping = true
        this.controls.dampingFactor = 0.12
        this.controls.screenSpacePanning = false
        this.controls.maxPolarAngle = Math.PI / 2 - 0.04
        this.controls.minDistance = 1.5
        this.controls.maxDistance = 700
      }
      this.controls!.enabled = true
      const aspect = (this.container.clientWidth || 1) / (this.container.clientHeight || 1)
      this.perspCamera.aspect = aspect
      this.perspCamera.updateProjectionMatrix()
      // 以当前俯视视图中心为环绕中心，仰角 55°，方位角对齐当前 180° 视图偏好
      const center = new THREE.Vector3(this.camera.position.x, this.camera.position.y, 0)
      const az = this.viewRot180 ? Math.PI : 0
      const el = THREE.MathUtils.degToRad(55)
      const dist = Math.max(this.viewHeight * 1.35, 3)
      this.perspCamera.position.set(
        center.x + dist * Math.cos(el) * Math.sin(az),
        center.y - dist * Math.cos(el) * Math.cos(az),
        dist * Math.sin(el),
      )
      this.perspCamera.lookAt(center)
      this.controls!.target.copy(center)
      this.controls!.update()
      // 斜视角下 1m 细网格易摩尔纹，只保留 10m 粗网格
      if (this.gridMinor) this.gridMinor.visible = false
    } else {
      if (this.controls) this.controls.enabled = false
      this.updateCamera()
    }
  }

  get isOrbitMode() {
    return this.orbitMode
  }

  get isFollowEnabled() {
    return this.followEnabled
  }

  setFollowEnabled(on: boolean) {
    this.followEnabled = on
  }

  /**
   * 镜头跟随机器人（渲染循环每帧调用）：RViz 式硬锁——相机位姿逐帧对齐机器人
   * （正交模式 xy 硬同步；3D 环绕模式平移 target 与相机保持视角），相机与机器人
   * 零偏差零延迟，平滑性由机器人位姿插值保证；手动平移/定位后自动退出。
   */
  updateFollow(robot: { x: number; y: number } | null, _dt: number) {
    if (!this.followEnabled || !robot) return
    if (this.orbitMode && this.controls && this.perspCamera) {
      const dx = robot.x - this.controls.target.x
      const dy = robot.y - this.controls.target.y
      this.controls.target.x += dx
      this.controls.target.y += dy
      this.perspCamera.position.x += dx
      this.perspCamera.position.y += dy
    } else {
      this.camera.position.x = robot.x
      this.camera.position.y = robot.y
    }
  }

  private updateCamera() {
    const w = this.container.clientWidth || 1
    const h = this.container.clientHeight || 1
    const aspect = w / h
    this.camera.left = (-aspect * this.viewHeight) / 2
    this.camera.right = (aspect * this.viewHeight) / 2
    this.camera.top = this.viewHeight / 2
    this.camera.bottom = (-this.viewHeight) / 2
    this.camera.updateProjectionMatrix()
    if (this.perspCamera) {
      this.perspCamera.aspect = aspect
      this.perspCamera.updateProjectionMatrix()
    }
    // 网格 LOD：1m 格不足 ~10px 时隐藏细网格防摩尔纹，10m 格不足 ~1.5px 时全部隐藏
    const pxPerMeter = h / this.viewHeight
    if (this.gridMinor) this.gridMinor.visible = pxPerMeter >= 10
    if (this.gridMajor) this.gridMajor.visible = pxPerMeter >= 1.5
  }

  resize() {
    const w = this.container.clientWidth
    const h = this.container.clientHeight
    if (!w || !h) return
    this.renderer.setSize(w, h)
    this.labelRenderer.setSize(w, h)
    this.updateCamera()
  }

  getZoomPct() {
    // viewHeight 相对默认 12m 的比例，供界面显示
    return Math.round((12 / this.viewHeight) * 100)
  }

  /** 视图顺时针旋转角度（0/90/180/270，四次还原） */
  private viewRotDeg = 0

  /** 视图整体顺时针旋转（步进 90°）：仅翻转相机 up，场景数据不动，地图/机器人/点云整体一致 */
  setViewRotation(degClockwise: number) {
    this.viewRotDeg = ((((Math.round(degClockwise / 90) * 90) % 360) + 360) % 360)
    this.viewRot180 = this.viewRotDeg === 180
    const rad = (this.viewRotDeg * Math.PI) / 180
    // up = (-sin θ, cos θ)：θ=90 时内容顺时针转 90°；θ=180 时与旧 setViewRot180 行为一致
    this.camera.up.set(-Math.sin(rad), Math.cos(rad), 0)
    this.camera.lookAt(this.camera.position.x, this.camera.position.y, 0)
  }

  getViewRotation() {
    return this.viewRotDeg
  }

  /** 视图整体旋转 180°（对齐 RViz 等外部工具的观察方向）：仅翻转相机 up，场景数据不动，地图/机器人/点云/轨迹整体一致 */
  setViewRot180(on: boolean) {
    this.setViewRotation(on ? 180 : 0)
  }

  getViewRot180() {
    return this.viewRot180
  }

  screenToWorld(clientX: number, clientY: number): THREE.Vector3 {
    const rect = this.renderer.domElement.getBoundingClientRect()
    const ndc = new THREE.Vector2(
      ((clientX - rect.left) / rect.width) * 2 - 1,
      -((clientY - rect.top) / rect.height) * 2 + 1,
    )
    this.raycaster.setFromCamera(ndc, this.activeCamera)
    const p = new THREE.Vector3()
    this.raycaster.ray.intersectPlane(this.groundPlane, p)
    return p
  }

  pick(clientX: number, clientY: number): PickResult | null {
    const rect = this.renderer.domElement.getBoundingClientRect()
    const ndc = new THREE.Vector2(
      ((clientX - rect.left) / rect.width) * 2 - 1,
      -((clientY - rect.top) / rect.height) * 2 + 1,
    )
    this.raycaster.setFromCamera(ndc, this.activeCamera)
    const hits = this.raycaster.intersectObjects(this.pickRoot.children, true)
    for (const h of hits) {
      let o: THREE.Object3D | null = h.object
      // 命中对象须整条祖先链可见（raycaster 不检查 visible）
      let hidden = false
      for (let p: THREE.Object3D | null = o; p && p !== this.pickRoot; p = p.parent) {
        if (!p.visible) {
          hidden = true
          break
        }
      }
      if (hidden) continue
      while (o) {
        const pick = o.userData.pick as PickResult | undefined
        if (pick) return { ...pick, object: o }
        o = o.parent
      }
    }
    return null
  }

  fitView(bounds: MapSceneBounds, pad = 0.12) {
    this.followEnabled = false
    const w = Math.max(bounds.maxX - bounds.minX, 0.5)
    const h = Math.max(bounds.maxY - bounds.minY, 0.5)
    const aspect = (this.container.clientWidth || 1) / (this.container.clientHeight || 1)
    this.viewHeight = Math.max(h, w / aspect) * (1 + pad * 2)
    this.camera.position.x = (bounds.minX + bounds.maxX) / 2
    this.camera.position.y = (bounds.minY + bounds.maxY) / 2
    this.updateCamera()
    // 3D 环绕模式下同步平移环绕中心，保持正交视图状态供切回
    if (this.orbitMode && this.controls) {
      this.controls.target.set(this.camera.position.x, this.camera.position.y, 0)
    }
  }

  flyTo(x: number, y: number, viewHeight?: number, duration = 600) {
    this.followEnabled = false
    if (this.orbitMode && this.controls) {
      const t = { x: this.controls.target.x, y: this.controls.target.y }
      new Tween(t, this.tweens)
        .to({ x, y }, duration)
        .easing(Easing.Cubic.Out)
        .onUpdate(() => {
          this.controls!.target.set(t.x, t.y, 0)
        })
        .start()
      return
    }
    const state = { x: this.camera.position.x, y: this.camera.position.y, vh: this.viewHeight }
    new Tween(state, this.tweens)
      .to({ x, y, vh: viewHeight ?? this.viewHeight }, duration)
      .easing(Easing.Cubic.Out)
      .onUpdate(() => {
        this.camera.position.x = state.x
        this.camera.position.y = state.y
        this.viewHeight = state.vh
        this.updateCamera()
      })
      .start()
  }

  zoomBy(factor: number, anchorClientX?: number, anchorClientY?: number) {
    const cx = anchorClientX ?? this.containerBoundingClientRect().left + this.container.clientWidth / 2
    const cy = anchorClientY ?? this.containerBoundingClientRect().top + this.container.clientHeight / 2
    const before = this.screenToWorld(cx, cy)
    this.viewHeight = clamp(this.viewHeight / factor, 0.15, 800)
    this.updateCamera()
    const after = this.screenToWorld(cx, cy)
    this.camera.position.x += before.x - after.x
    this.camera.position.y += before.y - after.y
    this.updateCamera()
  }

  private containerBoundingClientRect() {
    return this.renderer.domElement.getBoundingClientRect()
  }

  // ---------- 地图 ----------
  setMap(grid: MapGridVO) {
    const w = grid.width * grid.resolution
    const h = grid.height * grid.resolution
    const canReuse = this.mapMesh != null
      && this.mapTexture != null
      && this.mapPixelWidth === grid.width
      && this.mapPixelHeight === grid.height
      && this.mapResolution === grid.resolution

    if (canReuse) {
      updateGridTexture(this.mapTexture!, grid)
    } else {
      this.disposeMapResources()
      this.mapTexture = makeGridTexture(grid)
      this.mapMesh = new THREE.Mesh(
        new THREE.PlaneGeometry(w, h),
        new THREE.MeshBasicMaterial({ map: this.mapTexture }),
      )
      this.mapMesh.position.set(w / 2, h / 2, 0)
      this.mapBorder = new THREE.LineLoop(
        new THREE.BufferGeometry().setFromPoints([
          new THREE.Vector3(0, 0, 0.02),
          new THREE.Vector3(w, 0, 0.02),
          new THREE.Vector3(w, h, 0.02),
          new THREE.Vector3(0, h, 0.02),
        ]),
        new THREE.LineBasicMaterial({ color: 0xb9bdc7 }),
      )
      this.mapGroup.add(this.mapMesh, this.mapBorder)
      this.mapPixelWidth = grid.width
      this.mapPixelHeight = grid.height
      this.mapResolution = grid.resolution
    }
    this.mapGroup.position.set(grid.origin_x, grid.origin_y, 0)
    this.mapGroup.rotation.z = grid.origin_yaw || 0
    this.mapGroup.visible = true

    // 旋转后的世界包围盒
    this.mapGroup.updateMatrix()
    const corners = [
      new THREE.Vector3(0, 0, 0),
      new THREE.Vector3(w, 0, 0),
      new THREE.Vector3(w, h, 0),
      new THREE.Vector3(0, h, 0),
    ].map((c) => c.applyMatrix4(this.mapGroup.matrix))
    this.mapBounds = {
      minX: Math.min(...corners.map((c) => c.x)),
      minY: Math.min(...corners.map((c) => c.y)),
      maxX: Math.max(...corners.map((c) => c.x)),
      maxY: Math.max(...corners.map((c) => c.y)),
    }
  }

  /** Three.js 的 clear() 只解绑对象，不释放 geometry/material/texture，必须显式销毁。 */
  private disposeMapResources() {
    if (this.mapMesh) {
      this.mapMesh.geometry.dispose()
      ;(this.mapMesh.material as THREE.Material).dispose()
    }
    if (this.mapBorder) {
      this.mapBorder.geometry.dispose()
      ;(this.mapBorder.material as THREE.Material).dispose()
    }
    this.mapTexture?.dispose()
    this.mapGroup.clear()
    this.mapMesh = null
    this.mapBorder = null
    this.mapTexture = null
    this.mapPixelWidth = 0
    this.mapPixelHeight = 0
    this.mapResolution = 0
  }

  hideMap() {
    this.mapGroup.visible = false
  }

  // ---------- 测量 ----------
  showMeasure(a: { x: number; y: number } | null, b: { x: number; y: number } | null) {
    if (!a) {
      this.measureGroup.visible = false
      return
    }
    this.measureGroup.visible = true
    this.measureA.position.set(a.x, a.y, 0.15)
    const end = b ?? a
    this.measureB.position.set(end.x, end.y, 0.15)
    this.measureB.visible = b != null
    const pos = this.measureLine.geometry.attributes.position as THREE.BufferAttribute
    pos.setXYZ(0, a.x, a.y, 0.14)
    pos.setXYZ(1, end.x, end.y, 0.14)
    pos.needsUpdate = true
    this.measureLine.visible = b != null
    this.measureLabel.position.set((a.x + end.x) / 2, (a.y + end.y) / 2, 0.2)
    if (b) {
      const d = Math.hypot(b.x - a.x, b.y - a.y)
      ;(this.measureLabelEl.firstChild as HTMLElement).textContent = `${d.toFixed(2)} m`
    } else {
      ;(this.measureLabelEl.firstChild as HTMLElement).textContent = ''
    }
  }

  // ---------- 指针事件 ----------
  private onPointerDown = (ev: PointerEvent) => {
    if (this.disposed) return
    const hit = ev.button === 0 ? this.pick(ev.clientX, ev.clientY) : null
    this.pointer = {
      button: ev.button,
      startX: ev.clientX,
      startY: ev.clientY,
      lastX: ev.clientX,
      lastY: ev.clientY,
      moved: false,
      downHit: hit,
      panning: false,
    }
    this.rightStart = ev.button === 2 ? { x: ev.clientX, y: ev.clientY } : null
    ;(ev.target as HTMLElement).setPointerCapture(ev.pointerId)
    if (ev.button === 0) this.opts.onLeftDown?.(hit, this.screenToWorld(ev.clientX, ev.clientY), ev)
  }

  private onPointerMove = (ev: PointerEvent) => {
    if (this.disposed) return
    const world = this.screenToWorld(ev.clientX, ev.clientY)
    this.opts.onLeftMove?.(world, ev)
    const p = this.pointer
    if (!p) return
    const dx = ev.clientX - p.lastX
    const dy = ev.clientY - p.lastY
    p.lastX = ev.clientX
    p.lastY = ev.clientY
    if (Math.hypot(ev.clientX - p.startX, ev.clientY - p.startY) > 4) p.moved = true
    // 3D 环绕模式：左键旋转 / 右键平移均由 OrbitControls 处理，这里只做拾取与编辑回调
    const canPan = !this.orbitMode && this.leftDragPanEnabled && (p.button !== 0 || (!p.downHit && !p.panning))
    // 手动平移（俯视拖拽 / 3D 右键拖拽）视为接管镜头，退出跟随
    if (p.moved && ((canPan) || (this.orbitMode && p.button === 2))) {
      this.followEnabled = false
    }
    if (p.moved && canPan) {
      p.panning = true
      const wpp = this.viewHeight / (this.container.clientHeight || 1)
      // 沿相机屏幕轴平移：视图旋转 180°（up 翻转）后拖拽依然“内容跟手”
      this.tmpRight.set(1, 0, 0).applyQuaternion(this.camera.quaternion)
      this.tmpUp.set(0, 1, 0).applyQuaternion(this.camera.quaternion)
      this.camera.position.addScaledVector(this.tmpRight, -dx * wpp)
      this.camera.position.addScaledVector(this.tmpUp, dy * wpp)
      this.updateCamera()
    }
  }

  private onPointerUp = (ev: PointerEvent) => {
    if (this.disposed) return
    const p = this.pointer
    this.pointer = null
    if (!p) return
    const world = this.screenToWorld(ev.clientX, ev.clientY)
    const hit = this.pick(ev.clientX, ev.clientY)
    if (ev.button === 0) {
      this.opts.onLeftUp?.(p.downHit ?? hit, world, ev)
      if (!p.moved) this.opts.onLeftClick?.(p.downHit ?? hit, world, ev)
    }
  }

  private onWheel = (ev: WheelEvent) => {
    if (this.disposed) return
    // 3D 环绕模式：缩放由 OrbitControls 处理
    if (this.orbitMode) return
    ev.preventDefault()
    const factor = ev.deltaY < 0 ? 1.15 : 1 / 1.15
    this.zoomBy(factor, ev.clientX, ev.clientY)
  }

  private onContextMenu = (ev: MouseEvent) => {
    if (this.disposed) return
    ev.preventDefault()
    // 3D 环绕模式：右键拖动平移后不弹编辑菜单（OrbitControls 也消费了右键）
    if (this.orbitMode && this.rightStart
      && Math.hypot(ev.clientX - this.rightStart.x, ev.clientY - this.rightStart.y) > 4) {
      return
    }
    const hit = this.pick(ev.clientX, ev.clientY)
    this.opts.onContext?.(hit, this.screenToWorld(ev.clientX, ev.clientY), ev)
  }

  // ---------- 渲染循环 ----------
  private loop = () => {
    if (this.disposed) return
    this.rafId = requestAnimationFrame(this.loop)
    const now = performance.now()
    const dt = Math.min((now - this.lastT) / 1000, 0.1)
    this.lastT = now
    this.tweens.update()
    this.opts.onFrame?.(dt)
    if (this.orbitMode && this.controls) this.controls.update()
    const cam = this.activeCamera
    this.renderer.render(this.scene, cam)
    this.labelRenderer.render(this.scene, cam)
  }

  dispose() {
    this.disposed = true
    cancelAnimationFrame(this.rafId)
    this.ro.disconnect()
    this.disposeMapResources()
    this.controls?.dispose()
    this.controls = null
    const el = this.renderer.domElement
    el.removeEventListener('pointerdown', this.onPointerDown)
    el.removeEventListener('pointermove', this.onPointerMove)
    el.removeEventListener('pointerup', this.onPointerUp)
    el.removeEventListener('wheel', this.onWheel)
    el.removeEventListener('contextmenu', this.onContextMenu)
    this.scene.traverse((o) => {
      const mesh = o as THREE.Mesh
      if (mesh.geometry) mesh.geometry.dispose()
      const m = (mesh as unknown as { material?: THREE.Material | THREE.Material[] }).material
      if (Array.isArray(m)) m.forEach((x) => x.dispose())
      else m?.dispose()
    })
    this.renderer.dispose()
    this.renderer.domElement.remove()
    this.labelRenderer.domElement.remove()
  }
}
