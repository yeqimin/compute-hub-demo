import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import Access from './Access.vue'
import Billing from './Billing.vue'
import Products from './Products.vue'
import Resources from './Resources.vue'

const { get, post, put } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('../api', () => ({ api: { get, post, put } }))

const productPage = { items: [{ id: 1, sku: 'GPU-H100-1', name: 'H100 训练型', gpuModel: 'H100', gpuCount: 1, cpuCores: 32, memoryGb: 128, priceCent: 50000, enabled: true }], total: 1, page: 1, size: 20 }
const elementStubs = {
  'el-table': { template: '<div><slot /></div>' }, 'el-table-column': { template: '<div><slot :row="{}" /></div>' },
  'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' }, 'el-button': { template: '<button v-bind="$attrs"><slot /></button>' },
}

async function mountProducts(permissions: string[], routeQuery: Record<string, string> = {}) {
  localStorage.setItem('compute-user', JSON.stringify({ id: 3, tenantId: 2, roles: ['VIEWER'], permissions }))
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/products', component: Products }] })
  await router.push({ path: '/products', query: routeQuery })
  await router.isReady()
  const wrapper = mount(Products, { global: { plugins: [createPinia(), router], stubs: elementStubs } })
  return { wrapper, router }
}

async function mountAdminView(component: any, path: string, routeQuery: Record<string, string>, user: Record<string, unknown>) {
  localStorage.setItem('compute-user', JSON.stringify(user))
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path, component }, { path: '/instances', component: { template: '<div />' } }] })
  await router.push({ path, query: routeQuery })
  await router.isReady()
  const wrapper = mount(component, { global: { plugins: [createPinia(), router], stubs: elementStubs } })
  return { wrapper, router }
}

describe('administration CRUD', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    get.mockReset().mockResolvedValue(productPage)
    post.mockReset().mockResolvedValue({})
    put.mockReset().mockResolvedValue({})
  })

  it('submits product keyword and requested server page', async () => {
    const { wrapper } = await mountProducts(['product:manage'])
    await flushPromises()
    await wrapper.get('input').setValue('H100')
    await wrapper.get('input').trigger('change')
    await flushPromises()

    expect(get).toHaveBeenLastCalledWith('/products', { params: expect.objectContaining({ keyword: 'H100', page: 1, size: 20 }) })
  })

  it('restores product filters from URL and writes searches back to it', async () => {
    get.mockImplementation((_path, config) => Promise.resolve({ ...productPage, page: config.params.page, size: config.params.size }))
    const { wrapper, router } = await mountProducts(['product:manage'], { keyword: 'A10', enabled: 'false', page: '2', size: '50' })
    await flushPromises()

    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith('/products', { params: { keyword: 'A10', enabled: false, page: 2, size: 50 } })

    wrapper.findComponent({ name: 'ElInput' }).vm.$emit('update:modelValue', 'H100')
    await wrapper.vm.$nextTick()
    await wrapper.findAll('button').find(button => button.text() === '搜索')!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toMatchObject({ keyword: 'H100', enabled: 'false', page: '1', size: '50' })
  })

  it('does not render mutation controls for a viewer', async () => {
    const { wrapper } = await mountProducts([])
    await flushPromises()

    expect(wrapper.findAll('[data-test=mutation]')).toHaveLength(0)
  })

  it('restores access filters and paging from the URL', async () => {
    get.mockImplementation((path) => Promise.resolve(path === '/users' ? { items: [], total: 0, page: 2, size: 50 } : []))
    await mountAdminView(Access, '/access', { keyword: '查询用户', page: '2', size: '50' }, { id: 2, tenantId: 2, roles: ['TENANT_ADMIN'], permissions: [] })
    await flushPromises()

    expect(get).toHaveBeenCalledWith('/users', { params: { keyword: '查询用户', page: 2, size: 50 } })
  })

  it('restores billing tenant, filters and paging from the URL', async () => {
    get.mockImplementation((path, config) => {
      if (path === '/tenants') return Promise.resolve({ items: [{ id: 2, code: 'TENANT-A', name: '星云租户' }], total: 1, page: 1, size: 100 })
      if (path === '/wallet') return Promise.resolve({ availableCent: 10000, frozenCent: 0 })
      if (path === '/billing/ledgers') return Promise.resolve({ items: [], total: 0, page: config.params.page, size: config.params.size })
      return Promise.resolve([])
    })
    await mountAdminView(Billing, '/billing', { tenantId: '2', type: 'DEDUCT', bizNo: 'ORD-1', startTime: '2026-09-01T00:00:00', endTime: '2026-09-02T00:00:00', page: '2', size: '50' }, { id: 1, tenantId: null, roles: ['PLATFORM_ADMIN'], permissions: ['billing:recharge'] })
    await flushPromises()

    expect(get).toHaveBeenCalledWith('/billing/ledgers', { params: { tenantId: 2, type: 'DEDUCT', bizNo: 'ORD-1', startTime: '2026-09-01T00:00:00', endTime: '2026-09-02T00:00:00', page: 2, size: 50 } })
  })

  it('restores resource filters and selected cluster from the URL', async () => {
    get.mockImplementation((path) => Promise.resolve(path === '/clusters' ? [{ id: 2, code: 'BJ-GPU', name: '北京集群', region: '北京', status: 'UNHEALTHY', nodeCount: 0, gpuTotal: 0, gpuAllocated: 0 }] : []))
    const { wrapper } = await mountAdminView(Resources, '/resources', { keyword: '北京', status: 'UNHEALTHY', clusterId: '2' }, { id: 3, tenantId: 2, roles: ['VIEWER'], permissions: [] })
    await flushPromises()

    expect(wrapper.findComponent({ name: 'ElInput' }).props('modelValue')).toBe('北京')
    expect(wrapper.findComponent({ name: 'ElSelect' }).props('modelValue')).toBe('UNHEALTHY')
    expect(get).toHaveBeenCalledWith('/clusters/2/nodes')
  })
})
