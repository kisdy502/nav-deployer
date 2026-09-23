/* 路线渲染 + 路线编辑（贝塞尔）冒烟验证 */
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

  await page.goto('http://localhost:5173/editor/1', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2500)

  // 打开路线 tab，选中 ROUTE_A → 边详情渲染（含曲线）
  await page.locator('.el-tabs__item', { hasText: '路线' }).click()
  await page.waitForTimeout(400)
  await page.locator('.path-item', { hasText: 'ROUTE_A' }).click()
  await page.waitForTimeout(600)
  await page.screenshot({ path: path.join(OUT, '10-path-render.png') })

  // 编辑图形模式：显示控制点
  await page.locator('.path-item', { hasText: 'ROUTE_A' }).locator('button[aria-label], button').nth(1).click()
  await page.waitForTimeout(800)
  await page.screenshot({ path: path.join(OUT, '11-path-edit.png') })
  const edgeRows = await page.evaluate(() => [...document.querySelectorAll('.edge-item')].map((e) => e.textContent.trim().replace(/\s+/g, ' ')))
  console.log('--- EDGES ---')
  console.log(edgeRows.join(' | '))

  // 点击 P1 → P2 追加一条边（退出模式下重进编辑以重置）
  const canvasBox = await page.locator('.map-canvas canvas').first().boundingBox()
  const p1 = await page.evaluate(() => {
    // 通过标签找 P1 屏幕位置
    const els = [...document.querySelectorAll('.nd-label-inner')]
    const el = els.find((e) => e.textContent === 'P1')
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { x: r.x + r.width / 2, y: r.y + 30 }
  })
  if (p1) {
    await page.mouse.click(p1.x, p1.y)
    await page.waitForTimeout(300)
    // 拖动起点后点 P3（标签下方圆盘中心）
    const p3 = await page.evaluate(() => {
      const els = [...document.querySelectorAll('.nd-label-inner')]
      const el = els.find((e) => e.textContent === 'P3')
      if (!el) return null
      const r = el.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + 30 }
    })
    if (p3) await page.mouse.click(p3.x, p3.y)
    await page.waitForTimeout(400)
  }
  await page.screenshot({ path: path.join(OUT, '12-path-append.png') })
  const edgeRows2 = await page.evaluate(() => [...document.querySelectorAll('.edge-item')].length)
  console.log('--- EDGE COUNT AFTER APPEND ---', edgeRows2)

  console.log('--- ERRORS ---')
  console.log(errors.length ? errors.join('\n') : '(none)')
  await browser.close()
}
main().catch((e) => { console.error('FAILED', e); process.exit(1) })
