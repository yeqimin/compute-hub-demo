import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Dashboard from './Dashboard.vue'

const { get, chart } = vi.hoisted(() => ({
  get: vi.fn(),
  chart: { setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() },
}))

vi.mock('../api', () => ({ api: { get } }))
vi.mock('echarts/core', () => ({
  init: vi.fn(() => chart), use: vi.fn(), graphic: { LinearGradient: class {} },
}))
vi.mock('echarts/charts', () => ({ LineChart: {}, PieChart: {}, BarChart: {} }))
vi.mock('echarts/components', () => ({ GridComponent: {}, TooltipComponent: {}, LegendComponent: {}, TitleComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

const stubs = {
  'el-button': { template: '<button v-bind="$attrs"><slot /></button>' },
  'el-table': { template: '<div><slot /></div>' },
  'el-table-column': { template: '<div />' },
  'el-progress': true,
  'el-skeleton': { template: '<div><slot /></div>' },
  'el-empty': true,
}

const summary = {
  gpuTotal: 12, gpuAllocated: 5, gpuAvailable: 7, readyNodeCount: 3, unhealthyNodeCount: 1,
  runningInstances: 4, abnormalInstances: 2, taskSuccessRate: 87.5, abnormalTasks: 1,
  availableCent: 123456, frozenCent: 5000,
  instanceDistribution: [{ status: 'RUNNING', value: 4 }, { status: 'UNKNOWN', value: 2 }],
  topology: [{ id: 1, code: 'SH-01', name: '上海集群', status: 'READY', nodes: [{ id: 2, name: 'node-a', status: 'READY', gpuTotal: 8, gpuAllocated: 3 }] }],
}

const mountView = async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/dashboard', component: Dashboard }, { path: '/instances', component: { template: '<div />' } }, { path: '/tasks', component: { template: '<div />' } },
  ] })
  await router.push('/dashboard')
  await router.isReady()
  const wrapper = mount(Dashboard, { global: { plugins: [router], stubs } })
  return { wrapper, router }
}

describe('算力运营大屏', () => {
  beforeEach(() => {
    get.mockReset().mockImplementation((url: string) => url === '/dashboard/summary' ? Promise.resolve(summary) : Promise.resolve([{ clusterCode: 'SH-01', gpuUtilization: 60, cpuUtilization: 40, memoryUtilization: 50 }]))
  })
  afterEach(() => vi.useRealTimers())

  it('在加载期间显示骨架，并映射租户范围内的 KPI 和金额', async () => {
    let resolveSummary!: (value: typeof summary) => void
    get.mockImplementationOnce(() => new Promise<typeof summary>((resolve) => { resolveSummary = resolve }))
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-test=dashboard-loading]').exists()).toBe(true)
    resolveSummary(summary)
    await flushPromises()
    expect(wrapper.get('[data-test=gpu-total]').text()).toContain('12')
    expect(wrapper.get('[data-test=wallet-available]').text()).toContain('1,234.56')
    expect(wrapper.get('[data-test=task-success-rate]').text()).toContain('87.5%')
  })

  it('轮询刷新、手动刷新，并在卸载后清理定时器和图表', async () => {
    vi.useFakeTimers()
    const { wrapper } = await mountView()
    await flushPromises()
    await wrapper.get('[data-test=dashboard-refresh]').trigger('click')
    await flushPromises()
    expect(get).toHaveBeenCalledTimes(4)
    await vi.advanceTimersByTimeAsync(5000)
    expect(get).toHaveBeenCalledTimes(6)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(get).toHaveBeenCalledTimes(6)
    expect(chart.dispose).toHaveBeenCalled()
  })

  it('点击实例和异常任务 KPI 时带状态筛选跳转', async () => {
    const { wrapper, router } = await mountView()
    await flushPromises()
    await wrapper.get('[data-test=running-instances]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value).toMatchObject({ path: '/instances', query: { status: 'RUNNING' } })
    await router.push('/dashboard')
    await wrapper.get('[data-test=abnormal-tasks]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value).toMatchObject({ path: '/tasks', query: { state: 'UNKNOWN' } })
  })
})
