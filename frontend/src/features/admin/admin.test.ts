import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import AdminPage from './AdminPage.vue'
import { ApiError, request, session } from '../../api/http'
import { allPages } from './adminData'
import type { AdminUserPage, SlaPolicy, SupportAgent, SupportCategory, SupportGroup, User } from '../../api/types'

vi.mock('../../api/http', async original => ({ ...await original<typeof import('../../api/http')>(), request: vi.fn() }))
const mock = vi.mocked(request), wrappers: VueWrapper[] = []
let users: User[], groups: SupportGroup[], categories: SupportCategory[], policies: SlaPolicy[], members: SupportAgent[]
function response(path: string) {
  const url = new URL(path, 'http://test'), offset = Number(url.searchParams.get('offset')), limit = Number(url.searchParams.get('limit') || 20)
  let data: unknown[] = []
  if (url.pathname === '/api/admin/users') {
    const keyword = url.searchParams.get('keyword'), role = url.searchParams.get('role'), enabled = url.searchParams.get('enabled')
    const selected = users.filter(u => (!keyword || u.displayName.includes(keyword) || u.username.includes(keyword)) && (!role || u.roles.some(r => r === role)) && (!enabled || String(u.enabled) === enabled))
    return { items: selected.slice(offset, offset + limit), hasMore: selected.length > offset + limit, offset, limit }
  }
  if (url.pathname === '/api/admin/support-groups') data = groups
  if (url.pathname === '/api/admin/ticket-categories') data = categories
  if (url.pathname === '/api/admin/sla-policies') data = policies
  if (url.pathname.includes('/agents')) data = members.filter(m => m.groupId === Number(url.pathname.split('/')[4]))
  return data.slice(offset, offset + limit)
}
const writes = () => mock.mock.calls.filter(([, options]) => options?.method && options.method !== 'GET')
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (reason: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function button(wrapper: VueWrapper, label: string) { const found = wrapper.findAll('button').find(b => b.text() === label); if (!found) throw new Error(`找不到按钮：${label}`); return found }
async function click(wrapper: VueWrapper, label: string) {
  const enabled = wrapper.findAll('button').find(b => b.text() === label && b.attributes('disabled') === undefined)
  if (!enabled) throw new Error(`找不到可用按钮：${label}`)
  await enabled.trigger('click'); await flushPromises()
}
async function page() { const wrapper = mount(AdminPage); wrappers.push(wrapper); await flushPromises(); return wrapper }
const editor = (wrapper: VueWrapper) => wrapper.get('form[aria-label="管理员编辑表单"]')
beforeEach(() => {
  users = Array.from({ length: 150 }, (_, i) => ({ id: i + 1, username: `account${i + 1}`, displayName: `姓名${i + 1}`, enabled: true, roles: i === 0 ? ['ADMIN'] : i === 148 ? ['USER', 'LEADER'] : ['USER', 'AGENT'] }))
  groups = Array.from({ length: 150 }, (_, i) => ({ id: i + 1, name: `支持组${i + 1}`, leaderId: 149, enabled: true, version: 7 }))
  categories = Array.from({ length: 150 }, (_, i) => ({ id: i + 1, code: `C${i + 1}`, name: `分类${i + 1}`, groupId: i + 1, enabled: true, version: 4 }))
  policies = [{ id: 1, categoryId: 150, priority: 'HIGH', responseMinutes: 30, resolveMinutes: 240, enabled: true, autoEscalate: true, version: 3 }]
  members = Array.from({ length: 25 }, (_, i) => ({ id: i + 1, userId: i + 2, groupId: 1, enabled: true, online: false, lastAssignedAt: null, version: 5 }))
  session.user.value = users[0]; session.token.value = 'admin-test-session'
  mock.mockReset().mockImplementation(async (path, options) => options?.method ? {} : response(path))
})
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); session.token.value = null; session.user.value = null; vi.restoreAllMocks() })

