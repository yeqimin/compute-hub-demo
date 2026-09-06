import { describe, expect, it } from 'vitest'
import { statusMeta } from './status'

describe('statusMeta', () => {
  it('explains UNKNOWN as a frozen-funds reconciliation state', () => {
    expect(statusMeta('UNKNOWN')).toEqual({ label: '待对账', type: 'warning' })
  })

  it('maps running instances to success', () => {
    expect(statusMeta('RUNNING')).toEqual({ label: '运行中', type: 'success' })
  })

  it('labels every lifecycle state in Chinese instead of leaking enum codes', () => {
    expect(['REQUESTED', 'CREATING', 'RUNNING', 'STOPPING', 'STOPPED', 'STARTING', 'RESTARTING', 'DELETING', 'DELETED', 'FAILED', 'DELETE_FAILED', 'UNKNOWN'].map((state) => statusMeta(state).label)).toEqual([
      '已申请', '创建中', '运行中', '停止中', '已停止', '启动中', '重启中', '删除中', '已删除', '失败', '删除失败', '待对账',
    ])
  })
})
