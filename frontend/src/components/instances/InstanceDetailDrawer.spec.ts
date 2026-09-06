import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import InstanceDetailDrawer from './InstanceDetailDrawer.vue'

const shellStubs = {
  'el-drawer': { template: '<section><slot name="header" /><slot /></section>' },
  'el-skeleton': { template: '<div><slot /></div>' },
  'el-descriptions': { template: '<dl><slot /></dl>' },
  'el-descriptions-item': { template: '<div><slot /></div>' },
  'el-timeline': { template: '<ol><slot /></ol>' },
  'el-timeline-item': { template: '<li><slot /></li>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-empty': { template: '<div />' },
  'el-button': { template: '<button type="button" v-bind="$attrs"><slot /></button>' },
}

describe('InstanceDetailDrawer', () => {
  const unknown = { id: 7, instanceNo: 'I-007', name: 'gpu-demo', productName: 'A100', clusterName: '华东', status: 'UNKNOWN' as const, tenantId: 2, taskId: 42, createdAt: '2026-09-06T10:00:00' }

  it('only exposes manual reconciliation to users with instance:retry', async () => {
    const denied = mount(InstanceDetailDrawer, { props: { modelValue: true, detail: unknown, permissions: [] }, global: { stubs: shellStubs } })
    expect(denied.find('[data-test=reconcile]').exists()).toBe(false)

    const allowed = mount(InstanceDetailDrawer, { props: { modelValue: true, detail: unknown, permissions: ['instance:retry'] }, global: { stubs: shellStubs } })
    await allowed.get('[data-test=reconcile]').trigger('click')
    expect(allowed.emitted('reconcile')).toEqual([[42]])
  })
})
