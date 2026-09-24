/* 回归：浏览右键菜单 / 路线选中高亮 / 退出编辑后地图仍在 / 新机器人尺寸 */
const { chromium } = require('playwright-core')
const path = require('node:path')
const OUT = path.join(__dirname, 'shots')

async function main() {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true,
    args: ['--no-sandbox', '--disable-gpu', '--use-gl=swiftshader', '--enable-unsafe-swiftshader'],
  })
  const page = await browser.newPage({ viewport: { width: 1600, height: 950 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(`[pageerror] ${e.message}`))

  await page.goto('http://localhost:5173/', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2500)

  // 右键点位 → 浏览态菜单（导航到此/定位到此）
  const p1 = await page.evaluate(() => {
    const el = [...document.querySelectorAll('.nd-label-inner')].find((e) => e.textContent === 'P1')
    const r = el.getBoundingClientRect()
    return { x: r.x + r.width / 2, y: r.y + 22 }
  })
  await page.mouse.click(p1.x, p1.y, { button: 'right' })
  await page.waitForTimeout(400)
  await page.screenshot({ path: path.join(OUT, '30-browse-ctx.png') })
  const ctxItems = await page.evaluate(() => [...document.querySelectorAll('.ctx-item')].map((e) => e.textContent.trim()))
  console.log('--- BROWSE CTX (point) ---', JSON.stringify(ctxItems))
  await page.mouse.click(10, 500) // 关闭菜单
  await page.waitForTimeout(200)

  // 点击路线选中 → 高亮 + 辅助点（截图确认）
  await page.locator('.browse-main canvas').first().click() // 先聚焦
  await page.waitForTimeout(200)
  // P1→P2 直线段大约在中下方；从视觉坐标点击路线（用两点中点换算：先取 P1/P2 标签）
  const mid = await page.evaluate(() => {
    const find = (n) => {
      const el = [...document.querySelectorAll('.nd-label-inner')].find((e) => e.textContent === n)
      const r = el.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + 22 }
    }
    const a = find('P1')
    const b = find('P2')
    return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
  })
  await page.mouse.click(mid.x, mid.y)
  await page.waitForTimeout(500)
  await page.screenshot({ path: path.join(OUT, '31-path-selected.png') })
  const panel = await page.evaluate(() => document.querySelector('.browse-right')?.textContent?.replace(/\s+/g, ' ').slice(0, 120))
  console.log('--- PANEL AFTER PATH CLICK ---', panel)

  // 进入编辑器再退出 → 地图应仍在（无需刷新）
  await page.locator('button', { hasText: '编辑地图' }).first().click()
  await page.waitForTimeout(2200)
  await page.locator('button', { hasText: '退出编辑' }).click()
  await page.waitForTimeout(2200)
  await page.screenshot({ path: path.join(OUT, '32-back-to-browse.png') })
  const canvasPixels = await page.evaluate(() => {
    const c = document.querySelector('.browse-main canvas')
    if (!c) return 'NO_CANVAS'
    // 采样画布中心区域，判断是否画了内容（非纯背景色）
    const ctx2 = c.getContext('webgl2') || c.getContext('webgl')
    if (!ctx2) return 'NO_GL'
    const px = new Uint8Array(4)
    ctx2.readPixels(Math.floor(c.width / 2), Math.floor(c.height / 2), 1, 1, ctx2.RGBA, ctx2.UNSIGNED_BYTE, px)
    return `center_pixel=${px.join(',')}`
  })
  console.log('--- CANVAS AFTER BACK ---', canvasPixels)
  const backPanel = await page.evaluate(() => document.querySelector('.browse-right')?.textContent?.replace(/\s+/g, ' ').slice(0, 80))
  console.log('--- BACK PANEL ---', backPanel)

  console.log('--- ERRORS ---')
  console.log(errors.length ? errors.join('\n') : '(none)')
  await browser.close()
}
main().catch((e) => { console.error('FAILED', e); process.exit(1) })
