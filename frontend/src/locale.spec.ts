import { describe, expect, it } from 'vitest'
import { appLocale } from './locale'

describe('application locale', () => {
  it('provides Chinese pagination labels for task lists', () => {
    expect(appLocale.el.pagination.total).toBe('共 {total} 条')
  })
})
