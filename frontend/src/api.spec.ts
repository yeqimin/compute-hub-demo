import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'

const messageError = vi.fn()
vi.mock('element-plus', () => ({ ElMessage: { error: messageError } }))
vi.mock('element-plus/es/components/message/style/css', () => ({}))

const response = (config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse => ({
  config,
  data,
  headers: {},
  status,
  statusText: status === 200 ? 'OK' : 'Unauthorized',
})

describe('API client', async () => {
  const { api, authNavigation, http } = await import('./api')
  const originalRedirect = authNavigation.redirectToLogin

  beforeEach(() => {
    localStorage.clear()
    messageError.mockReset()
    authNavigation.redirectToLogin = originalRedirect
  })

  afterEach(() => {
    http.defaults.adapter = undefined
    authNavigation.redirectToLogin = originalRedirect
  })

  it('rejects a successful HTTP envelope with an API error that preserves server metadata', async () => {
    http.defaults.adapter = async (config) => response(config, {
      code: 'VALIDATION_ERROR',
      message: '名称已存在',
      data: null,
      traceId: 'trace-envelope-1',
    })

    await expect(api.get('/instances')).rejects.toMatchObject({
      name: 'ApiError',
      status: 200,
      code: 'VALIDATION_ERROR',
      message: '名称已存在',
      traceId: 'trace-envelope-1',
    })
  })

  it('converts failed HTTP responses to API errors and displays their server message', async () => {
    http.defaults.adapter = async (config) => Promise.reject({
      config,
      message: 'Request failed with status code 429',
      response: response(config, {
        code: 'RATE_LIMITED',
        message: '请求过于频繁',
        data: null,
        traceId: 'trace-http-1',
      }, 429),
    })

    await expect(api.get('/instances')).rejects.toMatchObject({
      name: 'ApiError',
      status: 429,
      code: 'RATE_LIMITED',
      message: '请求过于频繁',
      traceId: 'trace-http-1',
    })
    expect(messageError).toHaveBeenCalledWith('请求过于频繁')
  })

  it('clears the session and delegates login navigation after an unauthorized response', async () => {
    const redirectToLogin = vi.fn()
    authNavigation.redirectToLogin = redirectToLogin
    localStorage.setItem('compute-token', 'expired-token')
    localStorage.setItem('compute-user', '{"id":1}')
    http.defaults.adapter = async (config) => Promise.reject({
      config,
      message: 'Unauthorized',
      response: response(config, { code: 'UNAUTHORIZED', message: '登录已过期', data: null, traceId: 'trace-401' }, 401),
    })

    await expect(api.get('/instances')).rejects.toMatchObject({ status: 401, code: 'UNAUTHORIZED' })

    expect(localStorage.getItem('compute-token')).toBeNull()
    expect(localStorage.getItem('compute-user')).toBeNull()
    expect(redirectToLogin).toHaveBeenCalledWith('/login')
  })
})
