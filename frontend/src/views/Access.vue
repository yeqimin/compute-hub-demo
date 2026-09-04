<script setup lang="ts">
import{ref,onMounted}from'vue';import{api}from'../api';import{useAuthStore}from'../stores/auth';const auth=useAuthStore(),tenants=ref<any[]>([]),users=ref<any[]>([]),roles=ref<any[]>([]);onMounted(async()=>{roles.value=await api.get('/roles');if(auth.isAdmin)[tenants.value,users.value]=await Promise.all([api.get('/tenants'),api.get('/users')]);else users.value=await api.get('/users')})
</script>
<template>
  <el-tabs type="border-card" class="panel" style="padding: 0">
    <el-tab-pane label="租户">
      <el-table :data="tenants">
        <el-table-column prop="code" label="租户编码" />
        <el-table-column prop="name" label="租户名称" />
        <el-table-column prop="status" label="状态">
          <template #default><el-tag type="success">正常</el-tag></template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" />
      </el-table>
    </el-tab-pane>
    <el-tab-pane label="用户">
      <el-table :data="users">
        <el-table-column prop="username" label="账号" />
        <el-table-column prop="displayName" label="姓名" />
        <el-table-column prop="tenantName" label="所属租户" />
        <el-table-column prop="roles" label="角色" />
        <el-table-column label="状态">
          <template #default><el-tag type="success">启用</el-tag></template>
        </el-table-column>
      </el-table>
    </el-tab-pane>
    <el-tab-pane label="角色与权限">
      <el-row :gutter="16">
        <el-col v-for="r in roles" :key="r.id" :span="8">
          <div class="panel" style="box-shadow: none">
            <b>{{ r.name }}</b><div class="muted" style="margin: 8px 0">{{ r.code }}</div>
            <el-tag v-if="r.code === 'PLATFORM_ADMIN'">全部管理权限</el-tag>
            <el-tag v-else-if="r.code === 'TENANT_ADMIN'" type="success">租户业务权限</el-tag>
            <el-tag v-else type="info">只读权限</el-tag>
          </div>
        </el-col>
      </el-row>
    </el-tab-pane>
  </el-tabs>
</template>
