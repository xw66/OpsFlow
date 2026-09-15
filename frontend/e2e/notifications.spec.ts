import { test, expect } from '@playwright/test'
import { api, login, setup } from './fixtures'
import type { Detail, Notification } from '../src/api/types'

test('通知真实流程：分配事件入站、未读角标、已读和工单跳转', async ({ page }) => {
  const { client, accounts, category } = await setup()
  try {
    await api(client, '/api/support/agents/me/online', 'PUT', { online: true, version: 0 }, accounts.agent.token)
    const detail = await api<Detail>(client, '/api/tickets', 'POST', { title: '通知验收工单', description: '验证分配通知', categoryId: category.id, priority: 'HIGH', tags: [] }, accounts.user.token)
    await api(client, `/api/tickets/${detail.ticket.id}/assign`, 'POST', { assigneeId: accounts.agent.id, version: 0, reason: '通知验收分配' }, accounts.admin.token)
    await expect.poll(async () => (await api<Notification[]>(client, '/api/notifications?offset=0&limit=20', 'GET', undefined, accounts.agent.token)).filter(item => item.ticketId === detail.ticket.id).length, { timeout: 15000 }).toBe(1)

    await login(page, accounts.agent, '/notifications')
    await expect(page.getByRole('heading', { name: '通知', exact: true })).toBeVisible()
    await expect(page.locator('.unread-badge')).toHaveText('1')
    await expect(page.locator('li.unread')).toContainText(`工单 #${detail.ticket.id}：已分配客服`)
    await page.locator('li.unread').getByRole('button', { name: '标为已读', exact: true }).click()
    await expect(page.locator('.unread-badge')).toHaveCount(0)
    await page.locator('li').getByRole('link', { name: '查看工单', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/tickets/${detail.ticket.id}$`))
  } finally { await client.dispose() }
})
