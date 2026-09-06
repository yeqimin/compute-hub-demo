export type TagType = 'success' | 'warning' | 'danger' | 'info' | 'primary'
const states: Record<string, { label: string; type: TagType }> = {
  REQUESTED: { label: '已申请', type: 'info' }, CREATING: { label: '创建中', type: 'primary' },
  RUNNING: { label: '运行中', type: 'success' }, STOPPING: { label: '停止中', type: 'warning' },
  STOPPED: { label: '已停止', type: 'info' }, STARTING: { label: '启动中', type: 'primary' },
  RESTARTING: { label: '重启中', type: 'primary' }, DELETING: { label: '删除中', type: 'warning' },
  DELETED: { label: '已删除', type: 'info' }, FAILED: { label: '失败', type: 'danger' },
  DELETE_FAILED: { label: '删除失败', type: 'danger' }, UNKNOWN: { label: '待对账', type: 'warning' },
  PENDING: { label: '待处理', type: 'info' }, PUBLISHED: { label: '已投递', type: 'primary' },
  PROCESSING: { label: '处理中', type: 'primary' }, RETRY_WAIT: { label: '等待重试', type: 'warning' },
  DEAD: { label: '已终止', type: 'danger' }, DISPATCHING: { label: '调度中', type: 'primary' },
  READY: { label: '待投递', type: 'info' }, WAITING_CALLBACK: { label: '等待回调', type: 'primary' },
  SUCCEEDED: { label: '已成功', type: 'success' }, SUCCESS: { label: '成功', type: 'success' },
  HEALTHY: { label: '健康', type: 'success' }, READY_NODE: { label: '就绪', type: 'success' },
}
export const statusMeta = (status: string) => states[status] ?? { label: status || '-', type: 'info' as TagType }
