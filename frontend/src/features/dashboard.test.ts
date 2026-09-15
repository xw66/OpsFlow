import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import NotificationPage from './notifications/NotificationPage.vue'
import StatisticsPage from './statistics/StatisticsPage.vue'
import { ApiError, request, session } from '../api/http'

vi.mock('../api/http', async original => ({ ...await original<typeof import('../api/http')>(), request: vi.fn() }))
const requestMock = vi.mocked(request)
beforeEach(() => { session.token.value = 'token'; session.user.value = { id: 1, username: 'lead', displayName: '组长', enabled: true, roles: ['LEADER'] }; requestMock.mockReset() })
afterEach(() => { session.token.value = null; session.user.value = null; vi.restoreAllMocks() })

it('通知和统计在权限失效后清空旧数据', async () => {
  requestMock.mockRejectedValue(new ApiError(403, 'FORBIDDEN', '无权限'))
  const notifications = mount(NotificationPage, { global: { stubs: { RouterLink: true } } })
  await flushPromises()
  expect(notifications.text()).not.toContain('暂无通知。')
  notifications.unmount()

  const statistics = mount(StatisticsPage)
  await flushPromises()
  expect(statistics.find('.metric-grid').exists()).toBe(false)
  expect(statistics.text()).toContain('无权限')
  statistics.unmount()
})

it('统计默认查询包含上海今天，并保留结束日期不包含的口径', async () => {
  requestMock.mockResolvedValueOnce({ totals: { createdCount: 0, averageResponseSeconds: null, averageResolveSeconds: null, overdueCount: 0 }, slaAchievementRate: null, aiClassificationAcceptanceRate: null, categories: [], agents: [], groups: [] }).mockResolvedValueOnce([])
  const wrapper = mount(StatisticsPage)
  await flushPromises()
  const calls = requestMock.mock.calls.map(([path]) => String(path))
  expect(calls[0]).toMatch(/overview\?from=\d{4}-\d{2}-01&until=\d{4}-\d{2}-\d{2}/)
  expect(wrapper.text()).toContain('结束日期不包含')
  wrapper.unmount()
})

it('统计新查询失败不展示上个区间的数据', async () => {
  requestMock.mockResolvedValueOnce({ totals: { createdCount: 123 }, categories: [], agents: [], groups: [] }).mockResolvedValueOnce([])
  const wrapper = mount(StatisticsPage)
  await flushPromises()
  expect(wrapper.find('.metric-grid').exists()).toBe(true)
  requestMock.mockRejectedValue(new ApiError(503, 'UNAVAILABLE', '查询失败'))
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(wrapper.find('.metric-grid').exists()).toBe(false)
  expect(wrapper.text()).toContain('查询失败')
  wrapper.unmount()
})
