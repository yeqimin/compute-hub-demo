<script setup lang="ts">
import type { InstanceAction, InstanceSummary, InstanceStatus } from '../../types/api'
import { allowedActions } from '../../utils/instanceActions'
import { statusMeta } from '../../utils/status'
type InstanceRow = InstanceSummary & Record<string, any>
const props = defineProps<{ rows: InstanceRow[]; loading: boolean; permissions: readonly string[] }>()
const emit = defineEmits<{ 'selection-change': [rows: any[]]; action: [payload: { action: InstanceAction; row: any }]; detail: [row: any] }>()
const money = (value: unknown) => value == null ? '-' : `¥${(Number(value) / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`
const created = (value: unknown) => value ? String(value).replace('T', ' ').slice(0, 19) : '-'
const actions = (row: any) => allowedActions(row.status as InstanceStatus, props.permissions)
</script>
<template>
  <el-table v-loading="loading" :data="rows" row-key="id" class="instance-table" empty-text="暂无符合条件的实例">
    <el-table-column type="selection" width="48" @selection-change="emit('selection-change', $event)" />
    <el-table-column label="实例" min-width="180"><template #default="scope"><button class="instance-link" @click="emit('detail', scope.row)">{{ scope.row.name || '-' }}</button><div class="muted">{{ scope.row.instanceNo || `ID ${scope.row.id}` }}</div></template></el-table-column>
    <el-table-column label="算力配置" min-width="170"><template #default="scope"><div>{{ scope.row.productName || '-' }}</div><div class="muted">{{ scope.row.gpuModel || 'GPU' }}{{ scope.row.gpuCount ? ` × ${scope.row.gpuCount}` : '' }} · {{ scope.row.quantity || 1 }} 台</div></template></el-table-column>
    <el-table-column label="目标集群" min-width="125"><template #default="scope">{{ scope.row.clusterName || '-' }}<div v-if="scope.row.region" class="muted">{{ scope.row.region }}</div></template></el-table-column>
    <el-table-column label="计费" min-width="126"><template #default="scope"><span class="money">{{ money(scope.row.amountCent ?? scope.row.creationChargeCent) }}</span><div class="muted">{{ scope.row.billingType || '创建预冻结' }}</div></template></el-table-column>
    <el-table-column label="状态" width="125"><template #default="scope"><el-tag :type="statusMeta(scope.row.status).type" effect="light">{{ statusMeta(scope.row.status).label }}</el-tag><div v-if="scope.row.taskState" class="muted">任务 {{ statusMeta(scope.row.taskState).label }}</div></template></el-table-column>
    <el-table-column label="创建时间" width="168"><template #default="scope">{{ created(scope.row.createdAt) }}</template></el-table-column>
    <el-table-column fixed="right" label="操作" width="210"><template #default="scope"><div class="row-actions"><el-button link type="primary" @click="emit('detail', scope.row)">详情</el-button><el-button v-for="action in actions(scope.row)" :key="action" :data-test="`action-${action}`" link :type="action === 'DELETE' ? 'danger' : undefined" @click="emit('action', { action, row: scope.row })">{{ { START: '启动', STOP: '停止', RESTART: '重启', DELETE: '删除' }[action] }}</el-button></div></template></el-table-column>
  </el-table>
</template>
<style scoped>.instance-link{border:0;background:none;padding:0;color:var(--text);font:inherit;font-weight:650;cursor:pointer;text-align:left}.instance-link:hover{color:#168e9b}.row-actions{display:flex;flex-wrap:wrap;gap:2px}.money{font-weight:650}.instance-table :deep(.el-table__cell){vertical-align:middle}</style>
