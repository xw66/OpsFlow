import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError, login, logout, request, session } from './http'
import { registerUnsaved } from '../unsaved'
const fetchMock = vi.fn()
beforeEach(() => { logout(); vi.stubGlobal('fetch', fetchMock); fetchMock.mockReset() })
afterEach(() => { vi.unstubAllGlobals(); logout() })
it('Bearer只在请求头，旧请求401不会注销新会话', async () => {
  session.token.value = 'old-token'
  fetchMock.mockImplementation(async () => { session.token.value = 'new-token'; return new Response(JSON.stringify({ message: '已过期' }), { status: 401 }) })
  await expect(request('/api/test')).rejects.toBeInstanceOf(ApiError)
  expect(new Headers(fetchMock.mock.calls[0][1].headers).get('Authorization')).toBe('Bearer old-token')
  expect(session.token.value).toBe('new-token')
  expect(localStorage.length).toBe(0)
})
it('429按照Retry-After阻止立即重复请求', async () => {
  fetchMock.mockResolvedValue(new Response(JSON.stringify({ message: '请求频繁' }), { status: 429, headers: { 'Retry-After': '60' } }))
  await expect(request('/api/limited')).rejects.toMatchObject({ status: 429, retryAfter: 60 })
  await expect(request('/api/limited')).rejects.toMatchObject({ status: 429 })
  expect(fetchMock).toHaveBeenCalledTimes(1)
})
it('显式验证另一个令牌失败不能使当前会话失效', async () => {
  session.token.value = 'current-token'
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ message: '令牌无效' }), { status: 401 }))
  await expect(request('/api/auth/me', { headers: { Authorization: 'Bearer other-token' } })).rejects.toMatchObject({ status: 401 })
  expect(session.token.value).toBe('current-token')
})
it('身份读取成功之前不发布新令牌', async () => {
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ data: { accessToken: 'new-token' } })))
    .mockImplementationOnce(async () => {
      expect(session.token.value).toBeNull()
      return new Response(JSON.stringify({ data: { id: 1, displayName: '林晓', roles: ['USER'] } }))
    })
  await login('linxiao', 'example_password')
  expect(session.user.value?.id).toBe(1)
  expect(session.token.value).toBe('new-token')
})
it('取消账号切换保留原会话，同账号重新登录不丢弃草稿', async () => {
  const unregister = registerUnsaved(() => true)
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  session.user.value = { id: 1, username: 'owner', displayName: '用户', enabled: true, roles: ['USER'] }
  session.token.value = 'original-token'
  try {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ data: { accessToken: 'other-token' } })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { ...session.user.value, id: 2 } })))
    await expect(login('other', 'example_password')).rejects.toMatchObject({ code: 'ACCOUNT_SWITCH_CANCELLED' })
    expect(session.user.value.id).toBe(1)
    expect(session.token.value).toBe('original-token')
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ data: { accessToken: 'renewed-token' } })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: session.user.value })))
    await login('owner', 'example_password')
    expect(session.token.value).toBe('renewed-token')
    expect(confirm).toHaveBeenCalledTimes(1)
  } finally { unregister(); confirm.mockRestore() }
})
