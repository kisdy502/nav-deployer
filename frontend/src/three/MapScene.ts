import * as THREE from 'three'
import { CSS2DObject, CSS2DRenderer } from 'three/examples/jsm/renderers/CSS2DRenderer.js'
import { Easing, Group, Tween } from '@tweenjs/tween.js'
import { makeGridTexture } from './mapTexture'
import type { MapGridVO } from '@/types/api'

export interface PickResult {
  type: string
  id?: number
  edgeIdx?: number
  cpIdx?: number
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
  /** 地图纹理所在组（含 origin 偏移与 origin_yaw 旋转） */
  readonly mapGroup = new THREE.Group()
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
  private updateCamera() {
    const w = this.container.clientWidth || 1
    const h = this.container.clientHeight || 1
    const aspect = w / h
    this.camera.left = (-aspect * this.viewHeight) / 2
    this.camera.right = (aspect * this.viewHeight) / 2
    this.camera.top = this.viewHeight / 2
    this.camera.bottom = (-this.viewHeight) / 2
    this.camera.updateProjectionMatrix()
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

  /** 视图整体旋转 180°（对齐 RViz 等外部工具的观察方向）：仅翻转相机 up，场景数据不动，地图/机器人/点云/轨迹整体一致 */
  setViewRot180(on: boolean) {
    this.viewRot180 = on
    this.camera.up.set(0, on ? -1 : 1, 0)
    this.camera.lookAt(this.camera.position.x, this.camera.position.y, 0)
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
    this.raycaster.setFromCamera(ndc, this.camera)
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
    this.raycaster.setFromCamera(ndc, this.camera)
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
    const w = Math.max(bounds.maxX - bounds.minX, 0.5)
    const h = Math.max(bounds.maxY - bounds.minY, 0.5)
    const aspect = (this.container.clientWidth || 1) / (this.container.clientHeight || 1)
    this.viewHeight = Math.max(h, w / aspect) * (1 + pad * 2)
    this.camera.position.x = (bounds.minX + bounds.maxX) / 2
    this.camera.position.y = (bounds.minY + bounds.maxY) / 2
    this.updateCamera()
  }

  flyTo(x: number, y: number, viewHeight?: number, duration = 600) {
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
    this.mapGroup.clear()
    const w = grid.width * grid.resolution
    const h = grid.height * grid.resolution
    const tex = makeGridTexture(grid)
    const mesh = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ map: tex }))
    mesh.position.set(w / 2, h / 2, 0)
    this.mapGroup.add(mesh)
    const border = new THREE.LineLoop(
      new THREE.BufferGeometry().setFromPoints([
        new THREE.Vector3(0, 0, 0.02),
        new THREE.Vector3(w, 0, 0.02),
        new THREE.Vector3(w, h, 0.02),
        new THREE.Vector3(0, h, 0.02),
      ]),
      new THREE.LineBasicMaterial({ color: 0xb9bdc7 }),
    )
    this.mapGroup.add(border)
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
    const canPan = p.button !== 0 || (!p.downHit && !p.panning)
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
    ev.preventDefault()
    const factor = ev.deltaY < 0 ? 1.15 : 1 / 1.15
    this.zoomBy(factor, ev.clientX, ev.clientY)
  }

  private onContextMenu = (ev: MouseEvent) => {
    if (this.disposed) return
    ev.preventDefault()
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
    this.renderer.render(this.scene, this.camera)
    this.labelRenderer.render(this.scene, this.camera)
  }

  dispose() {
    this.disposed = true
    cancelAnimationFrame(this.rafId)
    this.ro.disconnect()
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
