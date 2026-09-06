import axios, { type AxiosError, type AxiosInstance, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { ApiError, type ApiResponse } from './types/api'

type ApiClient = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T>
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T>
}

export const http: AxiosInstance = axios.create({ baseURL: '/api/v1', timeout: 10000 })

export const authNavigation = {
  redirectToLogin: (path: string) => window.location.assign(path),
}

export const handleUnauthorized = () => {
  localStorage.removeItem('compute-token')
  localStorage.removeItem('compute-user')
  authNavigation.redirectToLogin('/login')
}

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('compute-token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<unknown>
    if (body.code !== 'SUCCESS') throw new ApiError(response.status, body.code, body.message, body.traceId)
    response.data = body.data
    return response
  },
  (reason: AxiosError<ApiResponse<unknown>>) => {
    const body = reason.response?.data
    const error = new ApiError(
      reason.response?.status ?? 0,
      body?.code ?? 'NETWORK_ERROR',
      body?.message ?? reason.message ?? '请求失败',
      body?.traceId,
    )
    ElMessage.error(error.message)
    if (error.status === 401) {
      handleUnauthorized()
    }
    return Promise.reject(error)
  },
)

const unwrap = <T>(request: Promise<AxiosResponse<T>>) => request.then((response) => response.data)

export const api: ApiClient = {
  get: <T>(url: string, config?: AxiosRequestConfig) => unwrap(http.get<T>(url, config)),
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) => unwrap(http.post<T>(url, data, config)),
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) => unwrap(http.put<T>(url, data, config)),
  delete: <T>(url: string, config?: AxiosRequestConfig) => unwrap(http.delete<T>(url, config)),
}
export const idemKey = () => crypto.randomUUID()
