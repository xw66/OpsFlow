import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from '../../App.vue'
import TicketDetailPage from './TicketDetailPage.vue'
import TicketAttachments from './TicketAttachments.vue'
import { ApiError, post, request, session } from '../../api/http'
import type { Comment, WorkspaceDetail } from '../../api/types'

vi.mock('../../api/http', async original => ({ ...await original<typeof import('../../api/http')>(), post: vi.fn(), request: vi.fn() }))
const wrappers: ReturnType<typeof mount>[] = []
const requestMock = vi.mocked(request), postMock = vi.mocked(post)
function detail(id: number, staff = true): WorkspaceDetail {
  const display = { id, title: `工单${id}`, userId: 1, userName: '用户', categoryId: 1, categoryName: '网络', groupId: 1,
    groupName: '支持组', assigneeId: 2, assigneeName: '客服', priority: 'HIGH' as const, status: 'PROCESSING' as const,
    version: 3, createdAt: '2026-09-14T01:00:00Z', responseDeadline: '2026-09-14T02:00:00Z',
    resolveDeadline: '2026-09-14T03:00:00Z', firstResponseAt: null, resolvedAt: null,
    responseBreached: false, resolveBreached: false, escalationLevel: 0 }
  return { staff, manager: false, display, detail: { tags: [], ticket: { ...display, description: `问题描述${id}`,
    slaCycle: 0, cycleStartedAt: display.createdAt, closedAt: null, cancelledAt: null } } }
}
const privateMessage: Comment = { id: 1, authorId: 2, content: '服务端内部排查记录', internal: true, createdAt: '2026-09-14T01:10:00Z' }
async function open() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tickets/:id', component: TicketDetailPage }] })
  await router.push('/tickets/1')
  const wrapper = mount(App, { global: { plugins: [router] } })
  wrappers.push(wrapper); await flushPromises()
  return { wrapper, router }
}
beforeEach(() => {
  session.user.value = { id: 2, username: 'agent', displayName: '客服', enabled: true, roles: ['AGENT'] }
  session.token.value = 'test-token'
  requestMock.mockReset(); postMock.mockReset()
  requestMock.mockImplementation(async path => path.startsWith('/api/workspace/') ? detail(Number(path.split('/').pop())) : path.includes('/comments?') ? [privateMessage] : [])
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(() => {
  wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0
  session.token.value = null; session.user.value = null; vi.restoreAllMocks()
})

it('回复403清除服务端工单和内部记录，保留不可发送的本地草稿', async () => {
  const { wrapper } = await open()
  expect(wrapper.text()).toContain(privateMessage.content)
  await wrapper.get('.composer textarea').setValue('本地未发送内容')
  postMock.mockRejectedValueOnce(new ApiError(403, 'FORBIDDEN', '工单访问权限已失效'))
  await wrapper.get('.composer').trigger('submit'); await flushPromises()
  expect(wrapper.text()).toContain('工单访问权限已失效')
  expect(wrapper.text()).not.toContain('问题描述1')
  expect(wrapper.text()).not.toContain(privateMessage.content)
  expect(wrapper.get<HTMLTextAreaElement>('.composer textarea').element.value).toBe('本地未发送内容')
  await wrapper.get('.composer').trigger('submit')
  expect(postMock).toHaveBeenCalledTimes(1)
})

it('重新授权为普通用户时过滤并发读取到的旧内部评论', async () => {
  const { wrapper } = await open()
  requestMock.mockImplementation(async path => path.startsWith('/api/workspace/') ? detail(1, false) : path.includes('/comments?') ? [privateMessage] : [])
  session.token.value = 'renewed-token'; await flushPromises()
  expect(wrapper.text()).toContain('问题描述1')
  expect(wrapper.text()).not.toContain(privateMessage.content)
})

it('加载更多的旧响应不能追加到另一张工单', async () => {
  let finish!: (value: Comment[]) => void
  requestMock.mockImplementation(async path => {
    if (path.startsWith('/api/workspace/')) return detail(Number(path.split('/').pop()))
    if (path.includes('offset=')) return new Promise<Comment[]>(resolve => { finish = resolve })
    if (path.includes('/1/comments')) return Array.from({ length: 100 }, (_, id) => ({ ...privateMessage, id }))
    return []
  })
  const { wrapper, router } = await open()
  await wrapper.findAll('button').find(button => button.text() === '加载后续交流')!.trigger('click')
  await router.push('/tickets/2'); await flushPromises()
  finish([{ ...privateMessage, id: 200, content: '旧工单迟到的内容' }]); await flushPromises()
  expect(wrapper.text()).toContain('问题描述2')
  expect(wrapper.text()).not.toContain('旧工单迟到的内容')
})

it('退出与工单参数切换都保护草稿，确认离开后新页面不继承草稿', async () => {
  const { wrapper, router } = await open()
  await wrapper.get('.composer textarea').setValue('待保存草稿')
  vi.mocked(window.confirm).mockReturnValue(false)
  await wrapper.findAll('button').find(button => button.text() === '退出登录')!.trigger('click')
  expect(session.user.value?.id).toBe(2)
  await router.push('/tickets/2')
  expect(router.currentRoute.value.path).toBe('/tickets/1')
  expect(wrapper.get<HTMLTextAreaElement>('.composer textarea').element.value).toBe('待保存草稿')
  vi.mocked(window.confirm).mockReturnValue(true)
  await router.push('/tickets/2'); await flushPromises()
  expect(wrapper.get<HTMLTextAreaElement>('.composer textarea').element.value).toBe('')
  const confirmations = vi.mocked(window.confirm).mock.calls.length
  await wrapper.findAll('button').find(button => button.text() === '退出登录')!.trigger('click')
  expect(session.user.value).toBeNull()
  expect(window.confirm).toHaveBeenCalledTimes(confirmations)
})

it('会话失效中止连续附件上传，不使用新账号继续上传旧文件', async () => {
  let finish!: (value: unknown) => void
  requestMock.mockImplementation(async (path, options) => {
    if (path.startsWith('/api/workspace/')) return detail(1)
    if (options?.method === 'POST') return new Promise(resolve => { finish = resolve })
    return []
  })
  const wrapper = mount(TicketAttachments, { props: { ticketId: 1, canUpload: true,
    initialFiles: [new File(['pdf1'], 'one.pdf'), new File(['pdf2'], 'two.pdf')] } })
  wrappers.push(wrapper); await flushPromises()
  session.token.value = null
  finish({ id: 1 }); await flushPromises()
  expect(requestMock.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
  expect(wrapper.emitted('changed')).toBeUndefined()
  expect(wrapper.text()).toContain('one.pdf、two.pdf')
})
