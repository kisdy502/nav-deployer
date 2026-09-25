/* 放弃建图链路：开始建图 → 建图中出现放弃按钮 → 放弃 → save-map 下发 → mock 完成 → mode 回 NAVIGATION */
const { chromium } = require('playwright-core')

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
  page.on('response', (r) => { if (r.url().includes('/api/') && r.request().method() !== 'GET') api.push(`${r.status()} ${r.request().method()} ${r.url().replace('http://localhost:5174', '')}`) })

  await page.goto('http://localhost:5174/', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2500)

  // 打开建图抽屉（顶栏「建图」按钮）
  await page.locator('.browse-header button', { hasText: '建图' }).click()
  await page.waitForTimeout(600)

  // 开始建图
  await page.locator('button', { hasText: '开始建图' }).click()
  await page.waitForTimeout(300)
  await page.locator('.el-message-box button', { hasText: '确认' }).click().catch(() => page.locator('.el-message-box button', { hasText: '确定' }).click())
  await page.waitForTimeout(2200) // mock 1.5s 后 SUCCEEDED 且 mode=MAPPING

  const abandonVisible = await page.locator('button', { hasText: '放弃建图' }).count()
  console.log('--- ABANDON BUTTON VISIBLE ---', abandonVisible > 0)

  // 放弃建图
  await page.locator('button', { hasText: '放弃建图' }).click()
  await page.waitForTimeout(300)
  await page.locator('.el-message-box button', { hasText: '放弃建图' }).last().click()
  await page.waitForTimeout(4000) // mock RELOCATING 1.5s + SUCCEEDED 3s

  await page.screenshot({ path: 'scripts/shots/40-abandon-mapping.png' })
  const drawerText = await page.evaluate(() => document.querySelector('.el-drawer')?.textContent?.replace(/\s+/g, ' ').slice(0, 200))
  console.log('--- DRAWER TEXT ---', drawerText)

  const mode = await page.evaluate(async () => {
    const s = await (await fetch('/api/v1/robot/snapshot')).json()
    return s.data.status.mode
  })
  console.log('--- ROBOT MODE AFTER ABANDON (expect NAVIGATION) ---', mode)
  const maps = await page.evaluate(async () => {
    const m = await (await fetch('/api/v1/nav-maps')).json()
    return m.data.map((x) => `${x.map_name}:${x.status}`).join(' | ')
  })
  console.log('--- MAPS AFTER ABANDON ---', maps)

  console.log('--- MUTATING API ---', api.join(' ; ') || '(none)')
  console.log('--- ERRORS ---', errors.length ? errors.join('\n') : '(none)')
  await browser.close()
}
main().catch((e) => { console.error('FAILED', e); process.exit(1) })
