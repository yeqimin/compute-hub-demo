export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  traceId?: string
}

export interface Page<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export type InstanceStatus =
  | 'REQUESTED' | 'CREATING' | 'RUNNING' | 'STOPPING' | 'STOPPED'
  | 'STARTING' | 'RESTARTING' | 'DELETING' | 'DELETED'
  | 'FAILED' | 'DELETE_FAILED' | 'UNKNOWN'

export type TaskState =
  | 'PENDING' | 'PUBLISHED' | 'PROCESSING' | 'RETRY_WAIT'
  | 'WAITING_CALLBACK' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'DEAD'

export type InstanceAction = 'START' | 'STOP' | 'RESTART' | 'DELETE'
export type RoleCode = 'PLATFORM_ADMIN' | 'TENANT_ADMIN' | 'VIEWER'

export interface InstanceSummary {
  id: number
  instanceNo: string
  tenantId: number
  name: string
  productName: string
  clusterName: string
  status: InstanceStatus
  taskId?: number
  createdAt: string
  deletedAt?: string
}

export interface AsyncTask {
  id: number
  taskNo: string
  commandId: string
  instanceId: number
  instanceNo?: string
  instanceName?: string
  tenantId?: number
  tenantName?: string
  operation: 'CREATE' | InstanceAction | 'RECONCILE'
  state: TaskState
  retryCount: number
  manualRetryCount?: number
  lastError?: string
  messageId?: string
  engineEventId?: string
  outboxId?: number
  outboxEventId?: string
  outboxState?: string
  outboxRetryCount?: number
  outboxLastError?: string
  outboxCreatedAt?: string
  outboxPublishedAt?: string
  nextRetryAt?: string
  outboxNextRetryAt?: string
  acceptedAt?: string
  finishedAt?: string
  deadlineAt?: string
  sourceTaskId?: number
  createdAt?: string
  updatedAt?: string
}

export interface AuditLog {
  id: number
  tenantId?: number
  actorId?: number
  instanceId?: number
  taskId?: number
  action: string
  beforeState?: string
  afterState?: string
  result: string
  error?: string
  traceId?: string
  createdAt: string
}

export interface BatchActionResult {
  successCount: number
  skippedCount: number
  failedCount: number
  items: Array<{ instanceId: number; code: string; message: string; taskId?: number }>
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly traceId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}
