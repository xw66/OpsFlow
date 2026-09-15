import { test, expect, type Page, type Locator } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { login, setup } from './fixtures'
import type { SlaPolicy, SupportCategory, SupportGroup } from '../src/api/types'

async function findRow(page: Page, panel: Locator, name: string) {
  for (let pageNo = 0; pageNo <= 5000; pageNo++) {
    await expect(panel.getByRole('table')).toBeVisible()
    const row = panel.getByRole('row').filter({ hasText: name })
    if (await row.count()) return row.first()
    const next = page.getByRole('button', { name: '下一页', exact: true })
    if (await next.isDisabled()) break
    await Promise.all([page.waitForResponse(r => r.request().method() === 'GET' && r.url().includes('limit=20')), next.click()])
  }
  throw new Error('完整分页后未找到目标配置')
}
const form = (page: Page) => page.getByRole('form', { name: '管理员编辑表单', exact: true })
async function save<T>(page: Page, path: string, method: string): Promise<T> {
  const response = page.waitForResponse(r => new URL(r.url()).pathname === path && r.request().method() === method)
  await form(page).getByRole('button', { name: '保存', exact: true }).click()
  const result = await response
  expect(result.ok()).toBe(true)
  await expect(form(page)).toHaveCount(0)
  return (await result.json()).data as T
}

