import { describe, expect, it } from 'vitest'
import { statusMeta } from './status'

describe('statusMeta', () => {
  it('explains UNKNOWN as a frozen-funds reconciliation state', () => {
    expect(statusMeta('UNKNOWN')).toEqual({ label: '待对账', type: 'warning' })
  })

  it('maps running instances to success', () => {
    expect(statusMeta('RUNNING')).toEqual({ label: '运行中', type: 'success' })
  })
})
