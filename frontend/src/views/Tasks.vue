<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { api } from '../api'
import { usePageQuery } from '../composables/usePageQuery'
import type { AsyncTask, Page, TaskState } from '../types/api'
import { statusMeta } from '../utils/status'

const query = usePageQuery<AsyncTask, { state: string; commandId: string }>(
  (params) => api.get<Page<AsyncTask>>('/tasks', { params }),
  { state: '', commandId: '' },
)
let timer: number | undefined

onMounted(() => {
  query.load()
  timer = window.setInterval(query.load, 5000)
})
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <div class="panel">
    <div class="toolbar">
      <div>
        <el-select v-model="query.filters.state" clearable placeholder="全部任务状态" style="width: 160px" @change="query.refresh">
          <el-option v-for="state in ['PENDING', 'PUBLISHED', 'PROCESSING', 'RETRY_WAIT', 'WAITING_CALLBACK', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'DEAD']" :key="state" :label="statusMeta(state).label" :value="state" />
        </el-select>
        <el-input v-model="query.filters.commandId" clearable placeholder="命令号" style="width: 220px" @keyup.enter="query.refresh" />
        <el-button :loading="query.loading.value" @click="query.refresh">刷新</el-button>
      </div>
      <span class="muted">每 5 秒自动刷新</span>
    </div>
    <el-alert v-if="query.error.value" :title="query.error.value.message" type="error" show-icon :closable="false" style="margin-bottom: 16px" />
    <el-table v-loading="query.loading.value" :data="query.items.value">
      <el-table-column prop="taskNo" label="任务号" min-width="150" />
      <el-table-column prop="commandId" label="命令号" min-width="210" />
      <el-table-column prop="instanceId" label="实例 ID" width="100" />
      <el-table-column prop="operation" label="操作" width="120" />
      <el-table-column label="状态" width="130"><template #default="scope"><el-tag :type="statusMeta(scope.row.state as TaskState).type">{{ statusMeta(scope.row.state as TaskState).label }}</el-tag></template></el-table-column>
      <el-table-column prop="retryCount" label="重试次数" width="100" />
      <el-table-column label="最近错误" min-width="180"><template #default="scope"><span class="muted">{{ scope.row.lastError || '-' }}</span></template></el-table-column>
    </el-table>
    <div class="pager"><el-pagination v-model:current-page="query.page.value" v-model:page-size="query.size.value" background layout="total, sizes, prev, pager, next" :total="query.total.value" @change="query.changePage(query.page.value, query.size.value)" /></div>
  </div>
</template>