test('管理员原生闭环：角色和账号、组/成员调组、分类/SLA新增编辑与名称关联', async ({ page }) => {
  test.setTimeout(180000)
  const fixture = await setup()
  const { accounts, client } = fixture
  const suffix = randomUUID().replaceAll('-', '').slice(0, 10)
  try {
    await login(page, accounts.admin, '/admin')
    await expect(page.getByRole('heading', { name: '管理配置', exact: true })).toBeVisible()
    const chooseUser = page.getByRole('button', { name: '选择用户编辑角色', exact: true })
    await expect(chooseUser).toBeEnabled()
    await chooseUser.click()
    const userSelect = form(page).locator('select[name="userId"]')
    await expect(userSelect).toBeEnabled({ timeout: 90000 })
    await userSelect.selectOption(String(accounts.user.id))
    await expect(form(page).locator('input[value="USER"]')).toBeChecked()
    await form(page).locator('input[value="AGENT"]').check()
    await form(page).locator('textarea[name="reason"]').fill('浏览器验收：授权客服职责')
    const changedUser = await save<{ roles: string[] }>(page, `/api/admin/users/${accounts.user.id}/roles`, 'PUT')
    expect(new Set(changedUser.roles)).toEqual(new Set(['USER', 'AGENT']))

    const users = page.getByRole('region', { name: '用户账号', exact: true })
    await users.getByLabel('查找账号或姓名').fill(accounts.user.username)
    await users.getByRole('button', { name: '查询', exact: true }).click()
    await expect(users.getByRole('row').filter({ hasText: accounts.user.username })).toHaveCount(1)
    await users.getByRole('button', { name: '停用账号', exact: true }).click()
    await form(page).locator('textarea[name="reason"]').fill('浏览器验收：暂时停用')
    await save(page, `/api/admin/users/${accounts.user.id}/enabled`, 'PUT')
    await users.getByRole('button', { name: '启用账号', exact: true }).click()
    await form(page).locator('textarea[name="reason"]').fill('浏览器验收：恢复启用')
    await save(page, `/api/admin/users/${accounts.user.id}/enabled`, 'PUT')

    await page.getByRole('button', { name: '客服组', exact: true }).click()
    const groupPanel = page.getByRole('region', { name: '客服组', exact: true })
    await groupPanel.getByRole('button', { name: '新增', exact: true }).click()
    await form(page).locator('input[name="name"]').fill(`原生新增组${suffix}`)
    await form(page).locator('select[name="leaderId"]').selectOption(String(accounts.leader.id))
    const group = await save<SupportGroup>(page, '/api/admin/support-groups', 'POST')
    const groupRow = await findRow(page, groupPanel, group.name)
    await expect(groupRow).toContainText(accounts.leader.displayName)
    await groupRow.getByRole('button', { name: '编辑', exact: true }).click()
    await expect(form(page).locator('input[name="name"]')).toHaveValue(group.name)
    const editedGroupName = `原生编辑组${suffix}`
    await form(page).locator('input[name="name"]').fill(editedGroupName)
    const updated = await save<SupportGroup>(page, `/api/admin/support-groups/${group.id}`, 'PUT')
    expect(updated.version).toBe(group.version + 1)

    await page.getByRole('button', { name: '客服成员', exact: true }).click()
    const memberPanel = page.getByRole('region', { name: '客服成员', exact: true })
    await memberPanel.locator('select[name="memberGroup"]').selectOption(String(group.id))
    await memberPanel.getByRole('button', { name: '新增成员', exact: true }).click()
    await form(page).locator('select[name="userId"]').selectOption(String(accounts.user.id))
    const member = await save<{ id: number }>(page, '/api/admin/support-agents', 'POST')
    await expect(memberPanel.getByRole('table')).toContainText(accounts.user.displayName)
    await expect(memberPanel.getByRole('table')).toContainText(editedGroupName)
    await memberPanel.getByRole('button', { name: '编辑成员', exact: true }).click()
    await form(page).locator('select[name="groupId"]').selectOption(String(fixture.group.id))
    await form(page).locator('input[name="enabled"]').uncheck()
    await save(page, `/api/admin/support-agents/${member.id}`, 'PUT')
    await memberPanel.locator('select[name="memberGroup"]').selectOption(String(fixture.group.id))
    const memberRow = memberPanel.getByRole('row').filter({ hasText: accounts.user.displayName })
    await expect(memberRow).toContainText('停用')

    await page.getByRole('button', { name: '工单分类', exact: true }).click()
    const categoryPanel = page.getByRole('region', { name: '工单分类', exact: true })
    await categoryPanel.getByRole('button', { name: '新增', exact: true }).click()
    await form(page).locator('input[name="name"]').fill(`原生分类${suffix}`)
    await form(page).locator('input[name="code"]').fill(`N${suffix.toUpperCase()}`)
    await form(page).locator('select[name="groupId"]').selectOption(String(group.id))
    const category = await save<SupportCategory>(page, '/api/admin/ticket-categories', 'POST')
    const categoryRow = await findRow(page, categoryPanel, category.code)
    await expect(categoryRow).toContainText(editedGroupName)
    await categoryRow.getByRole('button', { name: '编辑', exact: true }).click()
    const categoryName = `原生编辑分类${suffix}`
    await form(page).locator('input[name="name"]').fill(categoryName)
    await save(page, `/api/admin/ticket-categories/${category.id}`, 'PUT')

    await page.getByRole('button', { name: 'SLA规则', exact: true }).click()
    const slaPanel = page.getByRole('region', { name: 'SLA规则', exact: true })
    await slaPanel.getByRole('button', { name: '新增', exact: true }).click()
    await form(page).locator('select[name="categoryId"]').selectOption(String(category.id))
    await form(page).locator('select[name="priority"]').selectOption('HIGH')
    await form(page).locator('input[name="responseMinutes"]').fill('30')
    await form(page).locator('input[name="resolveMinutes"]').fill('240')
    const policy = await save<SlaPolicy>(page, '/api/admin/sla-policies', 'POST')
    const policyRow = await findRow(page, slaPanel, categoryName)
    await policyRow.getByRole('button', { name: '编辑', exact: true }).click()
    await expect(form(page).locator('input[name="responseMinutes"]')).toHaveValue('30')
    await form(page).locator('input[name="responseMinutes"]').fill('35')
    const policyUpdated = await save<SlaPolicy>(page, `/api/admin/sla-policies/${policy.id}`, 'PUT')
    expect(policyUpdated.responseMinutes).toBe(35)
    expect(policyUpdated.version).toBe(policy.version + 1)
    await slaPanel.getByRole('button', { name: '新增', exact: true }).click()
    let sent = false
    const listener = (request: import('@playwright/test').Request) => { if (request.method() === 'POST') sent = true }
    page.on('request', listener)
    await form(page).getByRole('button', { name: '取消', exact: true }).click()
    await expect(form(page)).toHaveCount(0)
    page.off('request', listener)
    expect(sent).toBe(false)
  } finally { await client.dispose() }
})
