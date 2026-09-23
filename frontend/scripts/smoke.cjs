/* 无头浏览器冒烟测试：mock 模式下走 首页 → 编辑器，收集控制台错误并截图 */
const { chromium } = require('playwright-core')
const path = require('node:path')

const OUT = path.join(__dirname, 'shots')
const BASE = 'http://localhost:5173'

async function main() {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true,
    args: ['--no-sandbox', '--disable-gpu', '--use-gl=swiftshader', '--enable-unsafe-swiftshader'],
  })
  const page = await browser.newPage({ viewport: { width: 1600, height: 950 } })

  const errors = []
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(`[console.error] ${msg.text()}`)
  })
  page.on('pageerror', (err) => errors.push(`[pageerror] ${err.message}`))
  page.on('requestfailed', (req) => errors.push(`[requestfailed] ${req.method()} ${req.url()} ${req.failure()?.errorText}`))

  // ---------- 首页 ----------
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2500)
  await page.screenshot({ path: path.join(OUT, '01-home.png') })
  const homeText = await page.evaluate(() => document.body.innerText.slice(0, 600))
  console.log('--- HOME TEXT ---')
  console.log(homeText)

  // ---------- 进入编辑器 ----------
  const editBtn = page.locator('button', { hasText: '编辑部署' }).first()
  await editBtn.click()
  await page.waitForTimeout(3000)
  await page.screenshot({ path: path.join(OUT, '02-editor.png') })
  const editorText = await page.evaluate(() => document.body.innerText.slice(0, 900))
  console.log('--- EDITOR TEXT ---')
  console.log(editorText)

  // SSE 位姿是否更新（状态栏有具体位姿数字且非"未知"）
  const pose1 = await page.evaluate(() => document.body.innerText.match(/x -?[\d.]+\s+y -?[\d.]+/)?.[0] ?? 'NO_POSE')
  await page.waitForTimeout(2500)
  const pose2 = await page.evaluate(() => document.body.innerText.match(/x -?[\d.]+\s+y -?[\d.]+/)?.[0] ?? 'NO_POSE')
  console.log(`--- SSE POSE --- first="${pose1}" second="${pose2}" updated=${pose1 !== pose2}`)

  // ---------- 新建点位（工具栏 → 新建点位 → 点击画布中心） ----------
  await page.locator('.el-radio-button', { hasText: '新建点位' }).click()
  await page.waitForTimeout(300)
  const canvas = page.locator('.map-canvas canvas').first()
  const box = await canvas.boundingBox()
  console.log('canvas box:', JSON.stringify(box))
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2)
  await page.waitForTimeout(600)
  await page.fill('.el-dialog input[placeholder*="图内唯一"]', 'T1')
  await page.screenshot({ path: path.join(OUT, '03-place-point-dialog.png') })
  await page.locator('.el-dialog button', { hasText: '创建' }).last().click()
  await page.waitForTimeout(800)
  await page.screenshot({ path: path.join(OUT, '04-point-created.png') })
  const pointCount = await page.evaluate(() => {
    const items = [...document.querySelectorAll('.point-item .point-code')]
    return items.map((e) => e.textContent.trim().split(/\s/)[0]).join(',')
  })
  console.log('--- POINTS ---', pointCount)

  // ---------- 机器人实时（循线演示：点击 P1 的导航按钮） ----------
  const navBtn = page.locator('.point-item', { hasText: 'P1' }).locator('button[title*="导航"], .point-ops button').nth(1)
  await navBtn.click()
  await page.waitForTimeout(1200)
  await page.screenshot({ path: path.join(OUT, '05-move-task.png') })
  await page.waitForTimeout(3500)
  await page.screenshot({ path: path.join(OUT, '06-move-task-later.png') })
  const taskText = await page.evaluate(() => {
    const tag = document.querySelector('.status-right .el-tag')
    return tag ? tag.textContent.trim() : 'NO_TASK_TAG'
  })
  console.log('--- MOVE TASK ---', taskText)

  console.log('--- ERRORS ---')
  console.log(errors.length ? errors.join('\n') : '(none)')

  await browser.close()
}

main().catch((e) => {
  console.error('TEST FAILED:', e)
  process.exit(1)
})
