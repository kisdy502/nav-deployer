/* 复现「建图中切后台 → 回前台遥控卡顿」：
 * 阶段 A 前台 6s 基线 → 阶段 B 后台 30s（rAF 停、定时器节流）→ 阶段 C 回前台 25s（恢复遥控场景）
 * 每阶段分别统计 fps / 帧间隔 / longtask / telemetry / scan / 实时图请求 */
const { chromium } = require('playwright-core')

const BASE = 'http://localhost:5173'
const HIDDEN_MS = 30000
const AFTER_MS = 25000

function pct(arr, p) {
  if (arr.length === 0) return 0
  const s = [...arr].sort((a, b) => a - b)
  return s[Math.min(s.length - 1, Math.floor((s.length - 1) * p))]
}

async function main() {
  const browser = await chromium.launch({
    executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    headless: false,
    args: ['--no-sandbox', '--no-first-run'],
  })
  const context = await browser.newContext({ viewport: { width: 1600, height: 950 } })
  const page = await context.newPage()

  await page.addInitScript(() => {
    window.__ev = { tel: [], scan: [], frames: [], longtasks: [], live: [], vis: [] }
    const rec = window.__ev
    document.addEventListener('visibilitychange', () => {
      rec.vis.push({ t: performance.now(), hidden: document.hidden })
    })
    const origAdd = EventTarget.prototype.addEventListener
    EventTarget.prototype.addEventListener = function (type, fn, opts) {
      if ((type === 'telemetry' || type === 'scan') && this instanceof EventSource) {
        const wrapped = (e) => {
          rec[type === 'telemetry' ? 'tel' : 'scan'].push({ t: performance.now(), len: e.data ? e.data.length : 0 })
          return fn(e)
        }
        return origAdd.call(this, type, wrapped, opts)
      }
      return origAdd.call(this, type, fn, opts)
    }
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) rec.longtasks.push({ start: e.startTime, dur: e.duration })
    }).observe({ entryTypes: ['longtask'] })
    const raf = (t) => { rec.frames.push(t); requestAnimationFrame(raf) }
    requestAnimationFrame(raf)
    const origOpen = XMLHttpRequest.prototype.open
    XMLHttpRequest.prototype.open = function (method, url, ...rest) {
      this.__t0 = performance.now()
      this.__url = String(url)
      this.addEventListener('loadend', () => {
        if (this.__url.includes('/nav-maps/live')) {
          rec.live.push({
            t: this.__t0, done: performance.now(), status: this.status,
            size: this.responseText ? this.responseText.length : null,
          })
        }
      })
      return origOpen.call(this, method, url, ...rest)
    }
  })

  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(6000) // 阶段 A：前台基线

  // 建图实时预览是否在轮询（没在轮询则本次复测无意义）
  const liveCount = () => page.evaluate(() => window.__ev.live.length)
  if (await liveCount() === 0) {
    console.log('!! 未检测到 /nav-maps/live 轮询（机器人可能不在建图模式），数据无效')
    await browser.close()
    process.exit(2)
  }

  // 阶段边界用页面时钟显式打点，避免依赖 visibilitychange 事件
  const tBack = await page.evaluate(() => performance.now())
  const cdp = await context.newCDPSession(page)
  const { windowId } = await cdp.send('Browser.getWindowForTarget')
  await cdp.send('Browser.setWindowBounds', { windowId, bounds: { windowState: 'minimized' } })
  console.log(`[phase B] minimized ${HIDDEN_MS}ms ...`)
  await page.waitForTimeout(HIDDEN_MS)

  await cdp.send('Browser.setWindowBounds', { windowId, bounds: { windowState: 'normal' } })
  await page.bringToFront() // 阶段 C：切回前台（对应"回来遥控"）
  const tFore = await page.evaluate(() => performance.now())
  console.log(`[phase C] foreground ${AFTER_MS}ms ...`)
  await page.waitForTimeout(AFTER_MS)

  const d = await page.evaluate(() => window.__ev)
  await browser.close()

  const backT = tBack
  const foreT = tFore
  console.log(`\n后台时刻=${backT.toFixed(0)}ms  回前台时刻=${foreT.toFixed(0)}ms`)

  const phases = [
    ['A 前台基线', 0, backT],
    ['B 后台', backT, foreT],
    ['C 回前台', foreT, Infinity],
  ]
  for (const [name, lo, hi] of phases) {
    const hiReal = isFinite(hi) ? hi : Math.max(...d.frames, foreT)
    const inRange = (x) => x.t >= lo && x.t < hiReal
    const frames = d.frames.filter((t) => t >= lo && t < hiReal)
    const gaps = []
    for (let i = 1; i < frames.length; i++) gaps.push(frames[i] - frames[i - 1])
    const lt = d.longtasks.filter((x) => x.start >= lo && x.start < hiReal)
    const tel = d.tel.filter(inRange)
    const telIv = []
    for (let i = 1; i < tel.length; i++) telIv.push(tel[i].t - tel[i - 1].t)
    const scan = d.scan.filter(inRange)
    const live = d.live.filter((x) => x.t >= lo && x.t < hiReal)
    console.log(`\n===== ${name} (${((hiReal - lo) / 1000).toFixed(0)}s) =====`)
    if (frames.length > 1) {
      console.log(`rAF 帧数=${frames.length} 帧率=${(frames.length / ((frames[frames.length - 1] - frames[0]) / 1000)).toFixed(1)}fps`)
      if (gaps.length) console.log(`帧间隔 p50=${pct(gaps, 0.5).toFixed(1)} p95=${pct(gaps, 0.95).toFixed(1)} max=${Math.max(...gaps).toFixed(1)}ms  >100ms 次数=${gaps.filter((g) => g > 100).length}`)
    } else {
      console.log(`rAF 帧数=${frames.length}（后台 rAF 停摆属正常）`)
    }
    console.log(`longtask: ${lt.length} 个, 总阻塞=${lt.reduce((a, b) => a + b.dur, 0).toFixed(0)}ms, max=${lt.length ? Math.max(...lt.map((x) => x.dur)).toFixed(0) : 0}ms`)
    for (const x of lt.filter((x) => x.dur > 100)) console.log(`   start=${x.start.toFixed(0)}ms dur=${x.dur.toFixed(0)}ms`)
    console.log(`telemetry: ${tel.length} 个${telIv.length ? `, 间隔 p50=${pct(telIv, 0.5).toFixed(0)} p95=${pct(telIv, 0.95).toFixed(0)} max=${Math.max(...telIv).toFixed(0)}ms` : ''}`)
    console.log(`scan: ${scan.length} 个, 平均 ${(scan.reduce((a, b) => a + b.len, 0) / Math.max(1, scan.length) / 1024).toFixed(1)}KB`)
    for (const f of live) console.log(`live: start=${f.t.toFixed(0)}ms 耗时=${(f.done - f.t).toFixed(0)}ms status=${f.status} ${(f.size / 1024 / 1024).toFixed(2)}MB`)
  }

  console.log('\n===== 回前台 ±5s 明细 =====')
  for (const x of d.longtasks.filter((x) => Math.abs(x.start - foreT) < 5000)) {
    console.log(`  ${(x.start - foreT >= 0 ? '+' : '') + ((x.start - foreT) / 1000).toFixed(2)}s dur=${x.dur.toFixed(0)}ms`)
  }
}

main().catch((e) => {
  console.error('PERF TEST FAILED:', e)
  process.exit(1)
})
