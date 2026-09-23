export interface XY {
  x: number
  y: number
}

/** 世界坐标(m) → 栅格像素列/行（含 y 翻转：第 0 行在世界坐标最下方） */
export function worldToGridPixel(
  grid: { origin_x: number; origin_y: number; resolution: number; width: number; height: number },
  x: number,
  y: number,
): { col: number; row: number } {
  const col = Math.floor((x - grid.origin_x) / grid.resolution)
  const row = grid.height - 1 - Math.floor((y - grid.origin_y) / grid.resolution)
  return { col, row }
}

/** 栅格像素 → 世界坐标(m)（取格子中心） */
export function gridPixelToWorld(
  grid: { origin_x: number; origin_y: number; resolution: number; width: number; height: number },
  col: number,
  row: number,
): XY {
  return {
    x: grid.origin_x + (col + 0.5) * grid.resolution,
    y: grid.origin_y + (grid.height - 1 - row + 0.5) * grid.resolution,
  }
}

/** 从 a 指向 b 的方位角（弧度，map 系逆时针为正） */
export function yawBetween(a: XY, b: XY): number {
  return Math.atan2(b.y - a.y, b.x - a.x)
}

/** 角度差归一化到 [-π, π] */
export function angleDelta(from: number, to: number): number {
  let d = to - from
  while (d > Math.PI) d -= Math.PI * 2
  while (d < -Math.PI) d += Math.PI * 2
  return d
}

/** 弧度 → 角度（保留 1 位小数） */
export const radToDeg = (rad: number) => Math.round((rad * 180) / Math.PI)
/** 角度 → 弧度 */
export const degToRad = (deg: number) => (deg * Math.PI) / 180
