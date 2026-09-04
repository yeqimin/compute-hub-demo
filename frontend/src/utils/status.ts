export type TagType = 'success' | 'warning' | 'danger' | 'info' | 'primary'
const states: Record<string, { label: string; type: TagType }> = {
  REQUESTED: { label: '已申请', type: 'info' }, DISPATCHING: { label: '调度中', type: 'primary' },
  RUNNING: { label: '运行中', type: 'success' }, FAILED: { label: '失败', type: 'danger' },
  UNKNOWN: { label: '待对账', type: 'warning' }, READY: { label: '待投递', type: 'info' },
  WAITING_CALLBACK: { label: '等待回调', type: 'primary' }, SUCCESS: { label: '成功', type: 'success' },
  HEALTHY: { label: '健康', type: 'success' }, READY_NODE: { label: '就绪', type: 'success' },
}
export const statusMeta = (status: string) => states[status] ?? { label: status || '-', type: 'info' as TagType }
