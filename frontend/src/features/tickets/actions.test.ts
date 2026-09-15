import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import TicketActions from './TicketActions.vue'
import type { User, WorkspaceDetail } from '../../api/types'
import { ApiError, post } from '../../api/http'
vi.mock('../../api/http', async original => ({ ...await original<typeof import('../../api/http')>(), post: vi.fn() }))
const user: User = { id: 5, username: 'agent', displayName: '客服', enabled: true, roles: ['AGENT'] }
function data(status: string, version = 1, assigneeId = 5): WorkspaceDetail {
  return { staff: true, manager: true, detail: { ticket: { id: 10, status, version, assigneeId }, tags: [] }, display: {} } as unknown as WorkspaceDetail
}
afterEach(() => vi.mocked(post).mockReset())
it('接单只向当前负责人显示，组长可恢复但不能代接单', async () => {
  const wrapper = mount(TicketActions, { props: { data: data('ASSIGNED', 1, 9), user, disabled: false } })
  expect(wrapper.text()).not.toContain('开始处理')
  await wrapper.setProps({ data: data('ASSIGNED') }); expect(wrapper.text()).toContain('开始处理')
  await wrapper.setProps({ user: { ...user, roles: ['LEADER'] } }); expect(wrapper.text()).not.toContain('开始处理')
  await wrapper.setProps({ data: data('PENDING') }); expect(wrapper.text()).toContain('恢复处理')
  await wrapper.setProps({ data: data('CLOSED') }); expect(wrapper.text()).toContain('重新打开')
  await wrapper.setProps({ data: { ...data('CLOSED'), manager: false } }); expect(wrapper.text()).not.toContain('重新打开')
  await wrapper.setProps({ data: { ...data('PROCESSING'), staff: false } }); expect(wrapper.findAll('button')).toHaveLength(0)
  wrapper.unmount()
})
it('409后固定原动作并保留原因，不能把解决变成关闭', async () => {
  vi.mocked(post).mockRejectedValueOnce(new ApiError(409, 'VERSION_CONFLICT', '工单已变化'))
  const wrapper = mount(TicketActions, { props: { data: data('PROCESSING'), user, disabled: false } })
  await wrapper.findAll('button').find(b => b.text() === '标记已解决')!.trigger('click')
  await wrapper.get('textarea').setValue('网络已恢复')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(post).toHaveBeenCalledWith('/api/tickets/10/resolve', { version: 1, reason: '网络已恢复' })
  expect(wrapper.emitted('changed')).toHaveLength(1)
  await wrapper.setProps({ data: data('RESOLVED', 2) })
  expect(wrapper.get('h2').text()).toBe('标记已解决')
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('网络已恢复')
  await wrapper.get('form').trigger('submit'); expect(post).toHaveBeenCalledTimes(1)
  wrapper.unmount()
})
it('处理中可以挂起，公开说明经确认携带版本提交', async () => {
  vi.mocked(post).mockResolvedValueOnce({})
  const wrapper = mount(TicketActions, { props: { data: data('PROCESSING', 8), user, disabled: false } })
  await wrapper.findAll('button').find(b => b.text() === '等待用户补充')!.trigger('click')
  expect(wrapper.text()).toContain('作为公开回复')
  await wrapper.get('form').trigger('submit'); expect(post).not.toHaveBeenCalled()
  await wrapper.get('textarea').setValue('请补充错误截图'); await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(post).toHaveBeenCalledWith('/api/tickets/10/suspend', { version: 8, reason: '请补充错误截图' })
  expect(wrapper.find('form').exists()).toBe(false)
  wrapper.unmount()
})
