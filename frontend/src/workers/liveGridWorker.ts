/**
 * 建图实时栅格轮询 worker。
 * 实时帧是 ~5MB 的 JSON（1248x944=117 万元素 data 数组），下载与 JSON.parse
 * 在主线程每 3s 冻结 50-150ms——建图界面周期性卡顿的根因，全部移到本 worker。
 * 占据值域 -1..100 恰好 Int8：整帧以 transferable 零拷贝回主线程。
 */
import type { MapGridVO } from '@/types/api'


const ctx = self as unknown as {
  onmessage: ((e: MessageEvent) => void) | null
  postMessage(msg: Record<string, unknown>, transfer?: Transferable[]): void
}

let timer: ReturnType<typeof setInterval> | undefined
let url = ''
let inFlight = false

async function tick() {
  if (inFlight) return
  inFlight = true
  try {
    const res = await fetch(url, { headers: { Accept: 'application/json' } })
    if (res.ok) {
      const g = (await res.json()) as Omit<MapGridVO, 'data'> & { data: number[] }
      const data = Int8Array.from(g.data)
      const { data: _ignored, ...meta } = g
      ctx.postMessage({ grid: meta, data }, [data.buffer])
    }
  } catch {
    /* 建图启动头几秒后端暂无实时图，静默等下一轮 */
  } finally {
    inFlight = false
  }
}

ctx.onmessage = (e: MessageEvent) => {
  const msg = e.data as { type: 'start' | 'stop'; url?: string; interval?: number }
  if (msg.type === 'start') {
    url = msg.url ?? ''
    if (timer) clearInterval(timer)
    void tick()
    timer = setInterval(tick, msg.interval ?? 3000)
  } else if (msg.type === 'stop') {
    if (timer) clearInterval(timer)
    timer = undefined
  }
}
