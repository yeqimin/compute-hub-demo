import { createRouter, createWebHistory } from 'vue-router'
import Login from './views/Login.vue'
import Dashboard from './views/Dashboard.vue'
import Resources from './views/Resources.vue'
import Products from './views/Products.vue'
import Instances from './views/Instances.vue'
import Access from './views/Access.vue'
import Billing from './views/Billing.vue'
const router=createRouter({history:createWebHistory(),routes:[
  {path:'/login',component:Login,meta:{public:true}},{path:'/',redirect:'/dashboard'},
  {path:'/dashboard',component:Dashboard},{path:'/resources',component:Resources},{path:'/products',component:Products},
  {path:'/instances',component:Instances},{path:'/access',component:Access},{path:'/billing',component:Billing}
]})
router.beforeEach(to=>{if(!to.meta.public&&!localStorage.getItem('compute-token'))return'/login';if(to.path==='/login'&&localStorage.getItem('compute-token'))return'/dashboard'})
export default router
