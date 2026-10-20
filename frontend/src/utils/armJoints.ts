/**
 * 机械臂关节语义工具：角色识别 + 按侧收集（含单臂 profile 兜底）。
 *
 * 双臂构型：关节名含 arm+left/right（命名契约见《仿真机器人功能开发说明.md》§2.4）；
 * 单臂构型：UR5e 原始命名 ur5e_shoulder_pan_joint 等（无 arm/left 标记），左右控制同一物理臂。
 */
import { reactive } from 'vue'

export type ArmRole = 'pan' | 'lift' | 'elbow' | 'wrist1' | 'wrist2' | 'wrist3'

export const ARM_ROLE_ORDER: ArmRole[] = ['pan', 'lift', 'elbow', 'wrist1', 'wrist2', 'wrist3']

const HOME_KEY = 'nd_arm_home_v2'
/**
 * 归位默认值（角色 → 弧度，来自机器人侧初始姿态标定）。
 * 机器人侧数组按关节名字母序 [elbow, lift, pan, wrist1, wrist2, wrist3]
 * = [2.65, -2.6, 0, -1.62079632679, -1.57079632679, 0]，换算为角色顺序如下。
 */
const DEFAULT_HOME: Record<ArmRole, number> = {
  pan: 0,
  lift: -2.6,
  elbow: 2.65,
  wrist1: -1.62079632679,
  wrist2: -1.57079632679,
  wrist3: 0,
}

function loadHome(): Record<ArmRole, number> {
  try {
    const raw = localStorage.getItem(HOME_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      if (parsed && typeof parsed === 'object') {
        return { ...DEFAULT_HOME, ...parsed }
      }
    }
  } catch {
    /* 损坏则回默认 */
  }
  return { ...DEFAULT_HOME }
}

/** 归位姿态（响应式，角色 → 弧度）：预设/徽标/联锁共用；「设为归位位」更新 */
export const armHome = reactive<Record<ArmRole, number>>(loadHome())

export const ARM_HOME_TOLERANCE = 0.1

/** 把当前位姿（关节名+角）保存为归位：更新 armHome + localStorage，返回角色顺序数组（同步后端联锁用） */
export function saveArmHomeByPairs(pairs: [string, number][]): number[] {
  for (const [name, value] of pairs) {
    const role = armRoleOf(name)
    if (role) armHome[role] = +value.toFixed(4)
  }
  const ordered = ARM_ROLE_ORDER.map((r) => armHome[r])
  try {
    localStorage.setItem(HOME_KEY, JSON.stringify({ ...armHome }))
  } catch {
    /* 存储失败不影响本次会话 */
  }
  return ordered
}

/** 是否存在用户自定义归位（启动时回推后端联锁） */
export function hasArmHomeOverride(): boolean {
  try {
    return localStorage.getItem(HOME_KEY) != null
  } catch {
    return false
  }
}

/** 关节名 → 语义角色；数值命名兜底 joint_1..6 = pan/lift/elbow/wrist1/wrist2/wrist3 */
export function armRoleOf(name: string): ArmRole | null {
  const l = name.toLowerCase()
  if (l.includes('pan')) return 'pan'
  if (l.includes('lift')) return 'lift'
  if (l.includes('elbow')) return 'elbow'
  if (l.includes('wrist_1') || l.includes('wrist1')) return 'wrist1'
  if (l.includes('wrist_2') || l.includes('wrist2')) return 'wrist2'
  if (l.includes('wrist_3') || l.includes('wrist3')) return 'wrist3'
  const m = l.match(/joint[_-]?(\d)/) ?? l.match(/[_-](\d)(?:[_-]|$)/)
  if (m) {
    const roles: ArmRole[] = ['pan', 'lift', 'elbow', 'wrist1', 'wrist2', 'wrist3']
    return roles[Number(m[1]) - 1] ?? null
  }
  return null
}

/**
 * 该侧臂的 [关节名, 角度] 列表（按名排序，与 arm_move 的 joints 顺序约定一致）。
 * 双臂按 arm+side 匹配；无匹配时回退单臂 profile（ur5e_*_joint），左右都指向同一物理臂。
 */
export function armJointPairs(
  joints: { name: string[]; position: number[] } | null,
  side: 'left' | 'right',
): [string, number][] {
  if (!joints) return []
  const pairs: [string, number][] = []
  for (let i = 0; i < joints.name.length && i < joints.position.length; i++) {
    const lower = joints.name[i].toLowerCase()
    if (lower.includes('arm') && lower.includes(side)) pairs.push([joints.name[i], joints.position[i]])
  }
  if (pairs.length === 0) {
    for (let i = 0; i < joints.name.length && i < joints.position.length; i++) {
      const n = joints.name[i]
      if (n.startsWith('ur5e_') && n.endsWith('_joint')) pairs.push([n, joints.position[i]])
    }
  }
  return pairs.sort((a, b) => a[0].localeCompare(b[0]))
}

export function armJointNames(
  joints: { name: string[]; position: number[] } | null,
  side: 'left' | 'right',
): string[] {
  return armJointPairs(joints, side).map(([n]) => n)
}

/** 角色顺序重排（J1..J6 = pan..wrist3 展示用）；无法识别角色的排最后保持原序 */
export function armPairsByRole(pairs: [string, number][]): [string, number][] {
  return [...pairs].sort((a, b) => {
    const ra = armRoleOf(a[0])
    const rb = armRoleOf(b[0])
    const ia = ra ? ARM_ROLE_ORDER.indexOf(ra) : 99
    const ib = rb ? ARM_ROLE_ORDER.indexOf(rb) : 99
    return ia - ib
  })
}

/** 归位判定：每个关节按角色对照归位角（armHome），全部在容差内即归位（无法识别角色的关节判未归位） */
export function armHomed(
  joints: { name: string[]; position: number[] } | null,
  side: 'left' | 'right',
): boolean | null {
  const pairs = armJointPairs(joints, side)
  if (pairs.length === 0) return null
  let seen = false
  for (const [name, value] of pairs) {
    const role = armRoleOf(name)
    if (!role) return false
    seen = true
    if (Math.abs(value - armHome[role]) > ARM_HOME_TOLERANCE) return false
  }
  return seen
}
