import type { RobotLayer } from './RobotLayer'

/**
 * 当前活跃 RobotLayer 引用（MapCanvas 挂载时注册、卸载时清除）。
 * 遥控器浮窗等画布外组件需要借用图层的 URDF 模型做机械臂数值逆解。
 */
let current: RobotLayer | null = null

export const registerRobotLayer = (layer: RobotLayer | null) => {
  if (current && layer === null) current = null
  else current = layer
}

export const getRobotLayer = () => current
