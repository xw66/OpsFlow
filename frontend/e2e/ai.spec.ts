import { test, expect } from '@playwright/test'
import { action, api, login, setup } from './fixtures'
import type { Detail } from '../src/api/types'
type Analysis = { kind: string; status: string }

test('固定AI结果的浏览器交互：编辑草稿、离开保护、版本刷新及真实评论发送', async ({ page }) => {
  const { client, accounts, category } = await setup()
  try {
    await api(client, '/api/support/agents/me/online', 'PUT', { online: true, version: 0 }, accounts.agent.token)
    const created = await api<Detail>(client, '/api/tickets', 'POST', { title: 'AI回复草稿验收', description: '检查人工确认前不发送', categoryId: category.id, priority: 'HIGH', tags: [] }, accounts.user.token)
    const id = created.ticket.id
    await api(client, `/api/tickets/${id}/assign`, 'POST', { assigneeId: accounts.agent.id, version: 0, reason: '回复验收分配' }, accounts.admin.token)
    let generated = false
    // 只固定模型结果；工单流转和评论仍写入隔离环境的真实后端。
    await page.route(`**/api/tickets/${id}/ai-analyses*`, async route => {
      if (route.request().method() === 'POST') generated = true
      const item = { id: 999999, ticketId: id, ticketVersion: 1, kind: 'REPLY', status: 'SUCCEEDED', resultJson: JSON.stringify({ suggestion: '请检查设备连接。' }) }
      await route.fulfill({ json: { code: 'OK', data: route.request().method() === 'POST' ? item : generated ? [item] : [] } })
    })
    await login(page, accounts.agent, `/tickets/${id}`)
    await page.locator('.ai-panel summary').click()
    await page.getByRole('button', { name: '请求回复建议', exact: true }).click()
    await page.getByRole('button', { name: '放入公开回复草稿', exact: true }).click()
    await expect(page.locator('.composer textarea')).toHaveValue('请检查设备连接。')
    await page.locator('.composer textarea').fill('人工修改：请重启设备后反馈结果。')
    expect((await api<unknown[]>(client, `/api/tickets/${id}/comments`, 'GET', undefined, accounts.admin.token)).length).toBe(0)
    page.once('dialog', dialog => dialog.dismiss())
    await page.getByRole('link', { name: '我的工单', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/tickets/${id}$`))
    await action(page, '开始处理', '核查设备')
    await expect(page.locator('.composer textarea')).toHaveValue('人工修改：请重启设备后反馈结果。')
    await page.locator('.composer').getByRole('button', { name: '发送回复', exact: true }).click()
    await expect(page.locator('.message')).toContainText('人工修改：请重启设备后反馈结果。')
    const comments = await api<{ content: string }[]>(client, `/api/tickets/${id}/comments`, 'GET', undefined, accounts.admin.token)
    expect(comments.filter(item => item.content === '人工修改：请重启设备后反馈结果。')).toHaveLength(1)
  } finally { await client.dispose() }
})

test('AI真实降级：无模型时请求失败可见且不阻塞工单', async ({ page }) => {
  const { client, accounts, category } = await setup()
  try {
    await api(client, '/api/support/agents/me/online', 'PUT', { online: true, version: 0 }, accounts.agent.token)
    const detail = await api<Detail>(client, '/api/tickets', 'POST', { title: 'AI降级验收工单', description: '验证未配置外部模型时的失败提示', categoryId: category.id, priority: 'HIGH', tags: [] }, accounts.user.token)
    await api(client, `/api/tickets/${detail.ticket.id}/assign`, 'POST', { assigneeId: accounts.agent.id, version: 0, reason: 'AI验收分配' }, accounts.admin.token)

    await login(page, accounts.agent, `/tickets/${detail.ticket.id}`)
    await expect(page.getByText('AI辅助', { exact: true })).toBeVisible()
    await page.locator('.ai-panel summary').click()
    await expect(page.getByRole('button', { name: '请求摘要', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '请求回复建议', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '请求分类建议', exact: true }).click()
    await expect.poll(async () => {
      const analyses = await api<Analysis[]>(client, `/api/tickets/${detail.ticket.id}/ai-analyses?offset=0&limit=20`, 'GET', undefined, accounts.agent.token)
      return analyses.find(item => item.kind === 'CLASSIFICATION')?.status || ''
    }, { timeout: 60000 }).toMatch(/FAILED|DISABLED/)
    await page.getByRole('button', { name: '刷新建议', exact: true }).click()
    await expect(page.getByText('建议尚未完成或已失败。', { exact: true }).first()).toBeVisible()
  } finally { await client.dispose() }
})
