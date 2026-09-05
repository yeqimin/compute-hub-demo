import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Instances from './Instances.vue'

const { get, post, del, idemKey, confirm } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
  idemKey: vi.fn(() => 'idem-instance-12'),
  confirm: vi.fn(() => Promise.resolve()),
}))

vi.mock('../api', () => ({ api: { get, post, delete: del }, idemKey }))
vi.mock('element-plus', async (importOriginal) => ({
  ...await importOriginal<typeof import('element-plus')>(),
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm },
}))

const page = {
  items: [{ id: 7, instanceNo: 'I-007', name: 'gpu-demo', productName: 'A100', clusterName: '华东', status: 'RUNNING', tenantId: 2, createdAt: '2026-09-06T10:00:00' }],
  total: 1, page: 1, size: 20,
}

const FiltersStub = {
  props: ['modelValue'],
  emits: ['update:modelValue', 'search', 'reset'],
  template: `<div>
    <input data-test="status-filter" :value="modelValue.status" @input="$emit('update:modelValue', {...modelValue, status: $event.target.value})" />
    <input data-test="search" :value="modelValue.keyword" @input="$emit('update:modelValue', {...modelValue, keyword: $event.target.value})" />
    <button data-test="query" @click="$emit('search')">查询</button>
  </div>`,
}

const TableStub = {
  props: ['rows'],
  emits: ['selection-change', 'action', 'detail'],
  template: `<div>
    <button data-test="select-all" @click="$emit('selection-change', rows)">选择</button>
    <button data-test="delete" @click="$emit('action', { action: 'DELETE', row: rows[0] })">删除</button>
  </div>`,
}

const mountView = async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/instances', component: Instances }] })
  await router.push('/instances')
  await router.isReady()
  const wrapper = mount(Instances, {
    global: {
      plugins: [createPinia(), router],
      stubs: {
        InstanceFilters: FiltersStub,
        InstanceTable: TableStub,
        InstanceDetailDrawer: true,
        'el-button': { template: '<button><slot /></button>' },
        'el-pagination': true,
        'el-alert': true,
        'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' },
      },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('Instances console', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('compute-user', JSON.stringify({ id: 1, tenantId: 2, roles: ['TENANT_ADMIN'], permissions: ['instance:create', 'instance:operate'] }))
    get.mockReset().mockImplementation((url: string) => {
      if (url === '/instances') return Promise.resolve(page)
      if (url === '/products' || url === '/clusters' || url === '/tenants') return Promise.resolve([])
      return Promise.resolve({})
    })
    post.mockReset().mockResolvedValue({})
    del.mockReset().mockResolvedValue({})
    idemKey.mockClear()
    confirm.mockClear().mockResolvedValue(undefined)
  })

  afterEach(() => vi.useRealTimers())

  it('keeps filters in the URL and requests server paging', async () => {
    const { wrapper, router } = await mountView()
    await wrapper.find('[data-test=status-filter]').setValue('RUNNING')
    await wrapper.find('[data-test=search]').setValue('gpu-demo')
    await wrapper.find('[data-test=query]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toMatchObject({ status: 'RUNNING', keyword: 'gpu-demo', page: '1' })
    expect(get).toHaveBeenLastCalledWith('/instances', { params: expect.objectContaining({ status: 'RUNNING', keyword: 'gpu-demo', page: 1, size: 20 }) })
  })

  it('submits an idempotent create request', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test=create]').trigger('click')
    await wrapper.find('[data-test=create-submit]').trigger('click')
    await flushPromises()

    expect(post).toHaveBeenCalledWith('/instances', expect.objectContaining({ scenario: 'SUCCESS' }), { headers: { 'Idempotency-Key': 'idem-instance-12' } })
  })

  it('requires an explicit delete confirmation before an idempotent request', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test=delete]').trigger('click')
    await wrapper.find('[data-test=action-confirm]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('DELETE'), expect.any(String), expect.any(Object))
    expect(del).toHaveBeenCalledWith('/instances/7', { params: { scenario: 'SUCCESS' }, headers: { 'Idempotency-Key': 'idem-instance-12' } })
  })

  it('shows every batch item result, including skipped and failed items', async () => {
    post.mockResolvedValueOnce({
      successCount: 1, skippedCount: 1, failedCount: 1,
      items: [
        { instanceId: 7, code: 'SUCCESS', message: '已提交' },
        { instanceId: 8, code: 'SKIPPED', message: '状态不匹配' },
        { instanceId: 9, code: 'FAILED', message: '余额不足' },
      ],
    })
    const { wrapper } = await mountView()
    await wrapper.find('[data-test=select-all]').trigger('click')
    await wrapper.find('[data-test=batch-STOP]').trigger('click')
    await wrapper.find('[data-test=action-confirm]').trigger('click')
    await flushPromises()

    expect(post).toHaveBeenCalledWith('/instances/batch-actions', expect.objectContaining({ action: 'STOP', instanceIds: [7] }), { headers: { 'Idempotency-Key': 'idem-instance-12' } })
    expect(wrapper.findAll('[data-test=batch-result]')).toHaveLength(3)
    expect(wrapper.text()).toContain('状态不匹配')
    expect(wrapper.text()).toContain('余额不足')
  })

  it('does not let polling overwrite an in-progress filter edit and clears polling on unmount', async () => {
    vi.useFakeTimers()
    const { wrapper } = await mountView()
    get.mockClear()
    await wrapper.find('[data-test=status-filter]').setValue('RUNNING')
    await vi.advanceTimersByTimeAsync(5000)
    expect(get).not.toHaveBeenCalledWith('/instances', expect.anything())

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(5000)
    expect(get).not.toHaveBeenCalled()
  })
})
