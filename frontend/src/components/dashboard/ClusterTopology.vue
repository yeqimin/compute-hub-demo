<script setup lang="ts">
type Node = { id: number; name: string; status: string; gpuTotal: number; gpuAllocated: number | null }
type Cluster = { id: number; code: string; name: string; region?: string; status: string; nodes: Node[] }
defineProps<{ clusters: Cluster[] }>()
const statusMeta = (status: string) => {
  if (status === 'HEALTHY' || status === 'READY') return { label: '健康', type: 'success' as const }
  if (status === 'UNHEALTHY') return { label: '不健康', type: 'danger' as const }
  if (status === 'OFFLINE') return { label: '离线', type: 'danger' as const }
  if (status === 'MAINTENANCE') return { label: '维护中', type: 'warning' as const }
  return { label: '状态异常', type: 'warning' as const }
}
</script>
<template><div v-if="clusters.length" class="topology" data-test="cluster-topology"><article v-for="cluster in clusters" :key="cluster.id" class="topology__cluster"><header><span><b>{{ cluster.name }}</b><small>{{ cluster.code }} · {{ cluster.region || '未标注地域' }}</small></span><el-tag size="small" :type="statusMeta(cluster.status).type">{{ statusMeta(cluster.status).label }}</el-tag></header><div class="topology__nodes"><div v-for="node in cluster.nodes" :key="node.id" class="topology__node"><span :class="{ bad: node.status !== 'READY' && node.status !== 'HEALTHY' }">{{ node.name }}</span><small v-if="node.gpuAllocated !== null">{{ node.gpuAllocated }}/{{ node.gpuTotal }} GPU</small><small v-else>租户范围内不展示共享节点已分配量</small></div></div></article></div><el-empty v-else description="暂无可展示的集群拓扑" :image-size="72" data-test="topology-empty" /></template>
<style scoped>.topology{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px}.topology__cluster{border:1px solid var(--border);border-radius:10px;padding:12px;background:var(--surface)}.topology__cluster header{height:auto;padding:0;background:transparent;border:0;display:flex;justify-content:space-between;gap:8px}.topology__cluster b,.topology__cluster small{display:block}.topology__cluster small,.topology__node small{color:var(--muted);font-size:11px;margin-top:3px}.topology__nodes{border-left:1px solid #bde5e7;margin:12px 0 0 7px;padding-left:12px;display:grid;gap:8px}.topology__node{display:flex;justify-content:space-between;gap:8px;font-size:12px}.bad{color:#dc4c64}</style>
