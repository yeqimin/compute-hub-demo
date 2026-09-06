<script setup lang="ts">
import { computed } from 'vue'
import type { AsyncTask } from '../../types/api'
import type { TagType } from '../../utils/status'
import { formatDateTime } from '../../utils/time'

const props = defineProps<{ task: AsyncTask }>()
const displayTime = (value?: string | null) => formatDateTime(value, '尚未到达')
type Phase = { key: string; label: string; detail: string; state: string; time?: string | null; type: TagType }
const phases = computed<Phase[]>(() => [
  { key: 'transaction', label: '事务', detail: '业务任务已落库', state: 'COMPLETED', time: props.task.createdAt, type: 'primary' },
  { key: 'outbox', label: '数据库 Outbox', detail: props.task.outboxState || '等待写入', state: props.task.outboxState || 'PENDING', time: props.task.outboxPublishedAt || props.task.outboxCreatedAt, type: 'primary' },
  { key: 'engine', label: 'gRPC 引擎', detail: props.task.acceptedAt ? '已接受命令' : '等待引擎确认', state: props.task.acceptedAt ? 'ACCEPTED' : 'PENDING', time: props.task.acceptedAt, type: 'primary' },
  { key: 'callback', label: '回调', detail: props.task.engineEventId ? '已接收回调事件' : '等待回调', state: props.task.engineEventId ? 'RECEIVED' : 'PENDING', time: props.task.finishedAt, type: 'warning' },
  { key: 'settlement', label: '业务收敛', detail: props.task.state === 'SUCCEEDED' ? '已成功收敛' : props.task.state === 'FAILED' ? '已失败收敛' : '等待业务收敛', state: props.task.state, time: props.task.finishedAt, type: props.task.state === 'SUCCEEDED' ? 'success' : props.task.state === 'FAILED' ? 'danger' : 'info' },
])
</script>

<template>
  <el-timeline class="task-timeline"><el-timeline-item v-for="phase in phases" :key="phase.key" :timestamp="displayTime(phase.time)" :type="phase.type" data-test="task-phase"><strong>{{ phase.label }}</strong><span class="phase-state">{{ phase.state }}</span><div class="muted">{{ phase.detail }}</div></el-timeline-item></el-timeline>
</template>

<style scoped>.task-timeline{margin:12px 0 2px}.phase-state{margin-left:7px;font-size:12px;color:var(--muted)}</style>
