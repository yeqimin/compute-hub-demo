import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

  it('updates the document when the system color preference changes', () => {
    const listeners = new Set<(event: MediaQueryListEvent) => void>()
    const mediaQuery = {
      matches: false,
      addEventListener: vi.fn((_type: string, listener: (event: MediaQueryListEvent) => void) => listeners.add(listener)),
      removeEventListener: vi.fn((_type: string, listener: (event: MediaQueryListEvent) => void) => listeners.delete(listener)),
    }
    vi.stubGlobal('matchMedia', vi.fn(() => mediaQuery))
    const ui = useUiStore()

    ui.setTheme('system')
    mediaQuery.matches = true
    listeners.forEach((listener) => listener({ matches: true } as MediaQueryListEvent))

    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  it('removes the system color listener after switching to an explicit theme', () => {
    const listeners = new Set<(event: MediaQueryListEvent) => void>()
    const mediaQuery = {
      matches: false,
      addEventListener: vi.fn((_type: string, listener: (event: MediaQueryListEvent) => void) => listeners.add(listener)),
      removeEventListener: vi.fn((_type: string, listener: (event: MediaQueryListEvent) => void) => listeners.delete(listener)),
    }
    vi.stubGlobal('matchMedia', vi.fn(() => mediaQuery))
    const ui = useUiStore()

    ui.setTheme('system')
    ui.setTheme('dark')
    mediaQuery.matches = false
    listeners.forEach((listener) => listener({ matches: false } as MediaQueryListEvent))

    expect(mediaQuery.removeEventListener).toHaveBeenCalledOnce()
    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  afterEach(() => vi.unstubAllGlobals())
})
