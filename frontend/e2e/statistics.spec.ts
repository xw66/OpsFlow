import { test, expect } from '@playwright/test'
import { login, setup } from './fixtures'

test('统计真实页面：管理员日期查询和空数据语义，普通用户无权限', async ({ page }) => {
  const { accounts } = await setup()
  await login(page, accounts.admin, '/statistics')
  await expect(page.getByRole('heading', { name: '统计看板', exact: true })).toBeVisible()
  await expect(page.getByText('新增工单', { exact: true })).toBeVisible()
  await page.getByLabel('开始日期', { exact: true }).fill('2020-01-01')
  await page.getByLabel('结束日期', { exact: true }).fill('2020-01-02')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByRole('row', { name: /2020-01-01/ })).toBeVisible()
  await expect(page.locator('.metric-grid').getByText('暂无数据', { exact: true })).toHaveCount(4)

  await login(page, accounts.user, '/statistics')
  await expect(page.getByText('统计仅向组长和管理员开放。', { exact: true })).toBeVisible()
  await expect(page.getByText('新增工单', { exact: true })).toHaveCount(0)
})
