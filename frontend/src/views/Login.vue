<script setup lang="ts">
import { reactive,ref } from 'vue';import { useRouter } from 'vue-router';import { useAuthStore } from '../stores/auth';
const form=reactive({username:'tenant_admin',password:'Tenant@123'}),loading=ref(false),router=useRouter(),auth=useAuthStore();
const login=async()=>{loading.value=true;try{await auth.login(form.username,form.password);router.push('/dashboard')}finally{loading.value=false}}
</script>
<template><div class="login"><div class="login-card"><div class="login-logo"><div class="brand-mark">C</div><div><h1>ComputeHub</h1><p>智能算力调度与运营平台</p></div></div><el-form label-position="top" @keyup.enter="login"><el-form-item label="账号"><el-input v-model="form.username" size="large"/></el-form-item><el-form-item label="密码"><el-input v-model="form.password" type="password" show-password size="large"/></el-form-item><el-button type="primary" size="large" style="width:100%" :loading="loading" @click="login">进入控制台</el-button></el-form><div class="demo-accounts"><b>演示账号</b><br/>平台管理员：admin / Admin@123<br/>租户管理员：tenant_admin / Tenant@123<br/>只读访客：viewer / Viewer@123</div></div></div></template>
