import type { InstanceAction, InstanceStatus } from '../types/api'
import { hasPermission } from './permissions'

export function allowedActions(status: InstanceStatus, permissions: readonly string[]): InstanceAction[] {
  if (!hasPermission(permissions, 'instance:operate')) return []
  if (status === 'RUNNING') return ['STOP', 'RESTART', 'DELETE']
  if (status === 'STOPPED') return ['START', 'DELETE']
  if (status === 'FAILED' || status === 'DELETE_FAILED') return ['DELETE']
  return []
}
