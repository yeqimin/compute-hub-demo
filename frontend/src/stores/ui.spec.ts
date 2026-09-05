import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUiStore } from './ui'

describe('UI theme preferences', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false })))
    setActivePinia(createPinia())
  })

  it('persists an explicit dark theme and applies it to the document', () => {
    const ui = useUiStore()

    ui.setTheme('dark')

    expect(localStorage.getItem('compute-theme')).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  it('resolves system theme from the current media preference', () => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })))
    const ui = useUiStore()

    ui.setTheme('system')

    expect(ui.resolvedTheme).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
  })
})
