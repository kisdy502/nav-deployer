/* 验证路线点击选中：高亮 + 辅助点 */
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

  // P1→P2 水平段中点（标签在点上方约 22px，取标签中心 y+22 = 点位）
  const mid = await page.evaluate(() => {
    const find = (n) => {
      const el = [...document.querySelectorAll('.nd-label-inner')].find((e) => e.textContent === n)
      const r = el.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 + 22 }
    }
    const a = find('P1')
    const b = find('P2')
    return { x: (a.x + b.x) / 2, y: a.y }
  })
  await page.mouse.click(mid.x, mid.y)
  await page.waitForTimeout(500)
  await page.screenshot({ path: path.join(OUT, '33-path-selected.png') })
  const panel = await page.evaluate(() => document.querySelector('.browse-right')?.textContent?.replace(/\s+/g, ' ').slice(0, 100))
  console.log('--- PANEL AFTER PATH CLICK ---', panel)

  // 右键路线 → 浏览态菜单应含「循线」
  await page.mouse.click(mid.x, mid.y, { button: 'right' })
  await page.waitForTimeout(300)
  const ctxItems = await page.evaluate(() => [...document.querySelectorAll('.ctx-item')].map((e) => e.textContent.trim()))
  console.log('--- BROWSE CTX (path) ---', JSON.stringify(ctxItems))

  console.log('--- ERRORS ---')
  console.log(errors.length ? errors.join('\n') : '(none)')
  await browser.close()
}
main().catch((e) => { console.error('FAILED', e); process.exit(1) })
