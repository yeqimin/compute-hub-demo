import axios from 'axios'
import { ElMessage } from 'element-plus'
export const api: any = axios.create({ baseURL: '/api/v1', timeout: 10000 })
api.interceptors.request.use((c: any) => { const token = localStorage.getItem('compute-token'); if (token) c.headers.Authorization = `Bearer ${token}`; return c })
api.interceptors.response.use((r: any) => r.data.data, (e: any) => { const msg = e.response?.data?.message || e.message || '请求失败'; ElMessage.error(msg); if (e.response?.status === 401) { localStorage.removeItem('compute-token'); location.href = '/login' } return Promise.reject(e) })
export const idemKey = () => crypto.randomUUID()
