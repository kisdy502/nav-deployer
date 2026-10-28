import { get } from './http'
import type { SystemConfig } from '@/types/api'

/** 当前生效配置总览（只读）：RMS/zenoh、机器人连接、话题、充电参数 */
export const getSystemConfig = () => get<SystemConfig>('/system/config')
