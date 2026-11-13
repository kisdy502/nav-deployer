/**
 * 点位类型样式（地图、列表、图例共享）。key 统一小写（v2 新词表 11 类），
 * 旧版类型（v1 大写 WORK/HOME、早期 normal）经 normalizePointType 归一。
 * 坐标为 x 向右、y 向上。
 */
export type PointStyleKey =
  | 'path'
  | 'charge'
  | 'pickup'
  | 'dropoff'
  | 'storage'
  | 'elevator'
  | 'stairs'
  | 'ramp'
  | 'relocation'
  | 'parking'
  | 'workstation'
  | 'dock'
  | 'roller'

/** 旧类型归一：v1 大写（NORMAL/WORK/CHARGER/HOME）与早期 normal → 新词表 */
const LEGACY_ALIAS: Record<string, PointStyleKey> = {
  NORMAL: 'path',
  normal: 'path',
  WORK: 'workstation',
  HOME: 'parking',
}

/** 圆盘统一绿；color 字段=类型图标色（深色调，绿底上清晰） */
export const POINT_UNIFIED_COLOR = '#95d475'
export const GLYPH_BASE_COLOR = '#52667a'

export const POINT_STYLES: Record<PointStyleKey, {
  color: string
  label: string
  outline: number[][]
  /** 可选挖孔环（如重定位 pin 空心）；渲染端须配 evenodd / Shape.holes */
  holes?: number[][]
}> = {
  // 路径点：无图形符号（outline 为空 → 圆盘上只留朝向箭头）
  path: { color: POINT_UNIFIED_COLOR, label: '路径点', outline: [] },
  charge: {
    color: '#e65100',
    label: '充电点',
    outline: [[0.1, 0.8], [-0.55, -0.1], [-0.05, -0.1], [-0.15, -0.8], [0.55, 0.2], [0.05, 0.2]],
  },
  pickup: {
    color: GLYPH_BASE_COLOR,
    label: '取货点',
    // 三爪内向（120° 旋转对称：任意点位朝向下形状不变；爪尖指向中心=抓取/上料）
    outline: [
      [0, 0.75], [-0.19, 0.11], [-0.65, -0.375],
      [0, -0.22], [0.65, -0.375], [0.19, 0.11],
    ],
  },
  dropoff: {
    color: GLYPH_BASE_COLOR,
    label: '卸货点',
    // 三叉外向（120° 旋转对称；三尖朝外=放出/下料，与取货的"内向爪"互为镜像语义）
    outline: [
      [0, 0.8], [-0.26, 0.15], [-0.69, -0.4],
      [0, -0.3], [0.69, -0.4], [0.26, 0.15],
    ],
  },
  storage: {
    color: GLYPH_BASE_COLOR,
    label: '暂存点',
    outline: [[-0.8, 0.05], [0, 0.78], [0.8, 0.05], [0.55, 0.05], [0.55, -0.65], [0.15, -0.65], [0.15, -0.15], [-0.15, -0.15], [-0.15, -0.65], [-0.55, -0.65], [-0.55, 0.05]],
  },
  elevator: {
    color: GLYPH_BASE_COLOR,
    label: '电梯口',
    // 电梯门：两扇门板 + 顶部横梁 + 中间缝隙（俯视门开效果）
    outline: [
      [-0.7, 0.7], [0.7, 0.7], [0.7, -0.7],
      [0.12, -0.7], [0.12, 0.45], [-0.12, 0.45], [-0.12, -0.7],
      [-0.7, -0.7],
    ],
  },
  stairs: {
    color: GLYPH_BASE_COLOR,
    label: '楼梯口',
    outline: [[-0.7, -0.7], [-0.7, -0.25], [-0.28, -0.25], [-0.28, 0.2], [0.14, 0.2], [0.14, 0.62], [0.56, 0.62], [0.56, 0.85], [0.85, 0.85], [0.85, -0.7]],
  },
  ramp: {
    color: GLYPH_BASE_COLOR,
    label: '坡道口',
    outline: [[-0.8, -0.65], [0.8, 0.65], [0.8, -0.65]],
  },
  relocation: {
    color: '#8e24aa',
    label: '重定位点',
    // 空心定位 pin：带尖圆立于底盘圆上（单连通外环），头圆中心挖孔（holes + evenodd）
    outline: [
      [-0.5, 0.35], [-0.38, 0.72], [-0.15, 0.84], [0.15, 0.84], [0.38, 0.72], [0.5, 0.35],
      [0.16, -0.35],
      [0.68, -0.52], [0.42, -0.82], [-0.42, -0.82], [-0.68, -0.52],
      [-0.16, -0.35],
    ],
    holes: [
      [0.24, 0.38], [0.17, 0.55], [0, 0.62], [-0.17, 0.55],
      [-0.24, 0.38], [-0.17, 0.21], [0, 0.16], [0.17, 0.21],
    ],
  },
  parking: {
    color: '#1565c0',
    label: '归巢点',
    // 六边形蜂巢（巢穴意象，区别于暂存点的房子）
    outline: [[0, 0.8], [0.7, 0.4], [0.7, -0.4], [0, -0.8], [-0.7, -0.4], [-0.7, 0.4]],
  },
  workstation: {
    color: '#00838f',
    label: '作业点',
    // 叉车轮廓（延续 v1 作业点经典图形）
    outline: [[-0.7, -0.5], [-0.5, -0.7], [0.22, 0.02], [0.58, 0.02], [0.8, 0.25], [0.8, 0.6], [0.48, 0.3], [0.25, 0.52], [0.55, 0.82], [0.2, 0.82], [-0.04, 0.58], [-0.04, 0.22]],
  },
  dock: {
    color: GLYPH_BASE_COLOR,
    label: '对接点',
    // 对接端口（梯形插头 + 连接线）
    outline: [[-0.3, 0.8], [0.3, 0.8], [0.5, 0.3], [0.5, -0.3], [0.3, -0.8], [-0.3, -0.8], [-0.5, -0.3], [-0.5, 0.3]],
  },
  roller: {
    color: GLYPH_BASE_COLOR,
    label: '滚筒点',
    // 滚筒（圆筒侧视 + 轴线）
    outline: [[-0.6, 0.5], [0.6, 0.5], [0.75, 0.2], [0.75, -0.2], [0.6, -0.5], [-0.6, -0.5], [-0.75, -0.2], [-0.75, 0.2]],
  },
}

/** 任意类型串（含旧大写/normal）→ 样式 key；未知类型回退 path。 */
export const normalizePointType = (type: string): PointStyleKey => {
  const lower = type?.toLowerCase()
  if (lower && lower in POINT_STYLES) return lower as PointStyleKey
  return LEGACY_ALIAS[type] ?? LEGACY_ALIAS[lower ?? ''] ?? 'path'
}

export const pointStyle = (type: string) => POINT_STYLES[normalizePointType(type)]

/**
 * 类型图形的 SVG polygon points 串（外环 + 可选孔环拼接），须配合 fill-rule="evenodd"。
 * scale/offset 用于把 outline 坐标适配进列表/图例的小圆内（与朝向箭头共存时整体左移缩放）。
 */
export const outlineSvgPoints = (
  style: ReturnType<typeof pointStyle>,
  scale = 0.5,
  offsetX = -0.5,
): string => {
  const ring = (points: number[][]) =>
    points.map(([x, y]) => `${x * scale + offsetX},${-y * scale}`).join(' ')
  const rings = [ring(style.outline)]
  if (style.holes?.length) {
    rings.push(ring(style.holes))
  }
  return rings.join(' ')
}