it('完整名称选择器读取超过100条，负责人按角色筛选，三种配置使用名称及原API字段', async () => {
  const wrapper = await page()
  for (const path of ['/api/admin/users', '/api/admin/support-groups', '/api/admin/ticket-categories']) expect(mock.mock.calls.some(([p]) => p === `${path}?offset=100&limit=100`)).toBe(true)
  await click(wrapper, '客服组'); expect(wrapper.get('tbody').text()).toContain('姓名149')
  await click(wrapper, '新增')
  const form = editor(wrapper)
  expect(form.get('select[name=leaderId]').text()).toContain('姓名149')
  expect(form.get('select[name=leaderId]').text()).not.toContain('姓名148')
  await form.get('input[name=name]').setValue('新组'); await form.get('select[name=leaderId]').setValue('149')
  await form.trigger('submit'); await flushPromises()
  expect(writes().at(-1)?.[0]).toBe('/api/admin/support-groups')
  expect(JSON.parse(writes().at(-1)![1]!.body as string)).toEqual({ name: '新组', leaderId: 149, enabled: true, version: 0 })
  await click(wrapper, '工单分类'); await click(wrapper, '新增')
  await editor(wrapper).get('input[name=name]').setValue('新分类'); await editor(wrapper).get('input[name=code]').setValue('NEW_CATEGORY')
  await editor(wrapper).get('select[name=groupId]').setValue('150'); await editor(wrapper).trigger('submit'); await flushPromises()
  expect(JSON.parse(writes().at(-1)![1]!.body as string)).toEqual({ name: '新分类', code: 'NEW_CATEGORY', groupId: 150, enabled: true, version: 0 })
  await click(wrapper, 'SLA规则'); expect(wrapper.get('tbody').text()).toContain('分类150'); await click(wrapper, '新增')
  await editor(wrapper).get('select[name=categoryId]').setValue('150'); await editor(wrapper).get('select[name=priority]').setValue('URGENT')
  await editor(wrapper).trigger('submit'); await flushPromises()
  expect(JSON.parse(writes().at(-1)![1]!.body as string)).toEqual({ categoryId: 150, priority: 'URGENT', responseMinutes: 60, resolveMinutes: 480, autoEscalate: true, enabled: true, version: 0 })
})

it('角色预填完整集合、切换用户清空原因、自身保护和提交中防重', async () => {
  const wrapper = await page(); await click(wrapper, '选择用户编辑角色')
  await editor(wrapper).get('select[name=userId]').setValue('150')
  expect(editor(wrapper).get<HTMLInputElement>('input[value=USER]').element.checked).toBe(true)
  expect(editor(wrapper).get<HTMLInputElement>('input[value=AGENT]').element.checked).toBe(true)
  await editor(wrapper).get('textarea[name=reason]').setValue('旧用户原因')
  await editor(wrapper).get('select[name=userId]').setValue('149')
  expect(editor(wrapper).get<HTMLTextAreaElement>('textarea[name=reason]').element.value).toBe('')
  expect(editor(wrapper).get<HTMLInputElement>('input[value=LEADER]').element.checked).toBe(true)
  await editor(wrapper).get('select[name=userId]').setValue('1')
  expect(button(wrapper, '保存').attributes('disabled')).toBeDefined()
  await editor(wrapper).trigger('submit'); expect(writes()).toHaveLength(0)
  await editor(wrapper).get('select[name=userId]').setValue('150')
  await editor(wrapper).get('input[value=LEADER]').setValue(true)
  await editor(wrapper).get('textarea[name=reason]').setValue('  增加组长职责  ')
  const pending = deferred<unknown>(); mock.mockImplementation(async (path, options) => options?.method ? pending.promise : response(path))
  await editor(wrapper).trigger('submit'); await editor(wrapper).trigger('submit')
  expect(writes()).toHaveLength(1)
  expect(writes()[0][0]).toBe('/api/admin/users/150/roles')
  expect(JSON.parse(writes()[0][1]!.body as string)).toEqual({ roles: ['USER', 'AGENT', 'LEADER'], reason: '增加组长职责' })
  expect(button(wrapper, '保存中…').attributes('disabled')).toBeDefined()
  pending.resolve({}); await flushPromises(); expect(wrapper.find('.admin-editor').exists()).toBe(false)
})

it('账号启停需要原因，取消无请求，保存完整目标状态', async () => {
  const wrapper = await page(); await click(wrapper, '停用账号')
  expect(editor(wrapper).get<HTMLInputElement>('input[name=enabled]').element.checked).toBe(false)
  await editor(wrapper).trigger('submit'); expect(writes()).toHaveLength(0)
  await click(wrapper, '取消'); expect(writes()).toHaveLength(0)
  await click(wrapper, '停用账号'); await editor(wrapper).get('textarea[name=reason]').setValue('离职停用')
  await editor(wrapper).trigger('submit'); await flushPromises()
  expect(JSON.parse(writes()[0][1]!.body as string)).toEqual({ enabled: false, reason: '离职停用' })
})

