import { reactive, ref } from 'vue'
import type { Page } from '../types/api'

type PageRequest<TFilters extends object> = TFilters & { page: number; size: number }

export function usePageQuery<T, TFilters extends object>(
  fetchPage: (query: PageRequest<TFilters>) => Promise<Page<T>>,
  initialFilters: TFilters,
  initialPage = { page: 1, size: 20 },
) {
  const filters = reactive({ ...initialFilters }) as TFilters
  const items = ref<T[]>([])
  const total = ref(0)
  const page = ref(initialPage.page)
  const size = ref(initialPage.size)
  const loading = ref(false)
  const error = ref<Error | null>(null)
  let requestSequence = 0

  const load = async () => {
    const requestId = ++requestSequence
    loading.value = true
    error.value = null
    try {
      const result = await fetchPage({ ...filters, page: page.value, size: size.value })
      if (requestId !== requestSequence) return
      items.value = result.items
      total.value = result.total
      page.value = result.page
      size.value = result.size
    } catch (reason) {
      if (requestId !== requestSequence) return
      error.value = reason instanceof Error ? reason : new Error('请求失败')
    } finally {
      if (requestId === requestSequence) loading.value = false
    }
  }

  const refresh = async () => {
    page.value = 1
    await load()
  }

  const changePage = async (nextPage: number, nextSize = size.value) => {
    page.value = nextPage
    size.value = nextSize
    await load()
  }

  return { filters, items, total, page, size, loading, error, load, refresh, changePage }
}
