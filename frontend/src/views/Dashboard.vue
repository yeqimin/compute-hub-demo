<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { init, use, type ECharts } from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { api } from '../api'
import MetricCard from '../components/dashboard/MetricCard.vue'
import MetricTrend from '../components/dashboard/MetricTrend.vue'
import ClusterTopology from '../components/dashboard/ClusterTopology.vue'
use([PieChart, LegendComponent, TooltipComponent, CanvasRenderer])
type Summary = Record<string, any>
const router = useRouter(), summary = ref<Summary | null>(null), metrics = ref<any[]>([]), loading = ref(true), refreshing = ref(false), error = ref('')
const capacityElement = ref<HTMLElement>(); let capacityChart: ECharts | undefined; let capacityObserver: ResizeObserver | undefined; let capacityResizeHandler: (() => void) | undefined; let timer: number | undefined; let requestActive = false
const samples = ref<{ label: string; value: number }[]>([])
const money = (value: number) => `¥${(Number(value || 0) / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const numeric = (value: unknown) => Number(value || 0).toLocaleString('zh-CN')
const percent = (value: unknown) => `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}%`
const capacity = computed(() => ({ allocated: Number(summary.value?.gpuAllocated || 0), available: Number(summary.value?.gpuAvailable || 0) }))
const tenantLogicalUsage = computed(() => summary.value?.resourceScope === 'TENANT_LOGICAL_USAGE')
const sample = () => { const values = metrics.value.filter(item => item.usageScope === 'PLATFORM_PHYSICAL' && Number.isFinite(item.gpuUtilization)).map(item => item.gpuUtilization as number); if (!values.length) return; const value = Math.round(values.reduce((sum, item) => sum + item, 0) / values.length * 10) / 10; samples.value = [...samples.value.slice(-11), { label: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }), value }] }
const observeCapacity = () => { if (!capacityElement.value || capacityObserver || capacityResizeHandler) return; if (typeof ResizeObserver !== 'undefined') { capacityObserver = new ResizeObserver(() => capacityChart?.resize()); capacityObserver.observe(capacityElement.value) } else { capacityResizeHandler = () => capacityChart?.resize(); window.addEventListener('resize', capacityResizeHandler) } }
const renderCapacity = () => { if (!capacityElement.value) return; capacityChart ??= init(capacityElement.value); observeCapacity(); capacityChart.setOption({ tooltip: { trigger: 'item' }, legend: { bottom: 0 }, series: [{ type: 'pie', radius: ['52%', '72%'], center: ['50%', '43%'], label: { show: false }, data: [{ name: tenantLogicalUsage.value ? '运行中逻辑用量' : '已分配', value: capacity.value.allocated, itemStyle: { color: '#5575ff' } }, { name: tenantLogicalUsage.value ? '非运行申请' : '可用', value: capacity.value.available, itemStyle: { color: '#dce6f1' } }] }] }) }
watch(capacityElement, (element) => { if (element) renderCapacity() })
const load = async () => { if (requestActive) return; requestActive = true; error.value = ''; try { const [nextSummary, nextMetrics] = await Promise.all([api.get<Summary>('/dashboard/summary'), api.get<any[]>('/metrics/clusters')]); summary.value = nextSummary; metrics.value = nextMetrics; sample(); await nextTick(); renderCapacity() } catch (cause) { error.value = (cause as Error).message || '暂时无法获取运营指标' } finally { loading.value = false; refreshing.value = false; requestActive = false } }
const refresh = () => { refreshing.value = true; void load() }
const toInstances = (status?: string) => router.push({ path: '/instances', query: status ? { status } : {} })
const toTasks = () => router.push({ path: '/tasks' })
onMounted(() => { void load(); timer = window.setInterval(load, 5000) })
onBeforeUnmount(() => { if (timer !== undefined) window.clearInterval(timer); capacityObserver?.disconnect(); if (capacityResizeHandler) window.removeEventListener('resize', capacityResizeHandler); capacityChart?.dispose() })
</script>
<template>
  <div class="dashboard">
    <div class="dashboard__toolbar"><div><span class="eyebrow">LIVE COMPUTE VIEW</span><p>实时采样仅保存在本次浏览器会话，不代表历史趋势</p></div><el-button data-test="dashboard-refresh" :loading="refreshing" @click="refresh">手动刷新</el-button></div>
    <section v-if="loading" class="dashboard__skeleton" data-test="dashboard-loading"><el-skeleton :rows="4" animated /></section>
    <section v-else-if="error && !summary" class="panel dashboard__error"><el-empty :description="error"><el-button @click="refresh">重新加载</el-button></el-empty></section>
    <template v-else-if="summary">
      <el-alert v-if="error" :title="`${error}，正在显示上次成功数据`" type="warning" :closable="false" show-icon class="dashboard__notice" />
      <section class="grid dashboard__kpis">
        <MetricCard data-test="gpu-total" :label="tenantLogicalUsage ? '租户逻辑 GPU 申请 / 运行中' : 'GPU 总量 / 可用'" :value="`${numeric(summary.gpuTotal)} / ${numeric(tenantLogicalUsage ? summary.gpuAllocated : summary.gpuAvailable)} 卡`" :hint="tenantLogicalUsage ? '仅统计本租户实例申请，不代表物理可用容量' : `运行已占用 ${numeric(summary.gpuAllocated)} 卡`" />
        <MetricCard data-test="node-health" label="节点健康" :value="`${numeric(summary.readyNodeCount)} 正常`" :hint="`异常 ${numeric(summary.unhealthyNodeCount)} 个`" :tone="Number(summary.unhealthyNodeCount) ? 'danger' : 'success'" @click="router.push('/resources')" />
        <MetricCard data-test="running-instances" label="运行实例" :value="numeric(summary.runningInstances)" hint="仅运行中" @click="toInstances('RUNNING')" />
        <MetricCard data-test="abnormal-instances" label="异常实例" :value="numeric(summary.abnormalInstances)" hint="FAILED / UNKNOWN" tone="danger" @click="toInstances()" />
        <MetricCard data-test="task-success-rate" label="24 小时任务成功率" :value="percent(summary.taskSuccessRate)" :hint="`异常任务 ${numeric(summary.abnormalTasks)}`" :tone="Number(summary.abnormalTasks) ? 'danger' : 'success'" @click="toTasks" />
        <MetricCard data-test="abnormal-tasks" label="异常任务" :value="numeric(summary.abnormalTasks)" hint="FAILED / UNKNOWN / DEAD（任务中心展示全部状态）" tone="danger" @click="toTasks" />
        <MetricCard data-test="wallet-available" label="钱包可用 / 冻结" :value="money(summary.availableCent)" :hint="`冻结 ${money(summary.frozenCent)}`" />
      </section>
      <section class="grid dashboard__charts"><article class="panel"><div class="panel-title"><h3>GPU 利用率</h3><span class="muted">浏览器会话实时采样</span></div><div v-if="tenantLogicalUsage" class="metric-trend-empty" data-test="metric-trend-empty">共享物理利用率未按租户展示</div><MetricTrend v-else :points="samples" /></article><article class="panel"><div class="panel-title"><h3>{{ tenantLogicalUsage ? '租户逻辑 GPU 用量' : 'GPU 容量' }}</h3><span class="muted">{{ tenantLogicalUsage ? '运行中 / 非运行申请' : '已分配 / 可用' }}</span></div><div ref="capacityElement" class="dashboard__capacity" data-test="capacity-chart" /></article><article class="panel"><div class="panel-title"><h3>实例状态分布</h3><span class="muted">当前范围</span></div><div v-if="summary.instanceDistribution?.length" class="distribution"><button v-for="item in summary.instanceDistribution" :key="item.status" type="button" @click="toInstances(item.status)"><span>{{ item.status }}</span><b>{{ numeric(item.value) }}</b></button></div><el-empty v-else description="暂无实例" :image-size="64" /></article></section>
      <section class="grid dashboard__bottom"><article class="panel"><div class="panel-title"><h3>集群健康</h3><span class="live"><i />{{ metrics.length ? '轮询正常' : '暂无指标' }}</span></div><div v-if="metrics.length" class="cluster-list"><div v-for="metric in metrics" :key="metric.clusterCode" class="cluster-list__item"><b>{{ metric.clusterCode }}</b><template v-if="metric.usageScope === 'PLATFORM_PHYSICAL'"><el-progress :percentage="Number(metric.gpuUtilization || 0)" :stroke-width="8" /><span>CPU {{ percent(metric.cpuUtilization) }} · 内存 {{ percent(metric.memoryUtilization) }}</span></template><span v-else class="muted">租户已用集群；共享物理利用率不按租户展示</span></div></div><el-empty v-else description="暂无集群实时指标" :image-size="64" /></article><article class="panel"><div class="panel-title"><h3>紧凑集群拓扑</h3><span class="muted">{{ numeric(summary.clusterCount) }} 个集群</span></div><ClusterTopology :clusters="summary.topology || []" /></article></section>
    </template>
  </div>
</template>
<style scoped>.dashboard{display:grid;gap:18px}.dashboard__toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px}.dashboard__toolbar p{font-size:12px;color:var(--muted);margin:4px 0 0}.dashboard__skeleton{min-height:280px;background:var(--surface);border-radius:14px;padding:28px}.dashboard__notice{margin-bottom:0}.dashboard__kpis{grid-template-columns:repeat(3,minmax(0,1fr))}.dashboard__charts{grid-template-columns:1.5fr 1fr 1fr}.dashboard__capacity{height:270px}.dashboard__bottom{grid-template-columns:1fr 1.35fr}.cluster-list{display:grid;gap:15px}.cluster-list__item{display:grid;grid-template-columns:100px 1fr 180px;align-items:center;gap:12px;font-size:12px}.distribution{display:grid;gap:8px}.distribution button{border:0;border-radius:8px;background:var(--page-bg);padding:10px;display:flex;justify-content:space-between;color:var(--text);cursor:pointer}.distribution button:hover{background:#e7f6f6}.dashboard__error{min-height:260px}@media(max-width:1200px){.dashboard__charts,.dashboard__bottom{grid-template-columns:1fr}.dashboard__kpis{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:650px){.dashboard__toolbar{align-items:flex-start;flex-direction:column}.dashboard__kpis{grid-template-columns:1fr}.cluster-list__item{grid-template-columns:1fr;gap:6px}.dashboard__capacity{height:230px}}</style>
