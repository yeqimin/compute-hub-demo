import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import InstanceTable from './InstanceTable.vue'

describe('InstanceTable', () => {
  it('keeps the real table loading and empty state while gating lifecycle actions by permission', () => {
    const empty = mount(InstanceTable, { props: { rows: [], loading: true, permissions: [] } })
    expect(empty.findComponent({ name: 'ElTable' }).props('data')).toEqual([])
    expect(empty.find('.el-loading-mask').exists()).toBe(true)
    expect(empty.text()).toContain('暂无符合条件的实例')

    const running = mount(InstanceTable, {
      props: { rows: [{ id: 7, instanceNo: 'I-007', name: 'gpu-demo', productName: 'A100', clusterName: '华东', status: 'RUNNING', tenantId: 2, createdAt: '2026-09-06T10:00:00' }], loading: false, permissions: [] },
    })
    expect(running.find('[data-test=action-STOP]').exists()).toBe(false)
    expect(running.find('[data-test=action-DELETE]').exists()).toBe(false)
  })
})
