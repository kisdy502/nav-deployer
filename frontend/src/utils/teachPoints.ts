/**
 * 机械臂示教点（localStorage 持久化，仅本机）。
 * joints 为该臂排序后（/joint_states 关节名序）的目标关节角，弧度。
 */
export interface ArmTeachPoint {
  id: string
  name: string
  side: 'left' | 'right'
  joints: number[]
  createdAt: number
}

const KEY = 'nd_arm_teach_points_v1'

export function loadTeachPoints(): ArmTeachPoint[] {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((p) => p && typeof p.name === 'string'
      && (p.side === 'left' || p.side === 'right') && Array.isArray(p.joints))
  } catch {
    return []
  }
}

export function saveTeachPoint(point: { name: string; side: 'left' | 'right'; joints: number[] }): ArmTeachPoint {
  const full: ArmTeachPoint = {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    name: point.name,
    side: point.side,
    joints: point.joints.map((v) => +v.toFixed(4)),
    createdAt: Date.now(),
  }
  const all = loadTeachPoints()
  all.push(full)
  localStorage.setItem(KEY, JSON.stringify(all))
  return full
}

export function deleteTeachPoint(id: string): void {
  localStorage.setItem(KEY, JSON.stringify(loadTeachPoints().filter((p) => p.id !== id)))
}
