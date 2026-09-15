import { shallowRef } from 'vue'
import type { User } from './types'
import { confirmDiscard } from '../unsaved'

export const session = { token: shallowRef<string | null>(null), user: shallowRef<User | null>(null) }
const cooldowns = new Map<string, number>()
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public retryAfter = 0) { super(message) }
}
export function logout() {
  if (!confirmDiscard()) return
  session.token.value = null; session.user.value = null; cooldowns.clear()
}
export function errorText(error: unknown) { return error instanceof Error ? error.message : '操作失败，请稍后重试' }

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await rawRequest(path, options)
  const body = await response.json()
  return body.data as T
}
export async function rawRequest(path: string, options: RequestInit = {}): Promise<Response> {
  const route = `${options.method || 'GET'}:${path.split('?')[0]}`
  const remaining = Math.ceil(((cooldowns.get(route) || 0) - Date.now()) / 1000)
  if (remaining > 0) throw new ApiError(429, 'RATE_LIMITED', `请在 ${remaining} 秒后重试`, remaining)
  const token = session.token.value
  const headers = new Headers(options.headers)
  if (token && !headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`)
  if (typeof options.body === 'string') headers.set('Content-Type', 'application/json')
  let response: Response
  try {
    response = await fetch(path, { ...options, headers, signal: options.signal
      ? AbortSignal.any([options.signal, AbortSignal.timeout(20000)]) : AbortSignal.timeout(20000) })
  } catch (error) {
    if (options.signal?.aborted) throw error
    throw new ApiError(0, 'NETWORK_ERROR', '连接中断或请求超时，请保留输入并重试')
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    if (response.status === 401 && token && token === session.token.value && headers.get('Authorization') === `Bearer ${token}`) session.token.value = null
    const retryAfter = Number(response.headers.get('Retry-After')) || 0
    if (retryAfter > 0) cooldowns.set(route, Date.now() + retryAfter * 1000)
    throw new ApiError(response.status, body.code || 'REQUEST_FAILED',
      `${body.message || '服务暂不可用'}${retryAfter > 0 ? `，请在 ${retryAfter} 秒后重试` : ''}`, retryAfter)
  }
  return response
}
export const post = <T>(path: string, body: unknown, headers?: HeadersInit) =>
  request<T>(path, { method: 'POST', body: JSON.stringify(body), headers })

export async function login(username: string, password: string) {
  const result = await post<{ accessToken: string }>('/api/auth/login', { username, password })
  const user = await request<User>('/api/auth/me', { headers: { Authorization: `Bearer ${result.accessToken}` } })
  if (session.user.value && session.user.value.id !== user.id && !confirmDiscard()) {
    throw new ApiError(0, 'ACCOUNT_SWITCH_CANCELLED', '已取消切换账号，原账号的未保存内容仍保留。')
  }
  cooldowns.clear()
  session.user.value = user
  session.token.value = result.accessToken
}
