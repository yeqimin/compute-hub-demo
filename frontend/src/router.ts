import { createRouter, createWebHistory } from 'vue-router'
import type { RoleCode } from './types/api'
import { hasAnyRole } from './utils/permissions'

const Login = () => import('./views/Login.vue')
const Dashboard = () => import('./views/Dashboard.vue')
const Resources = () => import('./views/Resources.vue')
const Products = () => import('./views/Products.vue')
const Instances = () => import('./views/Instances.vue')
const Tasks = () => import('./views/Tasks.vue')
const Access = () => import('./views/Access.vue')
const Billing = () => import('./views/Billing.vue')

const storedRoles = (): RoleCode[] => {
  try {
    const user = JSON.parse(localStorage.getItem('compute-user') || 'null') as { roles?: RoleCode[] } | null
    return user?.roles ?? []
  } catch {
    return []
  }
}

const router=createRouter({history:createWebHistory(),routes:[
  {path:'/login',component:Login,meta:{public:true}},{path:'/',redirect:'/dashboard'},
  {path:'/dashboard',component:Dashboard},{path:'/resources',component:Resources},{path:'/products',component:Products},
  {path:'/instances',component:Instances},{path:'/tasks',component:Tasks},{path:'/access',component:Access,meta:{roles:['PLATFORM_ADMIN','TENANT_ADMIN'] as RoleCode[]}},{path:'/billing',component:Billing}
]})
router.beforeEach((to) => {
  if (!to.meta.public && !localStorage.getItem('compute-token')) return '/login'
  if (to.path === '/login' && localStorage.getItem('compute-token')) return '/dashboard'
  const allowedRoles = to.meta.roles as RoleCode[] | undefined
  if (allowedRoles && !hasAnyRole(storedRoles(), allowedRoles)) return '/dashboard'
})
export default router
