import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Tasks from './Tasks.vue'

const { get, post, confirm, message } = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(), confirm: vi.fn(() => Promise.resolve()),
  message: { success: vi.fn(), error: vi.fn() },
}))

vi.mock('../api', () => ({ api: { get, post } }))
vi.mock('element-plus', async (importOriginal) => ({
  ...await importOriginal<typeof import('element-plus')>(),
  ElMessage: message,
  ElMessageBox: { confirm },
}))

const unknownTask = {
  id: 42, taskNo: 'TASK-042', commandId: 'cmd-042', tenantId: 2, tenantName: '演示租户',
  instanceId: 7, instanceNo: 'I-007', operation: 'RESTART', state: 'UNKNOWN', retryCount: 3,
  manualRetryCount: 1, lastError: '回调超时', createdAt: '2026-09-06T10:00:00',
}
const page = { items: [unknownTask], total: 1, page: 1, size: 20 }
const detail = {
  ...unknownTask, messageId: 'message-042', engineEventId: 'event-042', outboxEventId: 'outbox-042',
  outboxState: 'WAITING_CALLBACK', outboxRetryCount: 3, outboxCreatedAt: '2026-09-06T10:00:01',
  acceptedAt: '2026-09-06T10:00:02', finishedAt: null, updatedAt: '2026-09-06T10:00:03',
  nextRetryAt: '2026-09-06T10:05:00', outboxNextRetryAt: '2026-09-06T10:04:00', deadlineAt: '2026-09-06T10:10:00',
}

