<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import TaskDetailDrawer from '../components/tasks/TaskDetailDrawer.vue'
import { useAuthStore } from '../stores/auth'
import type { AsyncTask, Page, TaskState } from '../types/api'
import { statusMeta } from '../utils/status'
import { formatDateTime } from '../utils/time'

type TaskFilters = { tenantId?: number; operation: string; state: string; commandId: string; instanceNo: string; startTime?: string; endTime?: string; sort: 'createdAt' | 'updatedAt' | 'state' | 'operation' | 'retryCount'; order: 'asc' | 'desc' }
type TenantOption = { id: number; name: string }
const auth = useAuthStore(), route = useRoute(), router = useRouter()
const asNumber = (value: unknown) => typeof value === 'string' && value ? Number(value) : undefined
const readRoute = (): TaskFilters => ({ tenantId: asNumber(route.query.tenantId), operation: String(route.query.operation || ''), state: String(route.query.state || ''), commandId: String(route.query.commandId || ''), instanceNo: String(route.query.instanceNo || ''), startTime: typeof route.query.startTime === 'string' ? route.query.startTime : undefined, endTime: typeof route.query.endTime === 'string' ? route.query.endTime : undefined, sort: ['createdAt', 'updatedAt', 'state', 'operation', 'retryCount'].includes(String(route.query.sort)) ? String(route.query.sort) as TaskFilters['sort'] : 'createdAt', order: route.query.order === 'asc' ? 'asc' : 'desc' })
const filters = reactive<TaskFilters>(readRoute())
const page = ref(Number(route.query.page) || 1), size = ref(Number(route.query.size) || 20), rows = ref<AsyncTask[]>([]), total = ref(0)
const loading = ref(false), error = ref<Error | null>(null), tenants = ref<TenantOption[]>([]), filtersDirty = ref(false), drawer = ref(false), selected = ref<AsyncTask | null>(null), detailLoading = ref(false), submitting = ref(false)
let timer: number | undefined, ownRouteUpdate = false, requestSequence = 0
const canRecover = computed(() => auth.can('instance:retry'))
const interacting = computed(() => drawer.value || submitting.value)
const timeRange = computed<string[] | undefined>(() => filters.startTime && filters.endTime ? [filters.startTime, filters.endTime] : undefined)
const summary = computed(() => ({ processing: rows.value.filter(row => ['PENDING', 'PUBLISHED', 'PROCESSING', 'RETRY_WAIT', 'WAITING_CALLBACK'].includes(row.state)).length, succeeded: rows.value.filter(row => row.state === 'SUCCEEDED').length, abnormal: rows.value.filter(row => ['FAILED', 'DEAD', 'UNKNOWN'].includes(row.state)).length }))
const apiParams = () => ({ tenantId: filters.tenantId, operation: filters.operation || undefined, state: filters.state || undefined, commandId: filters.commandId || undefined, instanceNo: filters.instanceNo || undefined, startedAt: filters.startTime, endedAt: filters.endTime, sort: filters.sort, order: filters.order, page: page.value, size: size.value })
const toError = (reason: unknown) => reason instanceof Error ? reason : new Error('请求失败')
const load = async () => { const requestId = ++requestSequence; loading.value = true; error.value = null; try { const result = await api.get<Page<AsyncTask>>('/tasks', { params: apiParams() }); if (requestId !== requestSequence) return; rows.value = result.items; total.value = result.total; page.value = result.page; size.value = result.size } catch (reason) { if (requestId === requestSequence) error.value = toError(reason) } finally { if (requestId === requestSequence) loading.value = false } }
const writeUrl = async () => { ownRouteUpdate = true; await router.replace({ query: { ...(filters.tenantId ? { tenantId: String(filters.tenantId) } : {}), ...(filters.operation ? { operation: filters.operation } : {}), ...(filters.state ? { state: filters.state } : {}), ...(filters.commandId ? { commandId: filters.commandId } : {}), ...(filters.instanceNo ? { instanceNo: filters.instanceNo } : {}), ...(filters.startTime ? { startTime: filters.startTime } : {}), ...(filters.endTime ? { endTime: filters.endTime } : {}), sort: filters.sort, order: filters.order, page: String(page.value), size: String(size.value) } }); ownRouteUpdate = false }
const search = async () => { page.value = 1; filtersDirty.value = false; await writeUrl(); await load() }
const reset = async () => { Object.assign(filters, { tenantId: undefined, operation: '', state: '', commandId: '', instanceNo: '', startTime: undefined, endTime: undefined, sort: 'createdAt', order: 'desc' }); await search() }
const refresh = async () => { filtersDirty.value = false; await writeUrl(); await load() }
const changePage = async () => { filtersDirty.value = false; await writeUrl(); await load() }
const asTask = (row: unknown) => row as AsyncTask
const openDetail = async (row: unknown) => { const task = asTask(row); selected.value = task; drawer.value = true; detailLoading.value = true; try { selected.value = await api.get<AsyncTask>(`/tasks/${task.id}`) } catch (reason) { ElMessage.error(`任务详情加载失败：${toError(reason).message}`) } finally { detailLoading.value = false } }
const recover = async (action: 'retry' | 'reconcile', taskId: number) => { const label = action === 'retry' ? '重新投递' : '人工对账'; try { await ElMessageBox.confirm(action === 'retry' ? '将以原命令重新投递该 UNKNOWN 任务。任务状态已变化时服务端会拒绝本次操作。' : '将查询引擎事实并通过 Inbox/结算流程收敛任务。任务状态已变化时服务端会拒绝本次操作。', `${label}确认`, { confirmButtonText: `确认${label}`, cancelButtonText: '取消', type: 'warning' }); submitting.value = true; await api.post(`/tasks/${taskId}/${action}`); ElMessage.success(`${label}已发起`); await load(); if (selected.value?.id === taskId) selected.value = await api.get<AsyncTask>(`/tasks/${taskId}`) } catch (reason) { if (reason === 'cancel' || reason === 'close') return; const apiError = toError(reason) as Error & { traceId?: string }; ElMessage.error(apiError.traceId ? `${apiError.message}（追踪 ID：${apiError.traceId}）` : apiError.message) } finally { submitting.value = false } }
const openInstance = (row: unknown) => { const task = asTask(row); return router.push({ path: '/instances', query: { ...(task.instanceNo ? { keyword: task.instanceNo } : {}), from: 'tasks', taskId: String(task.id) } }) }
const updateTimeRange = (range: string[] | null) => { filters.startTime = range?.[0]; filters.endTime = range?.[1] }
const poll = () => { if (!interacting.value && !filtersDirty.value) void load() }
watch(filters, () => { if (!ownRouteUpdate) filtersDirty.value = true }, { deep: true })
watch(() => route.query, async () => { if (ownRouteUpdate) return; Object.assign(filters, readRoute()); page.value = Number(route.query.page) || 1; size.value = Number(route.query.size) || 20; filtersDirty.value = false; await load() })
onMounted(async () => { if (auth.isAdmin) { try { tenants.value = await api.get<TenantOption[]>('/tenants') } catch { tenants.value = [] } } await load(); timer = window.setInterval(poll, 5000) })
onBeforeUnmount(() => { if (timer !== undefined) window.clearInterval(timer) })
</script>
<template>
  <div class="panel tasks-page">
    <div class="page-heading"><div><p class="eyebrow">ASYNC OPERATIONS</p><h3>异步任务中心</h3><p class="muted">筛选后的总数来自服务端；状态计数仅统计当前页。</p></div><div class="heading-actions"><span class="muted">每 5 秒自动刷新</span><el-button :loading="loading" @click="refresh">刷新</el-button></div></div>
    <div class="summary-grid"><div class="summary-card"><span>当前筛选总任务</span><b>{{ total }}</b></div><div class="summary-card"><span>本页处理中</span><b>{{ summary.processing }}</b></div><div class="summary-card"><span>本页成功</span><b>{{ summary.succeeded }}</b></div><div class="summary-card warn"><span>本页异常 / 待对账</span><b data-test="summary-abnormal">{{ summary.abnormal }}</b></div></div>
    <div class="filters">
      <el-select v-if="auth.isAdmin" v-model="filters.tenantId" clearable placeholder="全部租户" style="width:160px"><el-option v-for="tenant in tenants" :key="tenant.id" :label="tenant.name" :value="tenant.id" /></el-select>
      <el-select v-model="filters.operation" clearable placeholder="全部操作" style="width:140px"><el-option v-for="operation in ['CREATE','START','STOP','RESTART','DELETE','RECONCILE']" :key="operation" :label="operation" :value="operation" /></el-select>
      <el-select v-model="filters.state" data-test="filter-state" clearable placeholder="全部任务状态" style="width:150px"><el-option v-for="state in ['PENDING','PUBLISHED','PROCESSING','RETRY_WAIT','WAITING_CALLBACK','SUCCEEDED','FAILED','UNKNOWN','DEAD']" :key="state" :label="statusMeta(state).label" :value="state" /></el-select>
      <el-input v-model="filters.commandId" clearable placeholder="命令号" style="width:190px" @keyup.enter="search" /><el-input v-model="filters.instanceNo" clearable placeholder="实例号" style="width:150px" @keyup.enter="search" />
      <el-date-picker :model-value="timeRange" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss" start-placeholder="开始时间" end-placeholder="结束时间" style="width:320px" @change="updateTimeRange" />
      <el-select v-model="filters.sort" aria-label="排序字段" style="width:120px"><el-option label="创建时间" value="createdAt" /><el-option label="更新时间" value="updatedAt" /><el-option label="状态" value="state" /><el-option label="操作" value="operation" /><el-option label="重试次数" value="retryCount" /></el-select>
      <el-select v-model="filters.order" aria-label="排序方向" style="width:100px"><el-option label="降序" value="desc" /><el-option label="升序" value="asc" /></el-select><el-button data-test="search" type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button>
    </div>
    <el-alert v-if="error" :title="error.message" type="error" show-icon :closable="false" class="load-error"><template #default><el-button link type="primary" @click="refresh">重新加载</el-button></template></el-alert>
    <el-table v-loading="loading" :data="rows" row-key="id" empty-text="暂无符合条件的任务">
      <el-table-column prop="taskNo" label="任务号" min-width="150"><template #default="scope"><el-button :data-test="`task-detail-${scope.row.id}`" link type="primary" @click="openDetail(scope.row)">{{ scope.row.taskNo }}</el-button></template></el-table-column>
      <el-table-column label="实例" min-width="150"><template #default="scope"><el-button link @click="openInstance(scope.row)">{{ scope.row.instanceNo || `#${scope.row.instanceId}` }}</el-button></template></el-table-column>
      <el-table-column prop="operation" label="操作" width="100" /><el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="statusMeta(scope.row.state as TaskState).type">{{ statusMeta(scope.row.state as TaskState).label }}</el-tag></template></el-table-column><el-table-column prop="retryCount" label="自动重试" width="95" /><el-table-column prop="manualRetryCount" label="人工重试" width="95" />
      <el-table-column label="错误" min-width="170" show-overflow-tooltip><template #default="scope"><span class="muted">{{ scope.row.lastError || '-' }}</span></template></el-table-column><el-table-column label="时间" min-width="165"><template #default="scope"><div data-test="task-list-created-at">{{ formatDateTime(scope.row.createdAt) }}</div><small data-test="task-list-updated-at" class="muted">{{ formatDateTime(scope.row.updatedAt) }}</small></template></el-table-column>
    </el-table>
    <div class="pager"><el-pagination v-model:current-page="page" v-model:page-size="size" background layout="total, sizes, prev, pager, next" :page-sizes="[20, 50, 100]" :total="total" @change="changePage" /></div>
    <TaskDetailDrawer v-model="drawer" :task="selected" :loading="detailLoading" :can-recover="canRecover" :submitting="submitting" @retry="recover('retry', $event)" @reconcile="recover('reconcile', $event)" />
  </div>
</template>
<style scoped>.page-heading{display:flex;justify-content:space-between;gap:18px;align-items:flex-start;margin-bottom:18px}.page-heading h3{margin:4px 0;font-size:20px}.page-heading .muted{margin:0}.heading-actions{display:flex;align-items:center;gap:9px;white-space:nowrap}.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:16px}.summary-card{padding:14px 16px;border:1px solid var(--border);border-radius:10px;background:var(--surface);display:flex;flex-direction:column;gap:4px}.summary-card span{font-size:12px;color:var(--muted)}.summary-card b{font-size:25px}.summary-card.warn b{color:#c27003}.filters{display:flex;flex-wrap:wrap;gap:9px;margin-bottom:14px}.load-error{margin-bottom:14px}.pager{display:flex;justify-content:flex-end;margin-top:16px}@media(max-width:900px){.summary-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.page-heading{display:block}.heading-actions{margin-top:12px}.pager{justify-content:center}}@media(max-width:540px){.summary-grid{grid-template-columns:1fr}}</style>
