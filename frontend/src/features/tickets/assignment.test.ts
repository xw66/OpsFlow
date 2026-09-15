import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import TicketAssignment from './TicketAssignment.vue'
import { ApiError, post, request, session } from '../../api/http'
import type { AssignmentCandidate, CandidatePage, WorkspaceDetail } from '../../api/types'

vi.mock('../../api/http', async original => ({ ...await original<typeof import('../../api/http')>(), post: vi.fn(), request: vi.fn() }))
const candidate: AssignmentCandidate = { userId: 8, username: 'support8', displayName: '林晓', groupId: 2, groupName: '网络支持', online: true, activeCount: 2, lastAssignedAt: null }
const page: CandidatePage = { items: [candidate], hasMore: false, offset: 0, limit: 20, ticketVersion: 3 }
const data = (status = 'PROCESSING', version = 3, manager = false) => ({ staff: true, manager,
  detail: { ticket: { id: 1, groupId: 1, version, status } } }) as WorkspaceDetail
const wrappers: ReturnType<typeof mount>[] = []
function open(props = data()) {
  const wrapper = mount(TicketAssignment, { props: { data: props, disabled: false } }); wrappers.push(wrapper); return wrapper
}
beforeEach(() => { vi.mocked(request).mockReset().mockResolvedValue(page); vi.mocked(post).mockReset(); session.token.value = 'test-token' })
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0; session.token.value = null })

it('普通提交人看不到转派，CREATED只有实际管理者可分配', async () => {
  const wrapper = open({ ...data(), staff: false })
  expect(wrapper.find('button').exists()).toBe(false)
  await wrapper.setProps({ data: data('CREATED') })
  expect(wrapper.find('button').exists()).toBe(false)
  await wrapper.setProps({ data: data('CREATED', 3, true) })
  expect(wrapper.get('button').text()).toBe('分配工单')
  await wrapper.get('button').trigger('click'); await flushPromises()
  await wrapper.get('input[type="radio"]').setValue()
  await wrapper.get('textarea').setValue('安排网络支持处理')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(post).toHaveBeenCalledWith('/api/tickets/1/assign', { assigneeId: 8, version: 3, reason: '安排网络支持处理' })
})

it('转派展示真实姓名和负载，跨组影响明确，确认才提交', async () => {
  const wrapper = open()
  await wrapper.get('button').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('林晓')
  expect(wrapper.text()).toContain('活动工单 2')
  await wrapper.get('input[type="radio"]').setValue()
  expect(wrapper.text()).toContain('当前组可能失去访问权限')
  expect(post).not.toHaveBeenCalled()
  await wrapper.get('textarea').setValue('需要网络组排查')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(post).toHaveBeenCalledWith('/api/tickets/1/transfer', { assigneeId: 8, version: 3, reason: '需要网络组排查' })
  expect(wrapper.emitted('changed')).toHaveLength(1)
  expect(wrapper.text()).toContain('转派已完成')
})

it('候选失效409保留说明，禁止自动套用新版本重复转派', async () => {
  vi.mocked(post).mockRejectedValueOnce(new ApiError(409, 'INVALID_ASSIGNEE', '客服已经离线'))
  const wrapper = open()
  await wrapper.get('button').trigger('click'); await flushPromises()
  await wrapper.get('input[type="radio"]').setValue()
  await wrapper.get('textarea').setValue('保留转派说明')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  await wrapper.setProps({ data: data('PROCESSING', 4) })
  await wrapper.get('form').trigger('submit')
  expect(post).toHaveBeenCalledTimes(1)
  expect(wrapper.get('textarea').element.value).toBe('保留转派说明')
  expect(wrapper.find('input[type="radio"]').exists()).toBe(false)
  vi.mocked(request).mockResolvedValueOnce({ ...page, ticketVersion: 4 })
  await wrapper.findAll('button').find(button => button.text() === '重新读取候选')!.trigger('click'); await flushPromises()
  await wrapper.get('input[type="radio"]').setValue()
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(vi.mocked(post).mock.calls[1]?.[1]).toEqual({ assigneeId: 8, version: 4, reason: '保留转派说明' })
})

it('搜索结果为空和候选版本过期都不能提交', async () => {
  vi.mocked(request).mockResolvedValueOnce({ ...page, items: [] }).mockResolvedValueOnce({ ...page, ticketVersion: 4 })
  const wrapper = open()
  await wrapper.get('button').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('没有符合条件的可用客服')
  await wrapper.get('input[maxlength="64"]').setValue('不存在的姓名')
  await wrapper.findAll('button').find(button => button.text() === '搜索 / 刷新候选')!.trigger('click'); await flushPromises()
  expect(wrapper.emitted('changed')).toHaveLength(1)
  expect(wrapper.find('input[type="radio"]').exists()).toBe(false)
  await wrapper.get('form').trigger('submit')
  expect(post).not.toHaveBeenCalled()
})