it('成员完整名称选择、分页、切组归零；编辑可移组停用并提交记录版本', async () => {
  const wrapper = await page(); await click(wrapper, '客服成员'); await wrapper.get('select[name=memberGroup]').setValue('1'); await flushPromises()
  expect(wrapper.get('tbody').text()).toContain('姓名2')
  await click(wrapper, '下一页成员'); expect(mock.mock.calls.some(([p]) => p.includes('/groups/1/agents?offset=20&limit=20'))).toBe(true)
  await wrapper.get('select[name=memberGroup]').setValue('2'); await flushPromises()
  expect(mock.mock.calls.at(-1)![0]).toContain('/groups/2/agents?offset=0')
  await wrapper.get('select[name=memberGroup]').setValue('1'); await flushPromises(); await click(wrapper, '编辑成员')
  expect(editor(wrapper).get('select[name=userId]').attributes('disabled')).toBeDefined()
  await editor(wrapper).get('select[name=groupId]').setValue('150'); await editor(wrapper).get('input[name=enabled]').setValue(false)
  await editor(wrapper).trigger('submit'); await flushPromises()
  expect(writes()[0][0]).toBe('/api/admin/support-agents/1')
  expect(JSON.parse(writes()[0][1]!.body as string)).toEqual({ userId: 2, groupId: 150, enabled: false, version: 5 })
  await click(wrapper, '新增成员'); await editor(wrapper).get('select[name=userId]').setValue('150')
  await editor(wrapper).trigger('submit'); await flushPromises()
  expect(writes()[1][0]).toBe('/api/admin/support-agents')
  expect(JSON.parse(writes()[1][1]!.body as string)).toMatchObject({ userId: 150, groupId: 1, version: 0 })
})

it('409保留输入，刷新最新记录后必须再确认，使用新版本而不覆盖草稿', async () => {
  const wrapper = await page(); await click(wrapper, '客服组'); await click(wrapper, '编辑')
  await editor(wrapper).get('input[name=name]').setValue('保留的编辑')
  mock.mockImplementation(async (path, options) => { if (options?.method) throw new ApiError(409, 'VERSION_CONFLICT', '记录已变化'); return response(path) })
  await editor(wrapper).trigger('submit'); await flushPromises()
  expect(editor(wrapper).get<HTMLInputElement>('input[name=name]').element.value).toBe('保留的编辑')
  await editor(wrapper).trigger('submit'); expect(writes()).toHaveLength(1)
  groups[0] = { ...groups[0], name: '他人已更新', version: 9 }
  await click(wrapper, '读取最新记录')
  expect(wrapper.text()).toContain('他人已更新'); expect(button(wrapper, '保存').attributes('disabled')).toBeDefined()
  expect(editor(wrapper).get<HTMLInputElement>('input[name=name]').element.value).toBe('保留的编辑')
  await editor(wrapper).get('input[name=confirmed]').setValue(true)
  mock.mockImplementation(async (path, options) => options?.method ? {} : response(path))
  await editor(wrapper).trigger('submit'); await flushPromises()
  expect(JSON.parse(writes()[1][1]!.body as string)).toMatchObject({ version: 9, name: '保留的编辑' })
})

it('失去角色的旧负责人及停用组/分类仍可保留关联并停用记录', async () => {
  users[148] = { ...users[148], roles: ['USER'], enabled: false }; groups[0].enabled = false; categories[149].enabled = false
  const wrapper = await page(); await click(wrapper, '客服组'); await click(wrapper, '编辑')
  expect(editor(wrapper).get('select[name=leaderId]').text()).toContain('姓名149')
  await editor(wrapper).get('input[name=enabled]').setValue(false); await editor(wrapper).trigger('submit'); await flushPromises()
  expect(JSON.parse(writes()[0][1]!.body as string)).toMatchObject({ leaderId: 149, enabled: false, version: 7 })
  await click(wrapper, '工单分类'); await click(wrapper, '编辑'); await editor(wrapper).get('input[name=enabled]').setValue(false)
  await editor(wrapper).trigger('submit'); await flushPromises(); expect(writes()).toHaveLength(2)
  await click(wrapper, 'SLA规则'); await click(wrapper, '编辑'); await editor(wrapper).get('input[name=enabled]').setValue(false)
  await editor(wrapper).trigger('submit'); await flushPromises(); expect(writes()).toHaveLength(3)
})

