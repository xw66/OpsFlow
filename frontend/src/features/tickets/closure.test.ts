import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import TicketForm from './TicketForm.vue'
import TicketEdit from './TicketEdit.vue'
import TicketAttachments from './TicketAttachments.vue'
import TicketHistoryPanel from './TicketHistoryPanel.vue'
import TicketQueue from './TicketQueue.vue'
import { ApiError, request, session } from '../../api/http'
import type { Attachment, TicketHistory, TicketInput, WorkspaceDetail } from '../../api/types'

vi.mock('../../api/http', async original => ({ ...await original<typeof import('../../api/http')>(), request: vi.fn() }))
const requestMock = vi.mocked(request), wrappers: ReturnType<typeof mount>[] = []
const initial: TicketInput = { title: '原工单标题', description: '原始问题描述', categoryId: 1, priority: 'HIGH', tags: ['网络'] }
function detail(version = 1): WorkspaceDetail {
  return { staff: false, manager: false, display: { categoryName: '网络' }, detail: { ticket: { id: 10, userId: 1, status: 'CREATED', version, ...initial }, tags: initial.tags } } as unknown as WorkspaceDetail
}
function track(wrapper: ReturnType<typeof mount>) { wrappers.push(wrapper); return wrapper }
beforeEach(() => {
  session.user.value = { id: 1, username: 'owner', displayName: '用户', roles: ['USER'], enabled: true }
  session.token.value = 'test-token'; requestMock.mockReset().mockResolvedValue([])
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0; session.user.value = null; session.token.value = null; vi.restoreAllMocks() })

it('原分类已停用仍可只改标题并沿用原SLA，不能提交新的无规则组合', async () => {
  const wrapper = track(mount(TicketForm, { props: { initial, initialCategoryName: '网络', disabled: false } }))
  await flushPromises()
  expect(wrapper.text()).toContain('沿用工单创建时的服务时限')
  expect(wrapper.findAll('select')[1].element.value).toBe('HIGH')
  await wrapper.get('input[maxlength="200"]').setValue('修改后的标题')
  await wrapper.get('form').trigger('submit')
  expect(wrapper.emitted('submit')?.[0]?.[0]).toEqual({ ...initial, title: '修改后的标题' })
  await wrapper.setProps({ disabled: true })
  await wrapper.get('form').trigger('submit')
  expect(wrapper.emitted('submit')).toHaveLength(1)
})

it('编辑409保留输入及原版本，核对后才能向新版本保存', async () => {
  requestMock.mockImplementation(async (_, options) => {
    if (options?.method === 'PUT') throw new ApiError(409, 'VERSION_CONFLICT', '工单已变更')
    return []
  })
  const wrapper = track(mount(TicketEdit, { props: { data: detail(), disabled: false } }))
  await wrapper.get('button').trigger('click'); await flushPromises()
  await wrapper.get('input[maxlength="200"]').setValue('未保存的修改')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  await wrapper.setProps({ data: detail(2) })
  expect(wrapper.get<HTMLInputElement>('input[maxlength="200"]').element.value).toBe('未保存的修改')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(requestMock.mock.calls.filter(([, options]) => options?.method === 'PUT')).toHaveLength(1)
  await wrapper.findAll('button').find(button => button.text() === '已核对最新内容，保留草稿继续编辑')!.trigger('click')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  const updates = requestMock.mock.calls.filter(([, options]) => options?.method === 'PUT')
  expect(updates.map(([, options]) => JSON.parse(String(options?.body)).version)).toEqual([1, 2])
  expect(JSON.parse(String(updates[1][1]?.body)).ticket.title).toBe('未保存的修改')
})

it('仅本人或管理员能删除附件，取消不写入，删除冲突不自动重试', async () => {
  const files: Attachment[] = [{ id: 8, originalName: '我的报告.pdf', sizeBytes: 20, uploaderId: 1, createdAt: '2026-09-14T01:00:00Z' },
    { id: 9, originalName: '客服报告.pdf', sizeBytes: 20, uploaderId: 2, createdAt: '2026-09-14T01:00:00Z' }]
  requestMock.mockImplementation(async (_, options) => { if (options?.method === 'DELETE') throw new ApiError(409, 'VERSION_CONFLICT', '版本已变更'); return files })
  const wrapper = track(mount(TicketAttachments, { props: { ticketId: 10, version: 8, canUpload: true } })); await flushPromises()
  expect(wrapper.findAll('button[aria-label^="删除附件"]')).toHaveLength(1)
  vi.mocked(window.confirm).mockReturnValue(false)
  await wrapper.get('button[aria-label="删除附件 我的报告.pdf"]').trigger('click'); await flushPromises()
  expect(requestMock.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false)
  vi.mocked(window.confirm).mockReturnValue(true)
  await wrapper.get('button[aria-label="删除附件 我的报告.pdf"]').trigger('click'); await flushPromises()
  expect(requestMock.mock.calls.filter(([, options]) => options?.method === 'DELETE').map(([path]) => path)).toEqual(['/api/tickets/10/attachments/8?version=8'])
  expect(wrapper.text()).toContain('请核对刷新后的附件列表')
  expect(wrapper.text()).toContain('我的报告.pdf')
  await wrapper.setProps({ canUpload: false })
  expect(wrapper.find('button[aria-label^="删除附件"]').exists()).toBe(false)
})

it('历史按需读取姓名和状态，权限失效后清空，迟到页不能恢复记录', async () => {
  const row: TicketHistory = { id: 1, ticketId: 10, fromStatus: 'CREATED', toStatus: 'ASSIGNED', operatorId: 2, operatorName: '组长陈明', remark: '分配给网络组', ticketVersion: 1, createdAt: '2026-09-14T01:00:00Z' }
  let finish!: (rows: TicketHistory[]) => void
  requestMock.mockResolvedValueOnce(Array.from({ length: 20 }, (_, id) => ({ ...row, id })))
    .mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const wrapper = track(mount(TicketHistoryPanel, { props: { ticketId: 10, version: 1, accessible: true } }))
  expect(requestMock).not.toHaveBeenCalled()
  wrapper.get('details').element.open = true
  await wrapper.get('details').trigger('toggle'); await flushPromises()
  expect(wrapper.text()).toContain('组长陈明')
  expect(wrapper.text()).toContain('已创建 → 待接单')
  await wrapper.findAll('button').find(button => button.text() === '加载更多记录')!.trigger('click')
  await wrapper.setProps({ accessible: false })
  finish([row]); await flushPromises()
  expect(wrapper.text()).not.toContain('组长陈明')
})

it('共享列表向服务端提交SLA筛选并重置分页，客服文案不会要求自己补充', async () => {
  requestMock.mockImplementation(async path => path.includes('/categories?') ? [] : { items: [], hasMore: false, offset: 20, limit: 20 })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/work', component: TicketQueue, props: { view: 'MINE_ASSIGNED' } }] })
  await router.push('/work?offset=20&sla=BREACHED')
  const wrapper = track(mount(TicketQueue, { props: { view: 'MINE_ASSIGNED' }, global: { plugins: [router] } })); await flushPromises()
  expect(wrapper.text()).toContain('等待用户补充')
  expect(wrapper.text()).not.toContain('待我补充')
  await wrapper.findAll('select')[3].setValue('WARNING')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(router.currentRoute.value.query.offset).toBe('0')
  const url = requestMock.mock.calls.filter(([path]) => path.startsWith('/api/workspace/')).at(-1)![0]
  expect(url).toContain('view=MINE_ASSIGNED')
  expect(url).toContain('sla=WARNING')
  expect(url).toContain('offset=0')
})
