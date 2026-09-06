<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, idemKey } from '../api'
import InstanceDetailDrawer from '../components/instances/InstanceDetailDrawer.vue'
import InstanceFilters, { type InstanceFilterQuery } from '../components/instances/InstanceFilters.vue'
import InstanceTable from '../components/instances/InstanceTable.vue'
import { usePageQuery } from '../composables/usePageQuery'
import { useAuthStore } from '../stores/auth'
import type { BatchActionResult, InstanceAction, InstanceSummary, Page } from '../types/api'

type Row = InstanceSummary & Record<string, any>
type Option = { id: number; name: string; priceCent?: number; region?: string }
const auth = useAuthStore(), route = useRoute(), router = useRouter()
const asNumber = (value: unknown) => typeof value === 'string' && value ? Number(value) : undefined
const readRoute = (): InstanceFilterQuery => ({ keyword: String(route.query.keyword || ''), status: String(route.query.status || ''), tenantId: asNumber(route.query.tenantId), productId: asNumber(route.query.productId), clusterId: asNumber(route.query.clusterId), startTime: typeof route.query.startTime === 'string' ? route.query.startTime : undefined, endTime: typeof route.query.endTime === 'string' ? route.query.endTime : undefined })
const query = usePageQuery<Row, InstanceFilterQuery>(
  (params) => api.get<Page<Row>>('/instances', { params: { keyword: params.keyword || undefined, tenantId: params.tenantId, productId: params.productId, clusterId: params.clusterId, status: params.status || undefined, startTime: params.startTime, endTime: params.endTime, page: params.page, size: params.size } }),
  readRoute(), { page: Number(route.query.page) || 1, size: Number(route.query.size) || 20 },
)
const products = ref<Option[]>([]), clusters = ref<Option[]>([]), tenants = ref<Option[]>([]), selected = ref<Row[]>([])
const filtersDirty = ref(false), createDialog = ref(false), createSubmitting = ref(false), createValidationError = ref(''), tenantLoading = ref(false), tenantLoadError = ref(false), actionDialog = ref(false), actionSubmitting = ref(false), detailVisible = ref(false), detailLoading = ref(false), detail = ref<Row | null>(null), batchResult = ref<BatchActionResult | null>(null)
const form = reactive({ tenantId: undefined as number | undefined, productId: undefined as number | undefined, clusterId: undefined as number | undefined, name: '', quantity: 1, scenario: 'SUCCESS' })
const actionForm = reactive({ action: 'STOP' as InstanceAction, row: null as Row | null, instanceIds: [] as number[], scenario: 'SUCCESS', batch: false })
let timer: number | undefined, ownRouteUpdate = false
const actionName = (action: InstanceAction) => ({ START: '启动', STOP: '停止', RESTART: '重启', DELETE: '删除' }[action])
const canOperate = computed(() => auth.can('instance:operate'))
const interactionOpen = computed(() => createDialog.value || actionDialog.value || detailVisible.value)
const platformTenantMissing = computed(() => auth.isAdmin && !form.tenantId)
const createDisabled = computed(() => createSubmitting.value || platformTenantMissing.value || (auth.isAdmin && (tenantLoading.value || !tenants.value.length)))

const writeUrl = async () => {
  ownRouteUpdate = true
  await router.replace({ query: { ...(query.filters.keyword ? { keyword: query.filters.keyword } : {}), ...(query.filters.status ? { status: query.filters.status } : {}), ...(query.filters.tenantId ? { tenantId: String(query.filters.tenantId) } : {}), ...(query.filters.productId ? { productId: String(query.filters.productId) } : {}), ...(query.filters.clusterId ? { clusterId: String(query.filters.clusterId) } : {}), ...(query.filters.startTime ? { startTime: query.filters.startTime } : {}), ...(query.filters.endTime ? { endTime: query.filters.endTime } : {}), page: String(query.page.value), size: String(query.size.value) } })
  ownRouteUpdate = false
}
const search = async () => { query.page.value = 1; filtersDirty.value = false; await writeUrl(); await query.load() }
const reset = async () => { Object.assign(query.filters, { keyword: '', status: '', tenantId: undefined, productId: undefined, clusterId: undefined, startTime: undefined, endTime: undefined }); await search() }
const updateFilters = (next: InstanceFilterQuery) => { Object.assign(query.filters, next); filtersDirty.value = true }
const changePage = async () => { filtersDirty.value = false; await writeUrl(); await query.changePage(query.page.value, query.size.value) }
const refresh = async () => { filtersDirty.value = false; await writeUrl(); await query.load() }
const poll = () => { if (!interactionOpen.value && !filtersDirty.value) query.load() }