it('名称/代码/SLA校验与后端一致，无效输入不能绕过原生校验提交', async () => {
  const wrapper = await page(); await click(wrapper, '工单分类'); await click(wrapper, '编辑')
  await editor(wrapper).get('input[name=code]').setValue('bad-code'); await editor(wrapper).trigger('submit'); expect(writes()).toHaveLength(0)
  await editor(wrapper).get('input[name=code]').setValue('GOOD_CODE'); await editor(wrapper).get('input[name=name]').setValue('文'.repeat(65)); await editor(wrapper).trigger('submit'); expect(writes()).toHaveLength(0)
  await click(wrapper, '取消'); await click(wrapper, 'SLA规则'); await click(wrapper, '编辑')
  for (const n of ['0', '525601', '1.5', '300']) { await editor(wrapper).get('input[name=responseMinutes]').setValue(n); await editor(wrapper).trigger('submit') }
  expect(writes()).toHaveLength(0)
  await editor(wrapper).get('input[name=responseMinutes]').setValue('30'); await editor(wrapper).trigger('submit'); await flushPromises()
  expect(JSON.parse(writes()[0][1]!.body as string)).toMatchObject({ version: 3, priority: 'HIGH' })
})

it('用户查询分页使用已提交条件，新查询取消旧页且忽略迟到响应', async () => {
  const wrapper = await page(); await click(wrapper, '下一页'); expect(wrapper.get('tbody').text()).toContain('姓名21')
  await wrapper.get('input[name=keyword]').setValue('姓名150')
  await click(wrapper, '下一页')
  expect(new URL(mock.mock.calls.filter(([p]) => p.includes('limit=20')).at(-1)![0], 'http://test').searchParams.get('keyword')).toBe('')
  const slow = deferred<AdminUserPage>(); let signal: AbortSignal | undefined
  mock.mockImplementation(async (path, options) => { if (path.includes('keyword=old')) { signal = options?.signal as AbortSignal; return slow.promise } return response(path) })
  await wrapper.get('input[name=keyword]').setValue('old'); await wrapper.get('form[aria-label="用户查询"]').trigger('submit'); await flushPromises()
  await wrapper.get('input[name=keyword]').setValue('姓名150'); await wrapper.get('form[aria-label="用户查询"]').trigger('submit'); await flushPromises()
  slow.resolve({ items: [{ ...users[1], displayName: '迟到用户' }], hasMore: false, offset: 0, limit: 20 }); await flushPromises()
  expect(signal?.aborted).toBe(true); expect(wrapper.get('tbody').text()).toContain('姓名150'); expect(wrapper.text()).not.toContain('迟到用户')
})

it('成员切组取消旧请求，旧响应不会覆盖新组', async () => {
  const slow = deferred<SupportAgent[]>(); let signal: AbortSignal | undefined
  mock.mockImplementation(async (path, options) => { if (path.includes('/groups/1/agents?')) { signal = options?.signal as AbortSignal; return slow.promise } return response(path) })
  const wrapper = await page(); await click(wrapper, '客服成员'); await wrapper.get('select[name=memberGroup]').setValue('1'); await flushPromises()
  await wrapper.get('select[name=memberGroup]').setValue('2'); await flushPromises()
  slow.resolve(members); await flushPromises(); expect(signal?.aborted).toBe(true); expect(wrapper.findAll('tbody tr')).toHaveLength(0)
})

