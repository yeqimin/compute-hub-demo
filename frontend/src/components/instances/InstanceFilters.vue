<script setup lang="ts">
import { computed, ref } from 'vue'

export type InstanceFilterQuery = {
  keyword: string
  tenantId?: number
  productId?: number
  clusterId?: number
  status: string
  startTime?: string
  endTime?: string
}

type Option = { id: number; name: string; code?: string; region?: string }
const props = defineProps<{ modelValue: InstanceFilterQuery; products: Option[]; clusters: Option[]; tenants: Option[]; showTenant: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: InstanceFilterQuery]; search: []; reset: [] }>()
const range = ref<string[]>(props.modelValue.startTime && props.modelValue.endTime ? [props.modelValue.startTime, props.modelValue.endTime] : [])
const update = (patch: Partial<InstanceFilterQuery>) => emit('update:modelValue', { ...props.modelValue, ...patch })
const setRange = (value: string[] | null) => { range.value = value || []; update({ startTime: range.value[0], endTime: range.value[1] }) }
const statusOptions = [['REQUESTED', '已申请'], ['CREATING', '创建中'], ['RUNNING', '运行中'], ['STOPPING', '停止中'], ['STOPPED', '已停止'], ['STARTING', '启动中'], ['RESTARTING', '重启中'], ['DELETING', '删除中'], ['DELETED', '已删除'], ['FAILED', '失败'], ['DELETE_FAILED', '删除失败'], ['UNKNOWN', '待对账']]
const summary = computed(() => props.modelValue.status || '全部状态')
</script>

<template>
  <section class="instance-filters" aria-label="实例筛选">
    <div class="filter-main">
      <el-input :model-value="modelValue.keyword" data-test="search" clearable placeholder="搜索实例名或实例号" class="keyword" @update:model-value="update({ keyword: String($event) })" @keyup.enter="emit('search')"><template #prefix>⌕</template></el-input>
      <el-select :model-value="modelValue.status" data-test="status-filter" clearable placeholder="全部状态" @update:model-value="update({ status: String($event || '') })"><el-option v-for="item in statusOptions" :key="item[0]" :label="item[1]" :value="item[0]" /></el-select>
      <el-select v-if="showTenant" :model-value="modelValue.tenantId" clearable filterable placeholder="全部租户" @update:model-value="update({ tenantId: $event || undefined })"><el-option v-for="tenant in tenants" :key="tenant.id" :label="tenant.name" :value="tenant.id" /></el-select>
      <el-select :model-value="modelValue.productId" clearable filterable placeholder="全部产品" @update:model-value="update({ productId: $event || undefined })"><el-option v-for="product in products" :key="product.id" :label="product.name" :value="product.id" /></el-select>
      <el-select :model-value="modelValue.clusterId" clearable filterable placeholder="全部集群" @update:model-value="update({ clusterId: $event || undefined })"><el-option v-for="cluster in clusters" :key="cluster.id" :label="cluster.region ? `${cluster.name} · ${cluster.region}` : cluster.name" :value="cluster.id" /></el-select>
      <el-date-picker :model-value="range" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss" range-separator="至" start-placeholder="创建开始" end-placeholder="创建结束" @update:model-value="setRange" />
    </div>
    <div class="filter-actions"><span class="filter-status">{{ summary }}</span><el-button data-test="query" type="primary" @click="emit('search')">查询</el-button><el-button @click="emit('reset')">重置</el-button></div>
  </section>
</template>

<style scoped>
.instance-filters{display:flex;justify-content:space-between;gap:14px;align-items:flex-start;margin-bottom:16px}.filter-main{display:flex;gap:10px;flex-wrap:wrap;flex:1}.filter-main :deep(.el-input),.filter-main :deep(.el-select){width:146px}.filter-main :deep(.keyword){width:220px}.filter-main :deep(.el-date-editor){width:286px}.filter-actions{display:flex;gap:8px;align-items:center;white-space:nowrap}.filter-status{display:none;color:var(--muted);font-size:12px}@media(max-width:920px){.instance-filters{display:block}.filter-actions{margin-top:10px}.filter-status{display:inline}}@media(max-width:600px){.filter-main :deep(.el-input),.filter-main :deep(.el-select),.filter-main :deep(.keyword),.filter-main :deep(.el-date-editor){width:100%}}
</style>