const openCreate = () => { createValidationError.value = ''; form.tenantId = auth.isAdmin ? form.tenantId : undefined; form.productId ||= products.value[0]?.id; form.clusterId ||= clusters.value[0]?.id; form.name ||= 'gpu-instance'; form.scenario = 'SUCCESS'; createDialog.value = true }
const create = async () => {
  if (platformTenantMissing.value) { createValidationError.value = '请选择租户'; return }
  if (auth.isAdmin && !tenants.value.length) { createValidationError.value = tenantLoadError.value ? '租户加载失败，请稍后重试' : '暂无可选租户，无法创建实例'; return }
  createValidationError.value = ''
  createSubmitting.value = true
  try { await api.post('/instances', { ...form, tenantId: auth.isAdmin ? form.tenantId : undefined }, { headers: { 'Idempotency-Key': idemKey() } }); createDialog.value = false; ElMessage.success('实例申请已提交，余额已冻结'); await refresh() } finally { createSubmitting.value = false }
}
const openAction = (payload: { action: InstanceAction; row: Row }) => { Object.assign(actionForm, { action: payload.action, row: payload.row, instanceIds: [payload.row.id], scenario: 'SUCCESS', batch: false }); actionDialog.value = true }
const openBatch = (action: InstanceAction) => { if (!selected.value.length) return; Object.assign(actionForm, { action, row: null, instanceIds: selected.value.map(row => row.id), scenario: 'SUCCESS', batch: true }); actionDialog.value = true }
const submitAction = async () => {
  if (actionForm.action === 'DELETE') await ElMessageBox.confirm(`即将 DELETE ${actionForm.instanceIds.length} 个实例。此操作会终止计算资源，是否继续？`, '高风险删除确认', { confirmButtonText: '确认 DELETE', cancelButtonText: '取消', type: 'warning' })
  actionSubmitting.value = true
  try {
    const config = { headers: { 'Idempotency-Key': idemKey() } }
    if (actionForm.batch) { batchResult.value = await api.post<BatchActionResult>('/instances/batch-actions', { instanceIds: actionForm.instanceIds, action: actionForm.action, scenario: actionForm.scenario }, config); selected.value = []; ElMessage.success(`批量${actionName(actionForm.action)}已处理`) }
    else if (actionForm.action === 'DELETE') await api.delete(`/instances/${actionForm.row!.id}`, { params: { scenario: actionForm.scenario }, ...config })
    else await api.post(`/instances/${actionForm.row!.id}/${actionForm.action.toLowerCase()}`, { scenario: actionForm.scenario }, config)
    actionDialog.value = false
    if (!actionForm.batch) ElMessage.success(`${actionName(actionForm.action)}请求已提交`)
    await refresh()
  } finally { actionSubmitting.value = false }
}
const openDetail = async (row: Row) => { detail.value = row; detailVisible.value = true; detailLoading.value = true; try { detail.value = await api.get<Row>(`/instances/${row.id}`) } catch { /* 详情接口异常时保留列表摘要 */ } finally { detailLoading.value = false } }
const reconcile = async (taskId: number) => { await api.post(`/tasks/${taskId}/reconcile`); ElMessage.success('已发起人工对账'); await refresh() }
watch(() => route.query, async () => { if (ownRouteUpdate) return; Object.assign(query.filters, readRoute()); query.page.value = Number(route.query.page) || 1; query.size.value = Number(route.query.size) || 20; filtersDirty.value = false; await query.load() })
onMounted(async () => { const requests: Promise<Option[]>[] = [api.get<Option[]>('/products'), api.get<Option[]>('/clusters')]; if (auth.isAdmin) { tenantLoading.value = true; requests.push(api.get<Option[]>('/tenants')) }; const values = await Promise.allSettled(requests); products.value = values[0].status === 'fulfilled' ? values[0].value : []; clusters.value = values[1].status === 'fulfilled' ? values[1].value : []; tenants.value = values[2]?.status === 'fulfilled' ? values[2].value : []; tenantLoadError.value = auth.isAdmin && values[2]?.status === 'rejected'; tenantLoading.value = false; await query.load(); timer = window.setInterval(poll, 5000) })
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <div class="panel instances-page">
    <div class="page-heading"><div><p class="eyebrow">COMPUTE FLEET</p><h3>实例控制台</h3><p class="muted">统一查看资源状态、费用与异步生命周期</p></div><div class="heading-actions"><span class="muted">每 5 秒自动刷新</span><el-button :loading="query.loading.value" @click="refresh">刷新</el-button><el-button v-if="auth.can('instance:create')" data-test="create" type="primary" @click="openCreate">创建实例</el-button></div></div>
    <InstanceFilters :model-value="query.filters" :products="products" :clusters="clusters" :tenants="tenants" :show-tenant="auth.isAdmin" @update:model-value="updateFilters" @search="search" @reset="reset" />
    <el-alert v-if="query.error.value" :title="query.error.value.message" type="error" show-icon :closable="false" class="load-error"><template #default><el-button link type="primary" @click="refresh">重新加载</el-button></template></el-alert>
    <div v-if="selected.length && canOperate" class="batch-bar"><span>已选择 <b>{{ selected.length }}</b> 个实例</span><div><el-button data-test="batch-START" size="small" @click="openBatch('START')">批量启动</el-button><el-button data-test="batch-STOP" size="small" @click="openBatch('STOP')">批量停止</el-button><el-button data-test="batch-RESTART" size="small" @click="openBatch('RESTART')">批量重启</el-button><el-button data-test="batch-DELETE" size="small" type="danger" plain @click="openBatch('DELETE')">批量删除</el-button></div></div>
    <InstanceTable :rows="query.items.value" :loading="query.loading.value" :permissions="auth.user?.permissions || []" @selection-change="selected = $event" @action="openAction" @detail="openDetail" />
    <div class="pager"><el-pagination v-model:current-page="query.page.value" v-model:page-size="query.size.value" background layout="total, sizes, prev, pager, next" :page-sizes="[20, 50, 100]" :total="query.total.value" @change="changePage" /></div>
    <el-dialog v-model="createDialog" title="创建 GPU 实例" width="min(580px, calc(100% - 28px))" :close-on-click-modal="false"><el-alert title="提交后将立即冻结对应金额，最终结算由异步任务完成。" type="info" :closable="false" style="margin-bottom:18px" /><el-alert v-if="createValidationError" :title="createValidationError" type="error" :closable="false" style="margin-bottom:18px" /><el-form :model="form" label-width="96px"><el-form-item v-if="auth.isAdmin" label="租户" required><el-select v-model="form.tenantId" data-test="create-tenant" :loading="tenantLoading" :disabled="tenantLoading || !tenants.length" placeholder="请选择租户" style="width:100%" @change="createValidationError = ''"><el-option v-for="tenant in tenants" :key="tenant.id" :label="tenant.name" :value="tenant.id" /></el-select><div v-if="tenantLoading" class="muted">正在加载租户…</div><div v-else-if="tenantLoadError" class="danger">租户加载失败，请稍后重试</div><div v-else-if="!tenants.length" class="muted">暂无可选租户</div><div v-else-if="platformTenantMissing" class="danger">请选择租户</div></el-form-item><el-form-item label="实例名称" required><el-input v-model="form.name" maxlength="128" show-word-limit /></el-form-item><el-form-item label="算力产品" required><el-select v-model="form.productId" style="width:100%"><el-option v-for="p in products" :key="p.id" :label="`${p.name} · ¥${((p.priceCent || 0) / 100).toFixed(2)}`" :value="p.id" /></el-select></el-form-item><el-form-item label="目标集群" required><el-select v-model="form.clusterId" style="width:100%"><el-option v-for="c in clusters" :key="c.id" :label="c.region ? `${c.name} · ${c.region}` : c.name" :value="c.id" /></el-select></el-form-item><el-form-item label="实例数量"><el-input-number v-model="form.quantity" :min="1" :max="10" /></el-form-item><el-collapse><el-collapse-item title="高级演示选项" name="scenario"><el-form-item label="引擎场景"><el-radio-group v-model="form.scenario"><el-radio value="SUCCESS">正常成功</el-radio><el-radio value="FAIL">引擎失败并解冻</el-radio><el-radio value="DUPLICATE_CALLBACK">重复回调防重</el-radio><el-radio value="TIMEOUT">超时待对账</el-radio></el-radio-group></el-form-item></el-collapse-item></el-collapse></el-form><template #footer><el-button @click="createDialog = false">取消</el-button><el-button data-test="create-submit" type="primary" :loading="createSubmitting" :disabled="createDisabled" @click="create">确认并冻结余额</el-button></template></el-dialog>
    <el-dialog v-model="actionDialog" :title="`${actionForm.batch ? '批量' : ''}${actionName(actionForm.action)}实例`" width="min(460px, calc(100% - 28px))"><p>将对 <b>{{ actionForm.instanceIds.length }}</b> 个实例提交{{ actionName(actionForm.action) }}请求。</p><el-alert v-if="actionForm.action === 'DELETE'" title="删除操作不可逆，请确认实例与业务影响。" type="warning" :closable="false" /><el-form label-width="78px" style="margin-top:18px"><el-form-item label="演示场景"><el-select v-model="actionForm.scenario"><el-option label="正常成功" value="SUCCESS" /><el-option label="模拟失败" value="FAIL" /><el-option label="超时待对账" value="TIMEOUT" /></el-select></el-form-item></el-form><template #footer><el-button @click="actionDialog = false">取消</el-button><el-button data-test="action-confirm" :type="actionForm.action === 'DELETE' ? 'danger' : 'primary'" :loading="actionSubmitting" @click="submitAction">确认{{ actionName(actionForm.action) }}</el-button></template></el-dialog>
    <el-dialog :model-value="!!batchResult" title="批量操作结果" width="min(560px, calc(100% - 28px))" @update:model-value="value => { if (!value) batchResult = null }"><div v-if="batchResult" class="batch-summary"><el-tag type="success">成功 {{ batchResult.successCount }}</el-tag><el-tag type="warning">跳过 {{ batchResult.skippedCount }}</el-tag><el-tag type="danger">失败 {{ batchResult.failedCount }}</el-tag></div><div v-for="item in batchResult?.items || []" :key="`${item.instanceId}-${item.code}`" data-test="batch-result" class="batch-result"><b>实例 {{ item.instanceId }}</b><el-tag size="small" :type="item.code === 'SUCCESS' ? 'success' : item.code === 'SKIPPED' ? 'warning' : 'danger'">{{ item.code }}</el-tag><span>{{ item.message }}</span><small v-if="item.taskId">任务 #{{ item.taskId }}</small></div></el-dialog>
    <InstanceDetailDrawer v-model="detailVisible" :detail="detail" :loading="detailLoading" :permissions="auth.user?.permissions || []" @reconcile="reconcile" />
  </div>
</template>
<style scoped>
.instances-page{min-width:0}.page-heading{display:flex;justify-content:space-between;gap:18px;align-items:flex-start;margin-bottom:22px}.page-heading h3{margin:4px 0;font-size:20px}.page-heading .muted{margin:0}.heading-actions{display:flex;align-items:center;gap:9px;white-space:nowrap}.load-error{margin-bottom:14px}.batch-bar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:0 0 12px;padding:9px 12px;border-radius:9px;background:#eaf8f9;color:#206878;font-size:13px}.batch-bar>div{display:flex;gap:6px}.batch-summary{display:flex;gap:8px;margin-bottom:12px}.batch-result{display:grid;grid-template-columns:90px auto 1fr auto;align-items:center;gap:9px;padding:10px 4px;border-bottom:1px solid var(--border);font-size:13px}.batch-result:last-child{border-bottom:0}.batch-result small{color:var(--muted)}@media(max-width:820px){.page-heading{display:block}.heading-actions{margin-top:12px}.batch-bar{align-items:flex-start;flex-direction:column}.batch-bar>div{flex-wrap:wrap}.batch-result{grid-template-columns:1fr auto}.batch-result span,.batch-result small{grid-column:1/-1}.pager{justify-content:center}}
</style>