it.each([401, 403])('HTTP %s清空敏感列表、选择器、编辑草稿，旧请求不能恢复数据', async status => {
  const wrapper = await page(); await click(wrapper, '选择用户编辑角色'); await editor(wrapper).get('select[name=userId]').setValue('150'); await editor(wrapper).get('textarea[name=reason]').setValue('敏感原因')
  mock.mockRejectedValue(new ApiError(status, 'FORBIDDEN', '权限失效'))
  await click(wrapper, '刷新'); expect(wrapper.text()).toContain('权限已失效'); expect(wrapper.find('.admin-editor').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('姓名150'); expect(wrapper.find('select').exists()).toBe(false)
})

it('账号切换清空草稿和旧查找结果，卸载会中止所有请求', async () => {
  const wrapper = await page(); await click(wrapper, '选择用户编辑角色'); await editor(wrapper).get('select[name=userId]').setValue('150'); await editor(wrapper).get('textarea[name=reason]').setValue('上个账号草稿')
  const slow = deferred<AdminUserPage>(); const signals: AbortSignal[] = []
  mock.mockImplementation(async (path, options) => { if (options?.signal) signals.push(options.signal as AbortSignal); if (path === '/api/admin/users?offset=0&limit=100') return slow.promise; return response(path) })
  await click(wrapper, '重新读取名称')
  session.user.value = { ...users[0], id: 999 }; session.token.value = 'other-test-session'; await flushPromises()
  expect(wrapper.find('.admin-editor').exists()).toBe(false); expect(signals[0].aborted).toBe(true)
  wrapper.unmount(); slow.resolve({ items: users, hasMore: false, offset: 0, limit: 100 }); await flushPromises()
  expect(signals.every(s => s.aborted)).toBe(true)
})

it('权限降级无需等待任何请求就清空页面', async () => {
  const wrapper = await page(); session.user.value = { ...users[0], roles: ['USER'] }; await flushPromises()
  expect(wrapper.text()).toContain('仅向管理员'); expect(wrapper.find('table').exists()).toBe(false)
})

it('完整分页严格受offset上限约束，异常空页不会无限循环', async () => {
  mock.mockResolvedValue({ items: [], hasMore: true })
  await expect(allPages('/api/admin/users', new AbortController().signal)).rejects.toThrow('分页响应异常')
  mock.mockResolvedValue(Array.from({ length: 100 }, (_, id) => ({ id })))
  await expect(allPages('/api/admin/support-groups', new AbortController().signal)).rejects.toThrow('上限')
  expect(mock.mock.calls.at(-1)![0]).toContain('offset=100000&limit=100')
  expect(mock.mock.calls.some(([p]) => p.includes('offset=100100'))).toBe(false)
})

it.each(['group', 'category', 'sla', 'member', 'enabled'] as const)('%s写入统一防重，失败保持草稿以便重试', async kind => {
  const wrapper = await page()
  if (kind === 'enabled') { await click(wrapper, '停用账号'); await editor(wrapper).get('textarea[name=reason]').setValue('停用原因') }
  else {
    await click(wrapper, { group: '客服组', category: '工单分类', sla: 'SLA规则', member: '客服成员' }[kind])
    if (kind === 'member') { await wrapper.get('select[name=memberGroup]').setValue('1'); await flushPromises() }
    await click(wrapper, kind === 'member' ? '编辑成员' : '编辑')
  }
  const pending = deferred<unknown>()
  mock.mockImplementation(async (path, options) => options?.method ? pending.promise : response(path))
  await editor(wrapper).trigger('submit'); await editor(wrapper).trigger('submit')
  expect(writes()).toHaveLength(1)
  expect(button(wrapper, '保存中…').attributes('disabled')).toBeDefined()
  pending.reject(new ApiError(503, 'UNAVAILABLE', '服务暂不可用')); await flushPromises()
  expect(wrapper.text()).toContain('服务暂不可用'); expect(wrapper.find('.admin-editor').exists()).toBe(true)
})

it.each(['members', 'lookups', 'save'] as const)('%s返回403也清空所有列表、选项和草稿', async lane => {
  const wrapper = await page(); await click(wrapper, '客服成员'); await wrapper.get('select[name=memberGroup]').setValue('1'); await flushPromises()
  await click(wrapper, '编辑成员')
  mock.mockImplementation(async (path, options) => {
    if ((lane === 'members' && path.includes('/agents?')) || (lane === 'lookups' && path.includes('limit=100')) || (lane === 'save' && options?.method)) throw new ApiError(403, 'FORBIDDEN', '权限撤销')
    return response(path)
  })
  if (lane === 'members') await click(wrapper, '刷新成员')
  else if (lane === 'lookups') await click(wrapper, '重新读取名称')
  else { await editor(wrapper).trigger('submit'); await flushPromises() }
  expect(wrapper.text()).toContain('权限已失效'); expect(wrapper.find('select').exists()).toBe(false); expect(wrapper.find('table').exists()).toBe(false)
})

it('查找与保存的旧会话结果不会恢复选项、关闭新草稿或显示旧成功提示', async () => {
  const wrapper = await page(); await click(wrapper, '选择用户编辑角色'); await editor(wrapper).get('select[name=userId]').setValue('150')
  await editor(wrapper).get('textarea[name=reason]').setValue('旧会话提交')
  const write = deferred<unknown>(), lookup = deferred<AdminUserPage>()
  mock.mockImplementation(async (path, options) => options?.method ? write.promise : response(path))
  await editor(wrapper).trigger('submit')
  const writeSignal = writes()[0][1]!.signal as AbortSignal
  mock.mockImplementation(async (path, options) => options?.method ? {} : path === '/api/admin/users?offset=0&limit=100' ? lookup.promise : response(path))
  session.user.value = { ...users[0], id: 999 }; await flushPromises()
  const lookupSignal = mock.mock.calls.filter(([p]) => p === '/api/admin/users?offset=0&limit=100').at(-1)![1]!.signal as AbortSignal
  mock.mockImplementation(async path => response(path))
  session.token.value = 'newer-session'; await flushPromises()
  await click(wrapper, '选择用户编辑角色'); await editor(wrapper).get('select[name=userId]').setValue('2')
  await editor(wrapper).get('textarea[name=reason]').setValue('新会话草稿')
  write.resolve({}); lookup.resolve({ items: [{ ...users[1], displayName: '旧账号查找结果' }], hasMore: false, offset: 0, limit: 100 }); await flushPromises()
  expect(writeSignal.aborted).toBe(true); expect(lookupSignal.aborted).toBe(true)
  expect(wrapper.text()).not.toContain('旧账号查找结果'); expect(wrapper.text()).not.toContain('保存成功')
  expect(editor(wrapper).get<HTMLTextAreaElement>('textarea[name=reason]').element.value).toBe('新会话草稿')
})

it('成员409刷新可找到已被移入其他组的记录，确认后提交新版本', async () => {
  const wrapper = await page(); await click(wrapper, '客服成员'); await wrapper.get('select[name=memberGroup]').setValue('1'); await flushPromises(); await click(wrapper, '编辑成员')
  await editor(wrapper).get('select[name=groupId]').setValue('150')
  mock.mockImplementation(async (path, options) => { if (options?.method) throw new ApiError(409, 'VERSION_CONFLICT', '已被调组'); return response(path) })
  await editor(wrapper).trigger('submit'); await flushPromises()
  members[0] = { ...members[0], groupId: 2, version: 6 }
  await click(wrapper, '读取最新记录'); expect(wrapper.text()).toContain('当前记录：姓名2；客服组：支持组2')
  expect(editor(wrapper).get<HTMLSelectElement>('select[name=groupId]').element.value).toBe('150')
  await editor(wrapper).get('input[name=confirmed]').setValue(true)
  mock.mockImplementation(async (path, options) => options?.method ? {} : response(path))
  await editor(wrapper).trigger('submit'); await flushPromises()
  expect(JSON.parse(writes()[1][1]!.body as string)).toMatchObject({ userId: 2, groupId: 150, version: 6 })
})

it('名称读取期间禁止重复打开账号编辑', async () => {
  const wrapper = await page(), slow = deferred<AdminUserPage>()
  let signal: AbortSignal | undefined
  mock.mockImplementation(async (path, options) => { if (path === '/api/admin/users?offset=0&limit=100') { signal = options?.signal as AbortSignal; return slow.promise } return response(path) })
  await click(wrapper, '选择用户编辑角色')
  expect(button(wrapper, '选择用户编辑角色').attributes('disabled')).toBeDefined()
  slow.resolve({ items: [{ ...users[1], displayName: '当前名称选项' }], offset: 0, limit: 100, hasMore: false }); await flushPromises()
  expect(editor(wrapper).text()).toContain('当前名称选项')
  await editor(wrapper).get('select[name=userId]').setValue('2'); expect(editor(wrapper).text()).toContain('当前名称选项')
})

it('打开用户行编辑时使用重新读取的角色；当前账号停用立即清空草稿', async () => {
  const wrapper = await page()
  users[1] = { ...users[1], roles: ['USER', 'LEADER'] }
  await click(wrapper, '编辑角色')
  expect(editor(wrapper).get<HTMLInputElement>('input[value=LEADER]').element.checked).toBe(true)
  expect(editor(wrapper).get<HTMLInputElement>('input[value=AGENT]').element.checked).toBe(false)
  session.user.value = { ...users[0], enabled: false }; await flushPromises()
  expect(wrapper.find('.admin-editor').exists()).toBe(false); expect(wrapper.find('table').exists()).toBe(false)
})
