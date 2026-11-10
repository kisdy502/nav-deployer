import type { ChargeTaskVO, MapModeTaskVO, MapSyncEvent, MoveTaskVO, ScanCloudEvent, TelemetrySnapshot } from '@/types/api'

export interface AgvSseHandlers {
  onStatus?: (s: 'connecting' | 'open' | 'closed') => void
  onTelemetry?: (t: TelemetrySnapshot) => void
  onScan?: (t: ScanCloudEvent) => void
  onMoveTask?: (t: MoveTaskVO) => void
  onMapTask?: (t: MapModeTaskVO) => void
  onMapSync?: (e: MapSyncEvent) => void
  onChargeTask?: (t: ChargeTaskVO) => void
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

  // 页面后台时高频事件只保留最新一条原始数据（跳过 parse 与处理）：
  // 浏览器对后台标签节流任务派发，切回时积压的几百条 telemetry/scan
  // 一次性 parse+重建会把主线程卡死数十秒；10Hz 下切回后 100ms 内自然有新帧
  let hiddenTelemetryRaw: string | null = null
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) return
    if (hiddenTelemetryRaw) {
      const t = parse<TelemetrySnapshot>(hiddenTelemetryRaw)
      if (t) handlers.onTelemetry?.(t)
      hiddenTelemetryRaw = null
    }
  })

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
      if (document.hidden) {
        hiddenTelemetryRaw = e.data
        return
      }
      const t = parse<TelemetrySnapshot>(e.data)
      if (t) handlers.onTelemetry?.(t)
    })
    es.addEventListener('scan', (e) => {
      if (document.hidden) return
      const t = parse<ScanCloudEvent>(e.data)
      if (t) handlers.onScan?.(t)
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
    es.addEventListener('charge', (e) => {
      const t = parse<ChargeTaskVO>(e.data)
      if (t) handlers.onChargeTask?.(t)
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
