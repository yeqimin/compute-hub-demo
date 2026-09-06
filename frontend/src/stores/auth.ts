import { defineStore } from 'pinia'
import { api } from '../api'
import type { RoleCode } from '../types/api'
export type User = { id:number; tenantId:number|null; username:string; displayName:string; roles:RoleCode[]; permissions:string[] }
export const useAuthStore = defineStore('auth', {
  state: () => ({ user: JSON.parse(localStorage.getItem('compute-user') || 'null') as User|null }),
  getters: { isAdmin: s => !!s.user?.roles.includes('PLATFORM_ADMIN'), can: s => (p:string) => !!s.user?.permissions.includes(p) },
  actions: { async login(username:string,password:string) { const data = await api.post<{ token: string; user: User }>('/auth/login',{username,password}); localStorage.setItem('compute-token',data.token); localStorage.setItem('compute-user',JSON.stringify(data.user)); this.user=data.user }, logout(){localStorage.removeItem('compute-token');localStorage.removeItem('compute-user');this.user=null} }
})
