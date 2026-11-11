import * as THREE from 'three'
import type { MapGridVO } from '@/types/api'
import { currentScenePalette } from './palette'

/**
 * 占据栅格 → DataTexture。
 * 后端 data 行优先、第 0 行在世界坐标最下方；DataTexture(flipY=false) 的第 0 行
 * 对应 UV v=0，而 PlaneGeometry 的 UV(0,0) 在平面左下角，因此行序天然对齐，无需翻转。
 *
 * 配色 = RViz "map" 方案移植（亮色=原版灰度/灰绿未知；暗色=灰度反转，占据越大概率越亮）。
 */

/** 把栅格颜色写入已有 RGBA 缓冲；实时建图时复用缓冲，避免每秒分配数 MB。 */
export function writeGridPixels(grid: MapGridVO, buf: Uint8Array) {
  const p = currentScenePalette()
  const { width, height, data } = grid
  if (buf.length !== width * height * 4) {
    throw new Error(`grid texture buffer size mismatch: ${buf.length} != ${width * height * 4}`)
  }
  for (let i = 0; i < width * height; i++) {
    const v = data[i]
    let r: number, g: number, b: number
    if (v === -1) {
      r = p.unknown[0]; g = p.unknown[1]; b = p.unknown[2]
    } else if (v >= 0 && v <= 100) {
      const grey = p.gridGreyOf(v)
      r = grey; g = grey; b = grey
    } else if (v > 100) {
      r = 0; g = 255; b = 0
    } else {
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
