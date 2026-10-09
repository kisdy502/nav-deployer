<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { PLYLoader } from 'three/examples/jsm/loaders/PLYLoader.js'
import { useRobotStore } from '@/stores/robot'

const props = defineProps<{ mapName: string }>()
const viewport = ref<HTMLDivElement>()
const message = ref('正在查询三维地图…')
const error = ref(false)
const robot = useRobotStore()
const abort = new AbortController()
let renderer: THREE.WebGLRenderer | null = null
let controls: OrbitControls | null = null
let observer: ResizeObserver | null = null
let frame = 0
const scene = new THREE.Scene()
scene.background = new THREE.Color(0x111827)
const camera = new THREE.PerspectiveCamera(55, 1, 0.1, 5000)
camera.up.set(0, 0, 1)
let robotMarker: THREE.Group | null = null

async function load() {
  const base = `/api/v1/map-3d/${encodeURIComponent(props.mapName)}`
  try {
    let state: { status: string; message?: string; version?: string; point_count?: number } | null = null
    for (let n = 0; n < 160; n++) {
      const response = await fetch(`${base}/status`, { signal: abort.signal })
      if (!response.ok) throw new Error(response.status === 404 ? '机器人上没有对应的 PBStream 地图' : '无法连接机器人三维地图服务')
      state = await response.json()
      if (state?.status === 'failed') throw new Error(state.message || '三维地图导出失败')
      if (state?.status === 'ready') break
      message.value = '正在生成三维地图，请稍候…'
      await new Promise<void>((resolve) => {
        const timer = setTimeout(done, 2000)
        function done() { clearTimeout(timer); abort.signal.removeEventListener('abort', done); resolve() }
        abort.signal.addEventListener('abort', done, { once: true })
      })
      if (abort.signal.aborted) return
    }
    if (state?.status !== 'ready') throw new Error('生成超时，请关闭窗口后重试')
    message.value = '正在下载三维点云…'
    const response = await fetch(`${base}/cloud.ply?v=${encodeURIComponent(state.version || '')}`, { signal: abort.signal })
    if (!response.ok) throw new Error('三维地图下载失败')
    const data = await response.arrayBuffer()
    if (abort.signal.aborted) return
    const geometry = new PLYLoader().parse(data)
    geometry.computeBoundingBox()
    const box = geometry.boundingBox!
    const position = geometry.getAttribute('position')
    const colors = new Float32Array(position.count * 3)
    const color = new THREE.Color()
    const span = Math.max(box.max.z - box.min.z, 0.1)
    for (let i = 0; i < position.count; i++) {
      color.setHSL(0.65 * (1 - (position.getZ(i) - box.min.z) / span), 0.8, 0.58)
      color.toArray(colors, i * 3)
    }
    geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3))
    scene.add(new THREE.Points(geometry, new THREE.PointsMaterial({ size: 0.09, vertexColors: true })))
    const center = box.getCenter(new THREE.Vector3())
    const size = Math.max(box.getSize(new THREE.Vector3()).length(), 5)
    camera.position.copy(center).add(new THREE.Vector3(size * 0.65, -size * 0.65, size * 0.6))
    camera.far = Math.max(5000, size * 10)
    camera.updateProjectionMatrix()
    controls!.target.copy(center)
    controls!.update()
    const axes = new THREE.AxesHelper(2)
    scene.add(axes)
    const grid = new THREE.GridHelper(Math.ceil(size), Math.ceil(size), 0x475569, 0x253247)
    grid.rotation.x = Math.PI / 2
    grid.position.set(center.x, center.y, 0)
    scene.add(grid)
    robotMarker = new THREE.Group()
    const arrow = new THREE.ArrowHelper(new THREE.Vector3(1, 0, 0), new THREE.Vector3(), 1, 0xff55aa)
    robotMarker.add(arrow)
    scene.add(robotMarker)
    message.value = `${position.count.toLocaleString()} 点 · ROS map 坐标系 · 拖动旋转 / 滚轮缩放 / 右键平移`
  } catch (exc) {
    if (!abort.signal.aborted) { error.value = true; message.value = exc instanceof Error ? exc.message : String(exc) }
  }
}

onMounted(() => {
  const el = viewport.value!
  renderer = new THREE.WebGLRenderer({ antialias: true })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  el.appendChild(renderer.domElement)
  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  observer = new ResizeObserver(() => {
    renderer!.setSize(el.clientWidth, el.clientHeight)
    camera.aspect = el.clientWidth / Math.max(el.clientHeight, 1)
    camera.updateProjectionMatrix()
  })
  observer.observe(el)
  function render() {
    frame = requestAnimationFrame(render)
    controls?.update()
    if (robotMarker) {
      const pose = robot.pose
      robotMarker.visible = !!pose && robot.status?.map_name === props.mapName
      if (pose) { robotMarker.position.set(pose.x, pose.y, 0.2); robotMarker.rotation.z = pose.yaw }
    }
    renderer?.render(scene, camera)
  }
  render()
  void load()
})

onBeforeUnmount(() => {
  abort.abort()
  cancelAnimationFrame(frame)
  observer?.disconnect()
  controls?.dispose()
  scene.traverse((object) => {
    const mesh = object as THREE.Mesh
    mesh.geometry?.dispose()
    if (Array.isArray(mesh.material)) mesh.material.forEach((m) => m.dispose())
    else mesh.material?.dispose()
  })
  renderer?.dispose()
  renderer?.domElement.remove()
})
</script>

<template>
  <div class="map3d-status" :class="{ error }">{{ message }}</div>
  <div ref="viewport" class="map3d-viewport" />
</template>

<style scoped>
.map3d-viewport { height: 70vh; width: 100%; }
.map3d-status { margin-bottom: 10px; color: #606266; }
.error { color: #f56c6c; }
</style>
