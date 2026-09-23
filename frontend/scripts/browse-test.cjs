/* 浏览版首页回归：默认地图加载、点位选中/移动到点、路线显示、编辑地图跳转 */
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
  await page.waitForTimeout(3000)
  await page.screenshot({ path: path.join(OUT, '20-browse-home.png') })
  const header = await page.evaluate(() => document.querySelector('.browse-header')?.textContent?.replace(/\s+/g, ' ').slice(0, 200))
  console.log('--- HEADER ---', header)

  // 点击一个点位（按 CSS2D 标签定位圆盘）
  const clickPoint = async (name) => {
    const pos = await page.evaluate((n) => {
      const el = [...document.querySelectorAll('.nd-label-inner')].find((e) => e.textContent === n)
      if (!el) return null
      const r = el.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + 26 }
    }, name)
    if (pos) await page.mouse.click(pos.x, pos.y)
    return pos
  }
  await clickPoint('P2')
  await page.waitForTimeout(600)
  await page.screenshot({ path: path.join(OUT, '21-point-selected.png') })
  const panelText = await page.evaluate(() => document.querySelector('.browse-right')?.textContent?.replace(/\s+/g, ' ').slice(0, 260))
  console.log('--- RIGHT PANEL AFTER SELECT ---', panelText)

  // 移动到点
  await page.locator('button', { hasText: '移动到点' }).click()
  await page.waitForTimeout(4500)
  await page.screenshot({ path: path.join(OUT, '22-browse-nav-done.png') })
  const agvPose = await page.evaluate(() => document.body.innerText.match(/x -?[\d.]+ y -?[\d.]+ θ -?\d+°/)?.[0])
  console.log('--- AGV POSE AFTER NAV (expect near P2 1.20,-0.50) ---', agvPose)

  // 点击路线选中
  await page.mouse.click(780, 640) // P1-P2 直线段中点附近（从截图估计）
  await page.waitForTimeout(400)
  const panel2 = await page.evaluate(() => document.querySelector('.browse-right')?.textContent?.replace(/\s+/g, ' ').slice(0, 160))
  console.log('--- RIGHT PANEL AFTER CANVAS CLICK ---', panel2)

  // 编辑地图跳转
  await page.locator('button', { hasText: '编辑地图' }).first().click()
  await page.waitForTimeout(2500)
  const isEditor = await page.evaluate(() => location.pathname + ' | ' + (document.querySelector('.toolbar') ? 'TOOLBAR_OK' : 'NO_TOOLBAR'))
  console.log('--- AFTER EDIT CLICK ---', isEditor)
  await page.screenshot({ path: path.join(OUT, '23-editor-from-browse.png') })

  console.log('--- ERRORS ---')
  console.log(errors.length ? errors.join('\n') : '(none)')
  await browser.close()
}
main().catch((e) => { console.error('FAILED', e); process.exit(1) })
