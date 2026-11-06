/* 建图实时预览性能实测：连运行中的页面采样 ~25s，输出
 * ① longtask ② rAF 帧间隔/掉帧 ③ telemetry 位姿节奏 ④ 实时图接口耗时/体积 ⑤ CPU profile 聚合 */
const { chromium } = require('playwright-core')
const path = require('node:path')

const BASE = 'http://localhost:5173'
const DURATION_MS = 25000

function pct(arr, p) {
  if (arr.length === 0) return 0
  const s = [...arr].sort((a, b) => a - b)
  return s[Math.min(s.length - 1, Math.floor((s.length - 1) * p))]
}

function fmtUrl(url) {
  if (!url) return ''
  if (url.includes('node_modules')) return 'node_modules'
  const m = url.match(/\/src\/(.+)$/)
  if (m) return m[1]
  return url.slice(-60)
}

async function main() {
  const browser = await chromium.launch({
    executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    headless: false,
    args: ['--no-sandbox', '--no-first-run'],
  })
  const page = await browser.newPage({ viewport: { width: 1600, height: 950 } })

  // 在应用加载前打补丁：记录 telemetry 事件节奏 + 位姿变化（用于判断卡顿是否来自位姿断流）
  await page.addInitScript(() => {
    window.__tel = []
    window.__frames = []
    window.__longtasks = []
    window.__liveFetch = []
    const origAdd = EventTarget.prototype.addEventListener
    EventTarget.prototype.addEventListener = function (type, fn, opts) {
      if (type === 'telemetry' && this instanceof EventSource) {
        const wrapped = (e) => {
          const rec = { t: performance.now(), len: e.data ? e.data.length : 0, x: null, y: null }
          window.__tel.push(rec)
          try {
            if (rec.len < 300000) {
              const j = JSON.parse(e.data)
              if (j && j.pose) { rec.x = j.pose.x; rec.y = j.pose.y }
            }
          } catch { /* ignore */ }
          return fn(e)
        }
        return origAdd.call(this, type, wrapped, opts)
      }
      return origAdd.call(this, type, fn, opts)
    }
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) window.__longtasks.push({ start: e.startTime, dur: e.duration })
    }).observe({ entryTypes: ['longtask'] })
    const raf = (t) => { window.__frames.push(t); requestAnimationFrame(raf) }
    requestAnimationFrame(raf)
    const origFetch = window.fetch
    window.fetch = async (...args) => {
      const t0 = performance.now()
      const res = await origFetch(...args)
      const u = String(args[0])
      if (u.includes('/nav-maps/live') || u.includes('/live')) {
        let size = null
        try { size = (await res.clone().text()).length } catch { /* ignore */ }
        window.__liveFetch.push({ t: t0, done: performance.now(), status: res.status, size })
      }
      return res
    }
  })

  // XHR（axios）也要抓实时图接口
  await page.addInitScript(() => {
    const origOpen = XMLHttpRequest.prototype.open
    XMLHttpRequest.prototype.open = function (method, url, ...rest) {
      this.__t0 = performance.now()
      this.__url = String(url)
      this.addEventListener('loadend', () => {
        if (this.__url.includes('/nav-maps/live')) {
          window.__liveFetch.push({
            t: this.__t0, done: performance.now(), status: this.status,
            size: this.responseType === '' || this.responseType === 'text' ? String(this.responseText).length : null,
          })
        }
      })
      return origOpen.call(this, method, url, ...rest)
    }
  })

  const cdp = await page.context().newCDPSession(page)
  await cdp.send('Network.enable')
  await cdp.send('Profiler.enable')
  await cdp.send('Profiler.setSamplingInterval', { interval: 2000 }) // 2ms 采样
  await cdp.send('Profiler.start')

  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' })
  console.log(`sampling ${DURATION_MS}ms ...`)
  await page.waitForTimeout(DURATION_MS)

  const data = await page.evaluate(() => ({
    tel: window.__tel,
    frames: window.__frames,
    longtasks: window.__longtasks,
    liveFetch: window.__liveFetch,
  }))
  const { profile } = await cdp.send('Profiler.stop')
  await browser.close()

  // ---------- 报告 ----------
  const tEnd = data.frames.length ? data.frames[data.frames.length - 1] : 0
  const t0 = data.frames.length ? data.frames[0] : 0
  console.log('\n================ 1) 帧率 / 掉帧 ================')
  const gaps = []
  for (let i = 1; i < data.frames.length; i++) gaps.push(data.frames[i] - data.frames[i - 1])
  const fps = data.frames.length / ((tEnd - t0) / 1000)
  console.log(`平均帧率: ${fps.toFixed(1)} fps, 帧数: ${data.frames.length}`)
  console.log(`帧间隔 p50=${pct(gaps, 0.5).toFixed(1)}ms p95=${pct(gaps, 0.95).toFixed(1)}ms max=${Math.max(...gaps).toFixed(1)}ms`)
  const big = gaps.filter((g) => g > 100).length
  console.log(`>100ms 的帧间隔数: ${big}`)
  for (let i = 1; i < data.frames.length; i++) {
    const g = data.frames[i] - data.frames[i - 1]
    if (g > 150) console.log(`  掉帧 ${(t0 + 0).toFixed(0)}ms 基准: 第 ${data.frames[i - 1].toFixed(0)}ms 帧后卡了 ${g.toFixed(0)}ms`)
  }

  console.log('\n================ 2) longtask（主线程长任务） ================')
  console.log(`总数: ${data.longtasks.length}`)
  for (const lt of data.longtasks) {
    console.log(`  start=${lt.start.toFixed(0)}ms dur=${lt.dur.toFixed(0)}ms`)
  }

  console.log('\n================ 3) telemetry 位姿节奏 ================')
  const tel = data.tel
  console.log(`事件数: ${tel.length}, 平均 data 长度: ${(tel.reduce((a, b) => a + b.len, 0) / Math.max(1, tel.length) / 1024).toFixed(1)}KB`)
  const iv = []
  for (let i = 1; i < tel.length; i++) iv.push(tel[i].t - tel[i - 1].t)
  if (iv.length) {
    console.log(`事件间隔 ms: p50=${pct(iv, 0.5).toFixed(0)} p90=${pct(iv, 0.9).toFixed(0)} max=${Math.max(...iv).toFixed(0)}`)
    const poseTel = tel.filter((e) => e.x != null)
    const poseIv = []
    for (let i = 1; i < poseTel.length; i++) poseIv.push(poseTel[i].t - poseTel[i - 1].t)
    console.log(`带位姿事件: ${poseTel.length}/${tel.length}`)
    if (poseIv.length) console.log(`位姿间隔 ms: p50=${pct(poseIv, 0.5).toFixed(0)} p90=${pct(poseIv, 0.9).toFixed(0)} max=${Math.max(...poseIv).toFixed(0)}`)
    const moved = poseTel.filter((e, i) => i > 0 && (Math.abs(e.x - poseTel[i - 1].x) > 0.001 || Math.abs(e.y - poseTel[i - 1].y) > 0.001)).length
    console.log(`位姿在动的样本: ${moved}/${Math.max(1, poseTel.length - 1)}（机器人移动中?）`)
    const jumps = poseTel.filter((e, i) => i > 0 && Math.hypot(e.x - poseTel[i - 1].x, e.y - poseTel[i - 1].y) > 0.25).length
    console.log(`单样本位移 >0.25m 的跳变次数: ${jumps}`)
  }

  console.log('\n================ 4) 实时图接口（/nav-maps/live） ================')
  console.log(`请求数: ${data.liveFetch.length}`)
  for (const f of data.liveFetch) {
    console.log(`  start=${f.t.toFixed(0)}ms 耗时=${(f.done - f.t).toFixed(0)}ms status=${f.status} 响应=${f.size == null ? '?' : (f.size / 1024 / 1024).toFixed(2) + 'MB'}`)
  }

  console.log('\n================ 5) CPU 采样 profile（self time 聚合） ================')
  const nodes = new Map()
  for (const n of profile.nodes) {
    nodes.set(n.id, {
      fn: n.callFrame.functionName || '(anonymous)',
      url: fmtUrl(n.callFrame.url),
      self: 0,
    })
  }
  for (let i = 0; i < profile.samples.length; i++) {
    const n = nodes.get(profile.samples[i])
    const d = profile.timeDeltas[i]
    if (n && d > 0 && d < 50000) n.self += d
  }
  const total = [...nodes.values()].reduce((a, b) => a + b.self, 0)
  console.log(`采样总时长: ${(total / 1000).toFixed(1)}ms`)
  const byFn = new Map()
  for (const n of nodes.values()) {
    const key = `${n.fn}  [${n.url}]`
    byFn.set(key, (byFn.get(key) ?? 0) + n.self)
  }
  ;[...byFn.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 28)
    .forEach(([k, v], i) => console.log(`  ${String(i + 1).padStart(2)}. ${String((v / 1000).toFixed(0)).padStart(6)}ms  ${k}`))
}

main().catch((e) => {
  console.error('PERF TEST FAILED:', e)
  process.exit(1)
})
