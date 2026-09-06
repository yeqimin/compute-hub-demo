<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { appLocale } from './locale'
import { useAuthStore } from './stores/auth'
import { useUiStore } from './stores/ui'
import { hasAnyRole } from './utils/permissions'
import type { RoleCode } from './types/api'
import { DataBoard, Cpu, Box, Monitor, CreditCard, UserFilled, SwitchButton, List, Moon, Sunny, Monitor as SystemTheme } from '@element-plus/icons-vue'

type MenuItem = { path: string; label: string; icon: object; roles?: RoleCode[] }

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()
const active = computed(() => route.path)
const menus: MenuItem[] = [
  { path: '/dashboard', label: '运营总览', icon: DataBoard },
  { path: '/resources', label: '资源中心', icon: Cpu },
  { path: '/products', label: '产品管理', icon: Box },
  { path: '/instances', label: '实例控制台', icon: Monitor },
  { path: '/tasks', label: '任务中心', icon: List },
  { path: '/billing', label: '费用中心', icon: CreditCard },
  { path: '/access', label: '租户与权限', icon: UserFilled, roles: ['PLATFORM_ADMIN', 'TENANT_ADMIN'] },
]
const visibleMenus = computed(() => menus.filter((menu) => !menu.roles || hasAnyRole(auth.user?.roles ?? [], menu.roles)))
const currentMenu = computed(() => visibleMenus.value.find((menu) => menu.path === active.value))
const themeIcon = computed(() => ui.theme === 'light' ? Sunny : ui.theme === 'dark' ? Moon : SystemTheme)
const themeLabel = computed(() => ui.theme === 'light' ? '亮色主题' : ui.theme === 'dark' ? '深色主题' : '跟随系统')
const cycleTheme = () => ui.setTheme(ui.theme === 'light' ? 'dark' : ui.theme === 'dark' ? 'system' : 'light')
const quit = () => { auth.logout(); router.push('/login') }
</script>
<template><el-config-provider :locale="appLocale"><router-view v-if="route.path==='/login'"/><div v-else class="shell"><aside class="sidebar"><div class="brand"><div class="brand-mark">C</div><div><strong>ComputeHub</strong><small>算力调度平台</small></div></div><el-menu :default-active="active" router><el-menu-item v-for="menu in visibleMenus" :key="menu.path" :index="menu.path"><el-icon><component :is="menu.icon"/></el-icon><span>{{menu.label}}</span></el-menu-item></el-menu><div class="side-foot"><div class="avatar">{{auth.user?.displayName?.slice(0,1)}}</div><div class="who"><strong>{{auth.user?.displayName}}</strong><small>{{auth.user?.roles?.[0]}}</small></div><el-tooltip :content="themeLabel"><el-button text circle @click="cycleTheme"><el-icon><component :is="themeIcon"/></el-icon></el-button></el-tooltip><el-button text circle :icon="SwitchButton" @click="quit"/></div></aside><main><header><div><span class="eyebrow">COMPUTE OPERATIONS</span><h2>{{currentMenu?.label}}</h2></div><div class="live"><i/>系统运行正常</div></header><section class="page"><router-view/></section></main></div></el-config-provider></template>
