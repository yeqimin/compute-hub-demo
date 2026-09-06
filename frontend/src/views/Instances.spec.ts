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

const TableStub = {
  props: ['rows'],
  emits: ['selection-change', 'action', 'detail'],
  template: `<div>
    <button data-test="select-all" @click="$emit('selection-change', rows)">选择</button>
    <button data-test="delete" @click="$emit('action', { action: 'DELETE', row: rows[0] })">删除</button>
    <button data-test="detail" @click="$emit('detail', rows[0])">详情</button>
  </div>`,
}

const mountView = async (realDrawer = false) => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/instances', component: Instances }] })
  await router.push('/instances')
  await router.isReady()
  const wrapper = mount(Instances, {
    global: {
      plugins: [createPinia(), router],
      stubs: {
        InstanceTable: TableStub,
        ...(realDrawer ? {} : { InstanceDetailDrawer: true }),
        'el-button': { template: '<button v-bind="$attrs"><slot /></button>' },
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
    const filters = wrapper.getComponent({ name: 'InstanceFilters' })
    filters.vm.$emit('update:modelValue', { keyword: 'gpu-demo', status: 'RUNNING' })
    filters.vm.$emit('search')
    await flushPromises()

    expect(router.currentRoute.value.query).toMatchObject({ status: 'RUNNING', keyword: 'gpu-demo', page: '1' })
    expect(get).toHaveBeenLastCalledWith('/instances', { params: expect.objectContaining({ status: 'RUNNING', keyword: 'gpu-demo', page: 1, size: 20 }) })
  })

  it('keeps the date control and request aligned when browser navigation restores filters', async () => {
    const { wrapper, router } = await mountView()
    get.mockClear()
    await router.replace({ query: { startTime: '2026-09-03T00:00:00', endTime: '2026-09-03T23:59:59', page: '1', size: '20' } })
    await flushPromises()

    const picker = wrapper.getComponent({ name: 'InstanceFilters' }).getComponent({ name: 'ElDatePicker' })
    expect(picker.props('modelValue')).toEqual(['2026-09-03T00:00:00', '2026-09-03T23:59:59'])
    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith('/instances', { params: expect.objectContaining({ startTime: '2026-09-03T00:00:00', endTime: '2026-09-03T23:59:59' }) })
  })

  it('submits an idempotent create request', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test=create]').trigger('click')
    await wrapper.find('[data-test=create-submit]').trigger('click')
    await flushPromises()

    expect(post).toHaveBeenCalledWith('/instances', expect.objectContaining({ scenario: 'SUCCESS' }), { headers: { 'Idempotency-Key': 'idem-instance-12' } })
  })

  it('requires a platform administrator to select a tenant before creating', async () => {
    localStorage.setItem('compute-user', JSON.stringify({ id: 1, tenantId: null, roles: ['PLATFORM_ADMIN'], permissions: ['instance:create', 'instance:operate'] }))
    const { wrapper } = await mountView()
    await wrapper.find('[data-test=create]').trigger('click')
    await wrapper.find('[data-test=create-submit]').trigger('click')
    await flushPromises()

    expect(post).not.toHaveBeenCalledWith('/instances', expect.anything(), expect.anything())
    expect(wrapper.find('[data-test=create-submit]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('请选择租户')
  })

  it('requires an explicit delete confirmation before an idempotent request', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('[data-test=delete]').trigger('click')
    await wrapper.find('[data-test=action-confirm]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('DELETE'),
      expect.any(String),
      expect.objectContaining({ confirmButtonClass: 'el-button--danger' }),
    )
    expect(del).toHaveBeenCalledWith('/instances/7', { params: { scenario: 'SUCCESS' }, headers: { 'Idempotency-Key': 'idem-instance-12' } })
  })

  it('sends drawer reconciliation to the real reconcile endpoint without a retry idempotency key', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/instances') return Promise.resolve(page)
      if (url === '/instances/7') return Promise.resolve({ ...page.items[0], status: 'UNKNOWN', taskId: 42 })
      if (url === '/products' || url === '/clusters') return Promise.resolve([])
      return Promise.resolve({})
    })
    localStorage.setItem('compute-user', JSON.stringify({ id: 1, tenantId: 2, roles: ['TENANT_ADMIN'], permissions: ['instance:create', 'instance:operate', 'instance:retry'] }))
    const { wrapper } = await mountView(true)
    await wrapper.find('[data-test=detail]').trigger('click')
    await flushPromises()
    const drawer = wrapper.getComponent({ name: 'InstanceDetailDrawer' })
    drawer.vm.$emit('reconcile', 42)
    await flushPromises()

    expect(post).toHaveBeenCalledWith('/tasks/42/reconcile')
    expect(post).not.toHaveBeenCalledWith('/tasks/42/retry', expect.anything(), expect.anything())
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
    wrapper.getComponent({ name: 'InstanceFilters' }).vm.$emit('update:modelValue', { keyword: '', status: 'RUNNING' })
    await vi.advanceTimersByTimeAsync(5000)
    expect(get).not.toHaveBeenCalledWith('/instances', expect.anything())

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(5000)
    expect(get).not.toHaveBeenCalled()
  })
})
