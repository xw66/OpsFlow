import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReplyComposer from './ReplyComposer.vue'
import TicketCreatePage from './TicketCreatePage.vue'
import TicketAttachments from './TicketAttachments.vue'
import TicketForm from './TicketForm.vue'
import AgentAvailability from './AgentAvailability.vue'
import { ApiError, post, request, session } from '../../api/http'
import { slaInfo } from './format'

vi.mock('./useLeaveGuard', () => ({ useLeaveGuard: vi.fn() }))
vi.mock('../../api/http', async importOriginal => {
  const original = await importOriginal<typeof import('../../api/http')>()
  return { ...original, post: vi.fn(), request: vi.fn(), rawRequest: vi.fn() }
})
const requestMock = vi.mocked(request), postMock = vi.mocked(post)
const wrappers: ReturnType<typeof mount>[] = []
function track(wrapper: ReturnType<typeof mount>) { wrappers.push(wrapper); return wrapper }
beforeEach(() => {
  requestMock.mockReset(); postMock.mockReset()
  session.token.value = 'test-token'
  requestMock.mockImplementation(async path => path.includes('categories?') ? [{ id: 1, name: '网络' }] : path.includes('/priorities') ? [{ priority: 'HIGH', responseMinutes: 30, resolveMinutes: 240 }] : [])
})
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; session.token.value = null })

