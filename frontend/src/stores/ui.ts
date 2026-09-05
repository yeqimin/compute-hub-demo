import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export type ThemePreference = 'light' | 'dark' | 'system'

const THEME_KEY = 'compute-theme'

const readTheme = (): ThemePreference => {
  const saved = localStorage.getItem(THEME_KEY)
  return saved === 'light' || saved === 'dark' || saved === 'system' ? saved : 'system'
}

const systemTheme = () => window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'

export const useUiStore = defineStore('ui', () => {
  const theme = ref<ThemePreference>(readTheme())
  const resolvedTheme = computed<'light' | 'dark'>(() => theme.value === 'system' ? systemTheme() : theme.value)
  let mediaQuery: MediaQueryList | undefined
  let onSystemThemeChange: ((event: MediaQueryListEvent) => void) | undefined

  const stopSystemThemeListener = () => {
    if (mediaQuery && onSystemThemeChange) mediaQuery.removeEventListener?.('change', onSystemThemeChange)
    mediaQuery = undefined
    onSystemThemeChange = undefined
  }

  const syncSystemThemeListener = () => {
    stopSystemThemeListener()
    if (theme.value !== 'system') return
    mediaQuery = window.matchMedia?.('(prefers-color-scheme: dark)')
    if (!mediaQuery) return
    onSystemThemeChange = () => {
      document.documentElement.dataset.theme = systemTheme()
    }
    mediaQuery.addEventListener?.('change', onSystemThemeChange)
  }

  const applyTheme = () => {
    syncSystemThemeListener()
    document.documentElement.dataset.theme = resolvedTheme.value
  }

  const setTheme = (nextTheme: ThemePreference) => {
    theme.value = nextTheme
    localStorage.setItem(THEME_KEY, nextTheme)
    applyTheme()
  }

  return { theme, resolvedTheme, applyTheme, setTheme }
})