const elementStubs = {
  'el-button': { template: '<button v-bind="$attrs"><slot /></button>' },
  'el-select': { props: ['modelValue'], emits: ['update:modelValue'], template: '<select v-bind="$attrs" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>' },
  'el-option': { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
  'el-input': { props: ['modelValue'], emits: ['update:modelValue'], template: '<input v-bind="$attrs" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  'el-date-picker': {
    props: ['modelValue'], emits: ['change'],
    template: '<div data-test="date-picker"><span data-test="date-picker-value">{{ JSON.stringify(modelValue || []) }}</span><button data-test="pick-date-range" @click="$emit(\'change\', [\'2026-09-04T08:00:00\', \'2026-09-04T18:00:00\'])">选择时间</button></div>',
  },
  'el-pagination': true,
  'el-alert': { template: '<div><slot /></div>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-empty': true,
  'el-drawer': { template: '<section><slot name="header" /><slot /></section>' },
  'el-descriptions': { template: '<div><slot /></div>' },
  'el-descriptions-item': { props: ['label'], template: '<div>{{ label }}<slot /></div>' },
  'el-timeline': { template: '<div><slot /></div>' },
  'el-timeline-item': { template: '<div><slot /></div>' },
  'el-skeleton': { template: '<div><slot /></div>' },
}

const mountView = async (role = 'TENANT_ADMIN', permissions = ['instance:retry'], initialPath = '/tasks') => {
  localStorage.setItem('compute-user', JSON.stringify({ id: 1, tenantId: role === 'PLATFORM_ADMIN' ? null : 2, roles: [role], permissions }))
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks', component: Tasks }, { path: '/instances', component: { template: '<div />' } }] })
  await router.push(initialPath)
  await router.isReady()
  const wrapper = mount(Tasks, { global: { plugins: [createPinia(), router], stubs: elementStubs } })
  await flushPromises()
  return { wrapper, router }
}

describe('异步任务中心', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    get.mockReset().mockImplementation((url: string) => {
      if (url === '/tasks') return Promise.resolve(page)
      if (url === '/tasks/42') return Promise.resolve(detail)
      if (url === '/tenants') return Promise.resolve([{ id: 2, name: '演示租户' }])
      return Promise.resolve({})
    })
    post.mockReset().mockResolvedValue({ status: 'RETRYING' })
    confirm.mockReset().mockResolvedValue(undefined)
    message.success.mockReset(); message.error.mockReset()
  })
  afterEach(() => vi.useRealTimers())

  it('将筛选、排序和分页同步到 URL，并向服务端安全传参', async () => {
    const { wrapper, router } = await mountView('PLATFORM_ADMIN')
    const state = wrapper.get('[data-test=filter-state]')
    await state.setValue('UNKNOWN')
    await wrapper.get('[data-test=search]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toMatchObject({ state: 'UNKNOWN', page: '1', size: '20', sort: 'createdAt', order: 'desc' })
    expect(get).toHaveBeenLastCalledWith('/tasks', { params: expect.objectContaining({ state: 'UNKNOWN', page: 1, size: 20, sort: 'createdAt', order: 'desc' }) })
  })

  it('将 FAILED、DEAD 和 UNKNOWN 都统计为本页异常/待对账', async () => {
    get.mockImplementation((url: string) => url === '/tasks' ? Promise.resolve({
      items: [
        { ...unknownTask, id: 1, state: 'FAILED' },
        { ...unknownTask, id: 2, state: 'DEAD' },
        { ...unknownTask, id: 3, state: 'UNKNOWN' },
      ], total: 3, page: 1, size: 20,
    }) : Promise.resolve({}))
    const { wrapper } = await mountView()

    expect(wrapper.get('[data-test=summary-abnormal]').text()).toBe('3')
  })

  it('双向同步日期筛选：URL 回填、浏览器导航、选择与重置都反映到日期控件和请求', async () => {
    const { wrapper, router } = await mountView('TENANT_ADMIN', ['instance:retry'], '/tasks?startTime=2026-09-01T00:00:00&endTime=2026-09-02T23:59:59')
    expect(wrapper.get('[data-test=date-picker-value]').text()).toContain('2026-09-01T00:00:00')
    expect(get).toHaveBeenLastCalledWith('/tasks', { params: expect.objectContaining({ startedAt: '2026-09-01T00:00:00', endedAt: '2026-09-02T23:59:59' }) })

    await router.push('/tasks?startTime=2026-09-03T00:00:00&endTime=2026-09-03T23:59:59')
    await flushPromises()
    expect(wrapper.get('[data-test=date-picker-value]').text()).toContain('2026-09-03T00:00:00')

    await wrapper.get('[data-test=pick-date-range]').trigger('click')
    await wrapper.get('[data-test=search]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toMatchObject({ startTime: '2026-09-04T08:00:00', endTime: '2026-09-04T18:00:00' })
    expect(get).toHaveBeenLastCalledWith('/tasks', { params: expect.objectContaining({ startedAt: '2026-09-04T08:00:00', endedAt: '2026-09-04T18:00:00' }) })

    await wrapper.findAll('button').find(button => button.text() === '重置')!.trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test=date-picker-value]').text()).toBe('[]')
    expect(router.currentRoute.value.query.startTime).toBeUndefined()
    expect(router.currentRoute.value.query.endTime).toBeUndefined()
  })

  it('详情抽屉显示五阶段处理时间线和任务关联字段', async () => {
    const { wrapper } = await mountView()
    await wrapper.get('[data-test=task-detail-42]').trigger('click')
    await flushPromises()

    expect(get).toHaveBeenCalledWith('/tasks/42')
    expect(wrapper.findAll('[data-test=task-phase]')).toHaveLength(5)
    expect(wrapper.text()).toContain('消息号')
    expect(wrapper.text()).toContain('outbox-042')
  })

  it('以中文本地化格式展示列表时间，并明确显示自动重试与回调截止时间', async () => {
    const { wrapper } = await mountView()
    expect(wrapper.get('[data-test=task-list-created-at]').text()).not.toContain('T')
    expect(wrapper.get('[data-test=task-list-updated-at]').text()).toBe('-')

    await wrapper.get('[data-test=task-detail-42]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('下次自动重试')
    expect(wrapper.text()).toContain('Outbox 下次重试')
    expect(wrapper.text()).toContain('回调截止时间')
    expect(wrapper.text()).not.toContain('2026-09-06T10:05:00')
  })

  it('仅允许具有 instance:retry 权限的 UNKNOWN 任务显示处置动作', async () => {
    const denied = await mountView('VIEWER', [])
    await denied.wrapper.get('[data-test=task-detail-42]').trigger('click')
    await flushPromises()
    expect(denied.wrapper.find('[data-test=retry]').exists()).toBe(false)
    expect(denied.wrapper.find('[data-test=reconcile]').exists()).toBe(false)

    const allowed = await mountView()
    await allowed.wrapper.get('[data-test=task-detail-42]').trigger('click')
    await flushPromises()
    expect(allowed.wrapper.find('[data-test=retry]').exists()).toBe(true)
    expect(allowed.wrapper.find('[data-test=reconcile]').exists()).toBe(true)
    expect(allowed.wrapper.text()).not.toContain('redrive')
  })

  it('确认后调用真实 retry 与 reconcile 端点，并在错误中显示 traceId', async () => {
    const { wrapper } = await mountView()
    await wrapper.get('[data-test=task-detail-42]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=retry]').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalled()
    expect(post).toHaveBeenCalledWith('/tasks/42/retry')

    post.mockRejectedValueOnce(Object.assign(new Error('状态已变更'), { traceId: 'trace-42' }))
    await wrapper.get('[data-test=reconcile]').trigger('click')
    await flushPromises()
    expect(post).toHaveBeenCalledWith('/tasks/42/reconcile')
    expect(message.error).toHaveBeenCalledWith('状态已变更（追踪 ID：trace-42）')
  })

  it('筛选编辑或抽屉打开时不轮询，并在卸载后清理定时器', async () => {
    vi.useFakeTimers()
    const { wrapper } = await mountView()
    get.mockClear()
    await wrapper.get('[data-test=filter-state]').setValue('UNKNOWN')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(5000)
    expect(get).not.toHaveBeenCalled()
    await wrapper.get('[data-test=task-detail-42]').trigger('click')
    await flushPromises()
    get.mockClear()
    await vi.advanceTimersByTimeAsync(5000)
    expect(get).not.toHaveBeenCalled()
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(5000)
    expect(get).not.toHaveBeenCalled()
  })
})
