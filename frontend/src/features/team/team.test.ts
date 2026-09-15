import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import TeamWorkPage from './TeamWorkPage.vue'
import MemberWorkload from './MemberWorkload.vue'
import { ApiError, request, session } from '../../api/http'
import type { SupportGroup, WorkloadPage } from '../../api/types'

vi.mock('../../api/http', async original => ({ ...await original<typeof import('../../api/http')>(), request: vi.fn() }))
const requestMock = vi.mocked(request), wrappers: ReturnType<typeof mount>[] = []
const groups: SupportGroup[] = [{ id: 1, name: '网络支持组', leaderId: 3, enabled: true, version: 0 }, { id: 2, name: '设备支持组', leaderId: 3, enabled: false, version: 0 }]
const workload = (name: string, offset = 0, hasMore = false): WorkloadPage => ({ items: [{ userId: 8, username: 'agent', displayName: name, online: true, available: true, activeCount: 3, processingCount: 1 }], offset, limit: 20, hasMore })
function defaults(path: string) {
  if (path.startsWith('/api/support/groups?')) return groups
  if (path.includes('/workload?')) return workload('客服小周')
  if (path.includes('/categories?')) return []
  return { items: [], hasMore: false, offset: 0, limit: 20 }
}
async function page(path = '/team') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/team', component: TeamWorkPage }] })
  await router.push(path)
  const wrapper = mount(TeamWorkPage, { global: { plugins: [router] } }); wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}
beforeEach(() => {
  session.user.value = { id: 3, username: 'leader', displayName: '组长', enabled: true, roles: ['LEADER'] }
  session.token.value = 'team-test'
  requestMock.mockReset().mockImplementation(async path => defaults(path))
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); session.token.value = null; session.user.value = null; vi.restoreAllMocks() })

it('普通用户不能加载团队数据，未知组不会静默切换到其他组', async () => {
  session.user.value = { ...session.user.value!, roles: ['USER'] }
  const { wrapper } = await page()
  expect(wrapper.text()).toContain('仅向组长和管理员开放')
  expect(requestMock).not.toHaveBeenCalled()
  session.user.value = { ...session.user.value!, roles: ['LEADER'] }
  const other = await page('/team?groupId=999')
  expect(other.wrapper.text()).toContain('所选客服组不存在或不在你的管理范围')
  expect(other.wrapper.find('.members').exists()).toBe(false)
  expect(requestMock.mock.calls.some(([path]) => path.includes('groupId=999') || path.includes('/groups/999/'))).toBe(false)
})

it('团队筛选保留组与队列并重置分页，清除条件不退出待分配队列', async () => {
  const { wrapper, router } = await page('/team?groupId=1&queue=MANUAL&offset=20')
  expect(wrapper.text()).toContain('活动工单包含待接单、处理中、等待用户补充')
  expect(wrapper.text()).not.toContain('保持在线后等待工单分配')
  await wrapper.get('input[type=search]').setValue('网络异常')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(router.currentRoute.value.query).toMatchObject({ groupId: '1', queue: 'MANUAL', offset: '0', keyword: '网络异常' })
  const latest = requestMock.mock.calls.filter(([path]) => path.startsWith('/api/workspace/tickets?')).at(-1)![0]
  const params = new URL(latest, 'http://test').searchParams
  expect(Object.fromEntries(params)).toMatchObject({ view: 'MY_GROUP', groupId: '1', queue: 'MANUAL', offset: '0', keyword: '网络异常' })
  await wrapper.findAll('button').find(button => button.text() === '清除')!.trigger('click'); await flushPromises()
  expect(router.currentRoute.value.query).toMatchObject({ groupId: '1', queue: 'MANUAL', keyword: '' })
})

it('切组清空成员，迟到响应不能覆盖新组，失去成员权限同时隐藏队列', async () => {
  let finish!: (value: WorkloadPage) => void
  requestMock.mockImplementation(async path => path.includes('/groups/1/workload?') ? new Promise(resolve => { finish = resolve }) : defaults(path))
  const { wrapper } = await page('/team?groupId=1')
  await wrapper.get('select[aria-label="客服组"]').setValue('2'); await flushPromises()
  expect(wrapper.text()).toContain('该组已停用')
  finish(workload('旧组私有成员')); await flushPromises()
  expect(wrapper.text()).not.toContain('旧组私有成员')
  expect(wrapper.text()).toContain('客服小周')
  requestMock.mockImplementation(async path => {
    if (path.includes('/workload?')) throw new ApiError(403, 'FORBIDDEN', '权限已撤销')
    return defaults(path)
  })
  await wrapper.findAll('button').find(button => button.text() === '刷新工作量')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('团队访问权限已变化')
  expect(wrapper.text()).not.toContain('客服小周')
  expect(wrapper.find('form.filters').exists()).toBe(false)
})

it('成员按服务端分页，区分停用与离线，令牌失效清空负载', async () => {
  requestMock.mockResolvedValueOnce(workload('第一页成员', 0, true)).mockResolvedValueOnce({ ...workload('第二页成员', 20), items: [
    { ...workload('停用客服').items[0], available: false },
    { ...workload('离线客服').items[0], userId: 9, online: false, activeCount: 0, processingCount: 0 },
  ] })
  const wrapper = mount(MemberWorkload, { props: { groupId: 1 } }); wrappers.push(wrapper); await flushPromises()
  await wrapper.findAll('button').find(button => button.text() === '下一页成员')!.trigger('click'); await flushPromises()
  expect(requestMock.mock.calls[1][0]).toContain('offset=20')
  expect(wrapper.text()).not.toContain('第一页成员')
  expect(wrapper.text()).toContain('不可接单')
  expect(wrapper.findAll('tbody tr')[1].text()).toContain('离线')
  session.token.value = null; await flushPromises()
  expect(wrapper.find('tbody').exists()).toBe(false)
})

it('队列请求失败不显示为空队列，重新加载后才显示实际结果', async () => {
  requestMock.mockImplementation(async path => {
    if (path.startsWith('/api/workspace/')) throw new ApiError(503, 'UNAVAILABLE', '队列暂时不可用')
    return defaults(path)
  })
  const { wrapper } = await page()
  expect(wrapper.text()).toContain('队列暂时不可用')
  expect(wrapper.text()).not.toContain('没有符合条件的工单')
  expect(wrapper.text()).not.toContain('本页 0 张工单')
  requestMock.mockImplementation(async path => defaults(path))
  await wrapper.findAll('button').find(button => button.text() === '重新加载')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('没有符合条件的工单')
})
