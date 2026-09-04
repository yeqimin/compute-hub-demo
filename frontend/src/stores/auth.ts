import { defineStore } from 'pinia'
import { api } from '../api'
export type User = { id:number; tenantId:number|null; username:string; displayName:string; roles:string[]; permissions:string[] }
export const useAuthStore = defineStore('auth', {
  state: () => ({ user: JSON.parse(localStorage.getItem('compute-user') || 'null') as User|null }),
  getters: { isAdmin: s => !!s.user?.roles.includes('PLATFORM_ADMIN'), can: s => (p:string) => !!s.user?.permissions.includes(p) },
  actions: { async login(username:string,password:string) { const data:any = await api.post('/auth/login',{username,password}); localStorage.setItem('compute-token',data.token); localStorage.setItem('compute-user',JSON.stringify(data.user)); this.user=data.user }, logout(){localStorage.clear();this.user=null} }
})
