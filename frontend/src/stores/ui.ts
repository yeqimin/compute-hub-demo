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

  const applyTheme = () => {
    document.documentElement.dataset.theme = resolvedTheme.value
  }

  const setTheme = (nextTheme: ThemePreference) => {
    theme.value = nextTheme
    localStorage.setItem(THEME_KEY, nextTheme)
    applyTheme()
  }

  return { theme, resolvedTheme, applyTheme, setTheme }
})