describe('工单用户闭环', () => {
  it('在线状态来自服务端，切换携带读取到的版本，失败不能伪造在线', async () => {
    requestMock.mockResolvedValueOnce({ online: false, enabled: true, version: 7 })
      .mockRejectedValueOnce(new ApiError(409, 'VERSION_CONFLICT', '客服状态已变更'))
    const wrapper = track(mount(AgentAvailability))
    await flushPromises()
    expect(wrapper.text()).toContain('当前离线')
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(requestMock.mock.calls[1]).toEqual(['/api/support/agents/me/online', { method: 'PUT', body: JSON.stringify({ online: true, version: 7 }) }])
    expect(wrapper.text()).toContain('客服状态已变更')
    expect(wrapper.text()).not.toContain('在线接单')
  })

  it('失去客服权限后保留内部草稿，不能自动变成公开回复', async () => {
    const wrapper = track(mount(ReplyComposer, { props: { busy: false, successCount: 0, staff: true } }))
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('textarea').setValue('待确认草稿')
    await wrapper.setProps({ staff: false, busy: true })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')).toBeUndefined()
    await wrapper.setProps({ busy: false })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.get('textarea').element.value).toBe('待确认草稿')
    expect(wrapper.text()).toContain('不会转为公开回复')
    await wrapper.get('input[type="checkbox"]').setValue(false)
    expect(wrapper.get('textarea').element.value).toBe('')
    await wrapper.get('textarea').setValue('可公开内容')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')?.[0]).toEqual(['可公开内容', false])
    await wrapper.setProps({ successCount: 1 })
    await wrapper.get('input[type="checkbox"]').setValue(true)
    expect(wrapper.get('textarea').element.value).toBe('待确认草稿')
  })
  it('回复失败保留纯文本草稿，仅成功确认后清空', async () => {
    const wrapper = track(mount(ReplyComposer, { props: { busy: false, successCount: 0, staff: false } }))
    await wrapper.get('textarea').setValue('<img src=x onerror=alert(1)>')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')?.[0]).toEqual(['<img src=x onerror=alert(1)>', false])
    await wrapper.setProps({ busy: true }); await wrapper.setProps({ busy: false })
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toContain('<img')
    expect(wrapper.find('img').exists()).toBe(false)
    await wrapper.setProps({ successCount: 1 })
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('')
  })

  it('客服可以把草稿明确标记为内部备注', async () => {
    const wrapper = track(mount(ReplyComposer, { props: { busy: false, successCount: 0, staff: true } }))
    await wrapper.get('.internal-toggle input').setValue(true)
    await wrapper.get('textarea').setValue('仅供团队排查')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')?.[0]).toEqual(['仅供团队排查', true])
    expect(wrapper.text()).toContain('仅内部可见')
  })

  it('只允许选择有效SLA优先级，提交时规范化标签', async () => {
    const wrapper = track(mount(TicketForm, { props: { disabled: false } }))
    await flushPromises()
    await wrapper.get('input[maxlength="200"]').setValue(' VPN连接失败 ')
    await wrapper.get('textarea').setValue('所有内部服务无法访问')
    await wrapper.findAll('select')[0].setValue('1'); await flushPromises()
    expect(wrapper.findAll('select')[1].text()).toContain('高')
    expect(wrapper.findAll('select')[1].text()).not.toContain('普通')
    await wrapper.findAll('select')[1].setValue('HIGH')
    await wrapper.get('input[maxlength="329"]').setValue('网络, 网络，VPN')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')?.[0]?.[0]).toEqual({ title: 'VPN连接失败', description: '所有内部服务无法访问', categoryId: 1, priority: 'HIGH', tags: ['网络', 'VPN'] })
  })

  it('创建超时冻结内容，同一幂等键重试，成功后不再建单', async () => {
    postMock.mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '请求超时'))
      .mockResolvedValueOnce({ ticket: { id: 72, version: 0 }, tags: [] })
    const wrapper = track(mount(TicketCreatePage, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' }, TicketAttachments: true } } }))
    await flushPromises()
    await wrapper.get('input[maxlength="200"]').setValue('VPN故障')
    await wrapper.get('textarea').setValue('无法访问内部文档')
    await wrapper.findAll('select')[0].setValue('1'); await flushPromises()
    await wrapper.findAll('select')[1].setValue('HIGH')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('创建结果暂不确定')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    await wrapper.findAll('button').find(b => b.text() === '重试原请求')!.trigger('click'); await flushPromises()
    expect(postMock.mock.calls[0]).toEqual(postMock.mock.calls[1])
    expect(wrapper.text()).toContain('工单 #72 已保存')
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('附件响应未知时重试原版本，冲突后要求核对，不重复建单', async () => {
    let calls = 0
    requestMock.mockImplementation(async (path, options) => {
      if (path.startsWith('/api/workspace/')) return { detail: { ticket: { version: 4 } } }
      if (options?.method === 'POST') {
        calls++
        if (calls === 1) throw new ApiError(0, 'NETWORK_ERROR', '请求超时')
        throw new ApiError(409, 'VERSION_CONFLICT', '版本冲突')
      }
      return []
    })
    const wrapper = track(mount(TicketAttachments, { props: { ticketId: 72, canUpload: true, initialFiles: [new File(['%PDF-1.7'], '报告.pdf', { type: 'application/pdf' })] } }))
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '上传 / 重试')!.trigger('click'); await flushPromises()
    const uploads = requestMock.mock.calls.filter(([, options]) => options?.method === 'POST')
    expect(uploads.map(([path]) => path)).toEqual(['/api/tickets/72/attachments?version=4', '/api/tickets/72/attachments?version=4'])
    expect(wrapper.text()).toContain('核对附件后重新准备上传')
    expect(postMock).not.toHaveBeenCalled()
  })

  it('已响应工单显示解决时限，终态不继续倒计时', () => {
    const value = { status: 'PROCESSING' as const, firstResponseAt: '2026-09-10T01:00:00Z', responseDeadline: '2026-09-10T01:30:00Z', resolveDeadline: '2026-09-10T04:00:00Z' }
    expect(slaInfo(value, Date.parse('2026-09-10T03:58:00Z'))).toMatchObject({ label: '解决剩余 2 分钟', tone: 'warning' })
    expect(slaInfo({ ...value, status: 'CLOSED' }, Date.parse('2026-09-11T03:58:00Z'))).toMatchObject({ label: '已关闭', deadline: null })
  })
})
