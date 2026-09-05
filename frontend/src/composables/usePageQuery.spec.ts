import { describe, expect, it, vi } from 'vitest'
import { usePageQuery } from './usePageQuery'

describe('usePageQuery', () => {
  it('loads a page with filters and refreshes from the first page after filters change', async () => {
    const fetchPage = vi.fn()
      .mockResolvedValueOnce({ items: [{ id: 1 }], total: 3, page: 2, size: 20 })
      .mockResolvedValueOnce({ items: [{ id: 2 }], total: 1, page: 1, size: 20 })
    const query = usePageQuery(fetchPage, { status: '' }, { page: 2, size: 20 })

    await query.load()
    query.filters.status = 'RUNNING'
    await query.refresh()

    expect(fetchPage).toHaveBeenNthCalledWith(1, { status: '', page: 2, size: 20 })
    expect(fetchPage).toHaveBeenNthCalledWith(2, { status: 'RUNNING', page: 1, size: 20 })
    expect(query.items.value).toEqual([{ id: 2 }])
    expect(query.total.value).toBe(1)
    expect(query.error.value).toBeNull()
  })

  it('keeps the prior results and exposes an error when a refresh fails', async () => {
    const fetchPage = vi.fn()
      .mockResolvedValueOnce({ items: [{ id: 1 }], total: 1, page: 1, size: 20 })
      .mockRejectedValueOnce(new Error('网络不可用'))
    const query = usePageQuery(fetchPage, {}, { page: 1, size: 20 })

    await query.load()
    await query.load()

    expect(query.items.value).toEqual([{ id: 1 }])
    expect(query.error.value?.message).toBe('网络不可用')
    expect(query.loading.value).toBe(false)
  })
})
