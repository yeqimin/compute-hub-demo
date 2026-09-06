import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import InstanceFilters from './InstanceFilters.vue'

const FilterDatePicker = {
  props: ['modelValue'],
  template: '<div data-test="date-range">{{ JSON.stringify(modelValue) }}</div>',
}

describe('InstanceFilters', () => {
  it('reflects route-driven start and end dates after the parent model changes', async () => {
    const wrapper = mount(InstanceFilters, {
      props: {
        modelValue: { keyword: '', status: '', startTime: '2026-09-01T00:00:00', endTime: '2026-09-01T23:59:59' },
        products: [], clusters: [], tenants: [], showTenant: false,
      },
      global: { stubs: { 'el-date-picker': FilterDatePicker } },
    })

    await wrapper.setProps({ modelValue: { keyword: '', status: '', startTime: '2026-09-03T00:00:00', endTime: '2026-09-03T23:59:59' } })
    await nextTick()

    expect(wrapper.get('[data-test=date-range]').text()).toContain('2026-09-03T00:00:00')
    expect(wrapper.get('[data-test=date-range]').text()).toContain('2026-09-03T23:59:59')
  })
})
