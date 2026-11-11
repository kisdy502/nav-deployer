import { isDark } from '@/utils/theme'

/**
 * three 场景背景层调色板（亮暗两套，RVIZ 风格）。
 * 点位/路线/机器人等强调色（Element 系红蓝绿橙）在暗底同样可读，暂不参与主题切换。
 */
export interface ScenePalette {
  /** 场景背景 */
  bg: number
  /** 世界网格（GridHelper 中心线/普通线） */
  gridMinor: { center: number; line: number }
  gridMajor: { center: number; line: number }
  /** 地图边框 */
  mapBorder: number
  /** 占据概率 0~100 的灰度（亮色=RViz 白→黑；暗色反转=深底→亮，障碍物亮色可读） */
  gridGreyOf: (v: number) => number
  /** 未知栅格 (-1) RGB（亮色=RViz 灰绿；暗色=深灰绿） */
  unknown: [number, number, number]
}

const lightPalette: ScenePalette = {
  bg: 0xeef1f6,
  gridMinor: { center: 0xa8afbd, line: 0xdde1e8 },
  gridMajor: { center: 0xa8afbd, line: 0xd2d7e0 },
  mapBorder: 0xb9bdc7,
  gridGreyOf: (v) => 255 - Math.floor((255 * v) / 100),
  unknown: [0x70, 0x89, 0x86],
}

const darkPalette: ScenePalette = {
  bg: 0x1a1d21,
  gridMinor: { center: 0x4a515c, line: 0x2a2e35 },
  gridMajor: { center: 0x4a515c, line: 0x363b43 },
  mapBorder: 0x3a4048,
  gridGreyOf: (v) => 30 + Math.floor((185 * v) / 100),
  unknown: [0x27, 0x2e, 0x33],
}

export function currentScenePalette(): ScenePalette {
  return isDark() ? darkPalette : lightPalette
}
