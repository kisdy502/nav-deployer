import * as THREE from 'three'
import type { MapGridVO } from '@/types/api'

/**
 * 占据栅格 → DataTexture。
 * 后端 data 为 ROS 行序（第 0 行 = 世界最下方）；GPU 把纹理缓冲第 0 行贴在平面顶部
 * （图像行序），与 ROS 行序正好相反，因此填充缓冲时行序整体倒置——
 * 缓冲第 r 行取数据第 height-1-r 行，世界最下方才能渲染在平面底部，与 RViz 一致。
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
    // 缓冲行序倒置：数据第 0 行（世界最下方）写入缓冲最后一行
    const dst = ((height - 1 - ((i / width) | 0)) * width + (i % width)) * 4
    buf[dst] = r
    buf[dst + 1] = g
    buf[dst + 2] = b
    buf[dst + 3] = 255
  }
  const tex = new THREE.DataTexture(buf, width, height, THREE.RGBAFormat)
  tex.flipY = false
  tex.magFilter = THREE.NearestFilter
  tex.minFilter = THREE.NearestFilter
  tex.generateMipmaps = false
  tex.needsUpdate = true
  return tex
}
