import type { MapModeTaskVO, MapSyncEvent, MoveTaskVO, TelemetrySnapshot } from '@/types/api'

export interface AgvSseHandlers {
  onStatus?: (s: 'connecting' | 'open' | 'closed') => void
  onTelemetry?: (t: TelemetrySnapshot) => void
  onMoveTask?: (t: MoveTaskVO) => void
  onMapTask?: (t: MapModeTaskVO) => void
  onMapSync?: (e: MapSyncEvent) => void
}

/**
 * 连接 GET /sse/agv。
 * 浏览器 EventSource 出错时默认自动重连；仅当连接被彻底关闭（readyState=CLOSED）时手动重试。
 */
export function connectAgvSse(handlers: AgvSseHandlers) {
  let es: EventSource | null = null
  let manualTimer: number | undefined
  let closed = false

  const parse = <T>(data: string): T | null => {
    try {
      return JSON.parse(data) as T
    } catch {
      return null
    }
  }

  const connect = () => {
    if (closed) return
    handlers.onStatus?.('connecting')
    es = new EventSource('/sse/agv')
    es.onopen = () => handlers.onStatus?.('open')
    es.onerror = () => {
      handlers.onStatus?.('closed')
      if (es && es.readyState === EventSource.CLOSED) {
        es.close()
        manualTimer = window.setTimeout(connect, 3000)
      }
    }
    es.addEventListener('telemetry', (e) => {
      const t = parse<TelemetrySnapshot>(e.data)
      if (t) handlers.onTelemetry?.(t)
    })
    es.addEventListener('task', (e) => {
      const t = parse<MoveTaskVO>(e.data)
      if (t) handlers.onMoveTask?.(t)
    })
    es.addEventListener('map-task', (e) => {
      const t = parse<MapModeTaskVO>(e.data)
      if (t) handlers.onMapTask?.(t)
    })
    es.addEventListener('map-sync', (e) => {
      const t = parse<MapSyncEvent>(e.data)
      if (t) handlers.onMapSync?.(t)
    })
  }

  connect()
  return {
    close() {
      closed = true
      if (manualTimer) window.clearTimeout(manualTimer)
      es?.close()
      handlers.onStatus?.('closed')
    },
  }
}
