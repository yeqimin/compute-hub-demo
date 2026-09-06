import { describe, expect, it, vi } from 'vitest'
import { usePageQuery } from './usePageQuery'

const deferred = <T>() => {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((onResolve, onReject) => {
    resolve = onResolve
    reject = onReject
  })
  return { promise, resolve, reject }
}

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

  it('keeps the newest page when an older request resolves last', async () => {
    const first = deferred<{ items: Array<{ id: number }>; total: number; page: number; size: number }>()
    const latest = deferred<{ items: Array<{ id: number }>; total: number; page: number; size: number }>()
    const fetchPage = vi.fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(latest.promise)
    const query = usePageQuery(fetchPage, {}, { page: 1, size: 20 })

    const firstLoad = query.load()
    const latestLoad = query.changePage(2)
    latest.resolve({ items: [{ id: 2 }], total: 2, page: 2, size: 20 })
    await latestLoad
    first.resolve({ items: [{ id: 1 }], total: 1, page: 1, size: 20 })
    await firstLoad

    expect(query.items.value).toEqual([{ id: 2 }])
    expect(query.total.value).toBe(2)
    expect(query.page.value).toBe(2)
    expect(query.loading.value).toBe(false)
  })

  it('ignores an older request error while the latest request is still loading', async () => {
    const first = deferred<{ items: Array<{ id: number }>; total: number; page: number; size: number }>()
    const latest = deferred<{ items: Array<{ id: number }>; total: number; page: number; size: number }>()
    const fetchPage = vi.fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(latest.promise)
    const query = usePageQuery(fetchPage, {}, { page: 1, size: 20 })

    const firstLoad = query.load()
    const latestLoad = query.changePage(2)
    first.reject(new Error('旧请求失败'))
    await firstLoad

    expect(query.error.value).toBeNull()
    expect(query.loading.value).toBe(true)
    latest.resolve({ items: [{ id: 2 }], total: 2, page: 2, size: 20 })
    await latestLoad

    expect(query.error.value).toBeNull()
    expect(query.loading.value).toBe(false)
  })
})
