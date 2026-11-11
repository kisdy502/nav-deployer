const KEY = 'nd-theme'

export function isDark(): boolean {
  return document.documentElement.classList.contains('dark')
}

export function applyTheme(dark: boolean) {
  document.documentElement.classList.toggle('dark', dark)
  localStorage.setItem(KEY, dark ? 'dark' : 'light')
}

export function initTheme() {
  applyTheme(localStorage.getItem(KEY) === 'dark')
}

export function toggleTheme() {
  applyTheme(!isDark())
}
