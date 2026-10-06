import { get, post } from './http'
import type { TelemetrySnapshot } from '@/types/api'

export const getRobotSnapshot = () => get<TelemetrySnapshot>('/robot/snapshot')
export const robotControl = (action: 'start' | 'stop' | 'reset') =>
  post<{ success: boolean; message: string; state: string }>('/robot/control', { action })
export const setInitialPose = (x: number, y: number, theta: number) =>
  post<void>('/robot/initial-pose', { x, y, theta })

export const sendTeleop = (linearX: number, linearY: number, angularZ: number) =>
  post<void>('/robot/teleop', { linear_x: linearX, linear_y: linearY, angular_z: angularZ })
