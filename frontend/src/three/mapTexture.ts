import * as THREE from 'three'
import type { MapGridVO } from '@/types/api'

/**
 * 占据栅格 → DataTexture。
 * 后端 data 行优先、第 0 行在世界坐标最下方；DataTexture(flipY=false) 的第 0 行
 * 对应 UV v=0，而 PlaneGeometry 的 UV(0,0) 在平面左下角，因此行序天然对齐，无需翻转。
 */
export function makeGridTexture(grid: MapGridVO): THREE.DataTexture {
  const { width, height, data } = grid
  const buf = new Uint8Array(width * height * 4)
  for (let i = 0; i < width * height; i++) {
    const v = data[i]
    let r = 0xff
    let g = 0xff
    let b = 0xff
    if (v === -1) {
      r = 0xd9
      g = 0xdc
      b = 0xe1 // 未知：浅灰
    } else if (v > 65) {
      r = 0x30
      g = 0x35
      b = 0x40 // 占据：深色
    } else if (v > 0) {
      r = 0x8a
      g = 0x90
      b = 0x9c // 弱占据：中灰
    }
    buf[i * 4] = r
    buf[i * 4 + 1] = g
    buf[i * 4 + 2] = b
    buf[i * 4 + 3] = 255
  }
  const tex = new THREE.DataTexture(buf, width, height, THREE.RGBAFormat)
  tex.flipY = false
  tex.magFilter = THREE.NearestFilter
  tex.minFilter = THREE.NearestFilter
  tex.generateMipmaps = false
  tex.needsUpdate = true
  return tex
}
