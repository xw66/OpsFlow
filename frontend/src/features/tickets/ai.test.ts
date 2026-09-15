import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AiAssistPanel from './AiAssistPanel.vue'
import ReplyComposer from './ReplyComposer.vue'
import { request, session } from '../../api/http'

vi.mock('../../api/http', async original => ({ ...await original<typeof import('../../api/http')>(), request: vi.fn() }))
const requestMock = vi.mocked(request)
const ticket = { id: 7, version: 3 } as never
const reply = { id: 1, ticketId: 7, ticketVersion: 3, kind: 'REPLY', status: 'SUCCEEDED', resultJson: '{"suggestion":"原始建议"}', acceptedAt: null }

beforeEach(() => { session.token.value = 'token'; requestMock.mockReset().mockResolvedValue([reply]); vi.spyOn(window, 'confirm').mockReturnValue(false) })
afterEach(() => { session.token.value = null; vi.restoreAllMocks() })

it('AI 编辑后的回复进入公开草稿，采纳后刷新工单', async () => {
  const wrapper = mount(AiAssistPanel, { props: { ticket, staff: true } })
  await flushPromises()
  await wrapper.findAll('button').find(button => button.text() === '放入公开回复草稿')!.trigger('click')
  expect(wrapper.emitted('draft')?.[0]).toEqual(['原始建议'])
  const composer = mount(ReplyComposer, { props: { busy: false, staff: true, successCount: 0, initialDraft: '' } })
  await composer.setProps({ initialDraft: String(wrapper.emitted('draft')![0][0]) })
  await composer.get('textarea').setValue('人工改过的建议')
  await wrapper.setProps({ ticket: { id: 7, version: 4 } as never })
  expect(composer.get('textarea').element.value).toBe('人工改过的建议')
  expect(composer.emitted('dirty')?.at(-1)).toEqual([true])
  await composer.get('form').trigger('submit')
  expect(composer.emitted('submit')?.[0]).toEqual(['人工改过的建议', false])
  composer.unmount()
  wrapper.unmount()

  requestMock.mockReset().mockResolvedValueOnce([{ ...reply, kind: 'CLASSIFICATION', resultJson: '{"category":"网络","priority":"HIGH","reason":"匹配"}' }]).mockResolvedValueOnce({}).mockResolvedValueOnce([])
  const classified = mount(AiAssistPanel, { props: { ticket, staff: true } })
  await flushPromises()
  await classified.findAll('button').find(button => button.text() === '采纳分类')!.trigger('click')
  await flushPromises()
  expect(classified.emitted('changed')).toHaveLength(1)
  expect(requestMock.mock.calls.some(([path, options]) => String(path).endsWith('/accept') && options?.method === 'POST')).toBe(true)
  classified.unmount()
})

it('AI 建议不会静默覆盖已有公开草稿', async () => {
  const wrapper = mount(ReplyComposer, { props: { busy: false, staff: true, successCount: 0, initialDraft: '' } })
  await wrapper.get('textarea').setValue('我正在写的回复')
  await wrapper.setProps({ initialDraft: 'AI 建议' })
  expect(wrapper.get('textarea').element.value).toBe('我正在写的回复')
  wrapper.unmount()
})
