import type { PointType } from '@/types/api'

// 地图、列表与图例共享颜色和轮廓；坐标为 x 向右、y 向上。
export const POINT_STYLES: Record<PointType, { color: string; outline: number[][] }> = {
  NORMAL: { color: '#95d475', outline: Array.from({ length: 24 }, (_, i) => [Math.cos(i * Math.PI / 12) * 0.35, Math.sin(i * Math.PI / 12) * 0.35]) },
  CHARGER: { color: '#e6a23c', outline: [[0.1,0.8],[-0.55,-0.1],[-0.05,-0.1],[-0.15,-0.8],[0.55,0.2],[0.05,0.2]] },
  WORK: { color: '#409eff', outline: [[-0.7,-0.5],[-0.5,-0.7],[0.22,0.02],[0.58,0.02],[0.8,0.25],[0.8,0.6],[0.48,0.3],[0.25,0.52],[0.55,0.82],[0.2,0.82],[-0.04,0.58],[-0.04,0.22]] },
  HOME: { color: '#a78bfa', outline: [[-0.8,0.05],[0,0.78],[0.8,0.05],[0.55,0.05],[0.55,-0.65],[0.15,-0.65],[0.15,-0.15],[-0.15,-0.15],[-0.15,-0.65],[-0.55,-0.65],[-0.55,0.05]] },
}
export const pointStyle = (type: PointType) => POINT_STYLES[type] ?? POINT_STYLES.NORMAL
