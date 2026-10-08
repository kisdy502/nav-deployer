import * as THREE from 'three'
import type { MapGridVO } from '@/types/api'

/**
 * 占据栅格 → DataTexture。
 * 后端 data 行优先、第 0 行在世界坐标最下方；DataTexture(flipY=false) 的第 0 行
 * 对应 UV v=0，而 PlaneGeometry 的 UV(0,0) 在平面左下角，因此行序天然对齐，无需翻转。
 *
 * 配色 = RViz "map" 方案原样移植（rviz_default_plugins palette_builder.cpp makeMapPalette）：
 * - 0~100：纯灰度线性 grey = 255 - 255*v/100（0=白，100=黑，整数除法取整一致）
 * - -1（未知）：灰绿 (0x70, 0x89, 0x86) —— RViz 标志色
 * - >100（非法正值）：绿色；<-1（非法负值）：红→黄（防御分支，正常地图不会出现）
 */
const GREY_TABLE: number[] = (() => {
  // 预计算 0..100 的灰度查表（与 RViz 整数运算位位一致）
  const table: number[] = new Array(101)
  for (let i = 0; i <= 100; i++) {
    table[i] = 255 - Math.floor((255 * i) / 100)
  }
  return table
})()

/** 把栅格颜色写入已有 RGBA 缓冲；实时建图时复用缓冲，避免每秒分配数 MB。 */
export function writeGridPixels(grid: MapGridVO, buf: Uint8Array) {
  const { width, height, data } = grid
  if (buf.length !== width * height * 4) {
    throw new Error(`grid texture buffer size mismatch: ${buf.length} != ${width * height * 4}`)
  }
  for (let i = 0; i < width * height; i++) {
    const v = data[i]
    let r: number, g: number, b: number
    if (v === -1) {
      // 未知：RViz 灰绿
      r = 0x70; g = 0x89; b = 0x86
    } else if (v >= 0 && v <= 100) {
      // 合法占据概率：纯灰度线性
      const grey = GREY_TABLE[v]
      r = grey; g = grey; b = grey
    } else if (v > 100) {
      // 非法正值：绿（照抄 RViz）
      r = 0; g = 255; b = 0
    } else {
      // 非法负值（-128..-2）：红→黄渐变（照抄 RViz）
      r = 255
      g = Math.max(0, Math.min(255, Math.floor((255 * (v + 128)) / 126)))
      b = 0
    }
    buf[i * 4] = r
    buf[i * 4 + 1] = g
    buf[i * 4 + 2] = b
    buf[i * 4 + 3] = 255
  }
}

export function makeGridTexture(grid: MapGridVO): THREE.DataTexture {
  const { width, height } = grid
  const buf = new Uint8Array(width * height * 4)
  writeGridPixels(grid, buf)
  const tex = new THREE.DataTexture(buf, width, height, THREE.RGBAFormat)
  tex.flipY = false
  tex.magFilter = THREE.NearestFilter
  tex.minFilter = THREE.NearestFilter
  tex.generateMipmaps = false
  tex.needsUpdate = true
  return tex
}

/** 尺寸不变时原地更新纹理，保留 WebGL texture 对象，减少 GC 与 GPU 资源抖动。 */
export function updateGridTexture(tex: THREE.DataTexture, grid: MapGridVO) {
  const image = tex.image as { data: Uint8Array; width: number; height: number }
  if (image.width !== grid.width || image.height !== grid.height) {
    throw new Error(`grid texture dimensions changed: ${image.width}x${image.height} -> ${grid.width}x${grid.height}`)
  }
  writeGridPixels(grid, image.data)
  tex.needsUpdate = true
}
