import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Products from './Products.vue'

const { get, post, put } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('../api', () => ({ api: { get, post, put } }))

const productPage = { items: [{ id: 1, sku: 'GPU-H100-1', name: 'H100 训练型', gpuModel: 'H100', gpuCount: 1, cpuCores: 32, memoryGb: 128, priceCent: 50000, enabled: true }], total: 1, page: 1, size: 20 }

function mountProducts(permissions: string[]) {
  localStorage.setItem('compute-user', JSON.stringify({ id: 3, tenantId: 2, roles: ['VIEWER'], permissions }))
  return mount(Products, { global: { plugins: [createPinia()], stubs: {
    'el-table': { template: '<div><slot /></div>' }, 'el-table-column': { template: '<div><slot :row="{}" /></div>' },
    'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' }, 'el-button': { template: '<button v-bind="$attrs"><slot /></button>' },
  } } })
}

describe('administration CRUD', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    get.mockReset().mockResolvedValue(productPage)
    post.mockReset().mockResolvedValue({})
    put.mockReset().mockResolvedValue({})
  })

  it('submits product keyword and requested server page', async () => {
    const wrapper = mountProducts(['product:manage'])
    await flushPromises()
    await wrapper.get('input').setValue('H100')
    await wrapper.get('input').trigger('change')
    await flushPromises()

    expect(get).toHaveBeenLastCalledWith('/products', { params: expect.objectContaining({ keyword: 'H100', page: 1, size: 20 }) })
  })

  it('does not render mutation controls for a viewer', async () => {
    const wrapper = mountProducts([])
    await flushPromises()

    expect(wrapper.findAll('[data-test=mutation]')).toHaveLength(0)
  })
})
