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
  const api = []
  page.on('pageerror', (e) => errors.push(`[pageerror] ${e.message}`))
  page.on('response', (r) => { if (r.url().includes('/api/') && r.request().method() !== 'GET') api.push(`${r.status()} ${r.request().method()} ${r.url()}`) })

  await page.goto('http://localhost:5173/editor/1', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2500)

  // 进入 ROUTE_A 编辑
  await page.locator('.el-tabs__item', { hasText: '路线' }).click()
  await page.locator('.path-item', { hasText: 'ROUTE_A' }).locator('button').nth(1).click()
  await page.waitForTimeout(600)

  // 链式追加：终点是 P3，直接点击 T1 → 自动生成 P3→T1
  const labelXY = async (name) => {
    return page.evaluate((n) => {
      const el = [...document.querySelectorAll('.nd-label-inner')].find((e) => e.textContent === n)
      if (!el) return null
      const r = el.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + 30 }
    }, name)
  }
  const t1 = await labelXY('T1')
  await page.mouse.click(t1.x, t1.y)
  await page.waitForTimeout(400)
  const edgesAfterAppend = await page.evaluate(() => [...document.querySelectorAll('.edge-item')].map((e) => e.textContent.trim().replace(/\s+/g, ' ')))
  console.log('--- EDGES AFTER APPEND ---', JSON.stringify(edgesAfterAppend))

  // 保存图形
  await page.locator('button', { hasText: '保存图形' }).click()
  await page.waitForTimeout(1000)
  await page.screenshot({ path: path.join(OUT, '13-path-saved.png') })

  // 再部署
  await page.locator('.path-item', { hasText: 'ROUTE_A' }).click()
  await page.waitForTimeout(400)
  const deployBtn = page.locator('button', { hasText: '部署' }).last()
  if (await deployBtn.count()) await deployBtn.click()
  await page.waitForTimeout(800)

  console.log('--- MUTATING API CALLS ---')
  console.log(api.join('\n') || '(none)')
  console.log('--- ERRORS ---')
  console.log(errors.length ? errors.join('\n') : '(none)')
  await browser.close()
}
main().catch((e) => { console.error('FAILED', e); process.exit(1) })
