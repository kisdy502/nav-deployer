const KEY = 'nd-theme'

type ThemeListener = (dark: boolean) => void
const listeners = new Set<ThemeListener>()

export function isDark(): boolean {
  return document.documentElement.classList.contains('dark')
}

export function applyTheme(dark: boolean) {
  document.documentElement.classList.toggle('dark', dark)
  localStorage.setItem(KEY, dark ? 'dark' : 'light')
  for (const fn of listeners) fn(dark)
}

/** 订阅主题变化（three 场景等非 CSS 渲染层换色用），返回取消订阅函数 */
export function onThemeChange(fn: ThemeListener): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

export function initTheme() {
  document.documentElement.classList.toggle('dark', localStorage.getItem(KEY) === 'dark')
}

export function toggleTheme() {
  applyTheme(!isDark())
}
