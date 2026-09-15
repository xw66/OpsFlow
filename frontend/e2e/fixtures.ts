import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { request, expect, type APIRequestContext, type Page } from '@playwright/test'

const apiBaseURL = process.env.OPSFLOW_E2E_API_URL || 'http://127.0.0.1:8182'
const credentialsFile = process.env.OPSFLOW_E2E_CREDENTIALS_FILE || new URL('../../target/acceptance/credentials.json', import.meta.url)
export const credentials = JSON.parse(readFileSync(credentialsFile, 'utf8').replace(/^\uFEFF/, '')) as { password: string }
export type Account = { id: number; username: string; displayName: string; token: string }
export async function api<T>(client: APIRequestContext, path: string, method = 'GET', data?: unknown, token?: string): Promise<T> {
  const response = await client.fetch(path, { method, data, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Idempotency-Key': randomUUID() } })
  if (!response.ok()) throw new Error(`${method} ${path}: HTTP ${response.status()}`)
  return (await response.json()).data as T
}
export async function setup() {
  const client = await request.newContext({ baseURL: apiBaseURL })
  const admin = await api<{ accessToken: string }>(client, '/api/auth/login', 'POST', { username: 'e2e_admin', password: credentials.password })
  const adminUser = await api<{ id: number }>(client, '/api/auth/me', 'GET', undefined, admin.accessToken)
  const accounts: Record<string, Account> = { admin: { id: adminUser.id, username: 'e2e_admin', displayName: '管理员', token: admin.accessToken } }
  const suffix = randomUUID().replaceAll('-', '').slice(0, 12)
  for (const [key, role, displayName] of [['user', 'USER', '用户小林'], ['agent', 'AGENT', '客服小周'], ['leader', 'LEADER', '组长陈明']]) {
    const username = `e2e_${key}_${suffix}`
    const user = await api<{ id: number }>(client, '/api/auth/register', 'POST', { username, displayName, password: credentials.password })
    if (role !== 'USER') await api(client, `/api/admin/users/${user.id}/roles`, 'PUT', { roles: ['USER', role], reason: '初始化隔离浏览器测试角色' }, admin.accessToken)
    const login = await api<{ accessToken: string }>(client, '/api/auth/login', 'POST', { username, password: credentials.password })
    accounts[key] = { id: user.id, username, displayName, token: login.accessToken }
  }
  const group = await api<{ id: number; name: string }>(client, '/api/admin/support-groups', 'POST', { name: `浏览器支持组${suffix}`, leaderId: accounts.leader.id, enabled: true, version: 0 }, admin.accessToken)
  const category = await api<{ id: number; name: string }>(client, '/api/admin/ticket-categories', 'POST', { code: `E${suffix.toUpperCase()}`, name: `网络支持${suffix}`, groupId: group.id, enabled: true, version: 0 }, admin.accessToken)
  await api(client, '/api/admin/sla-policies', 'POST', { categoryId: category.id, priority: 'HIGH', responseMinutes: 30, resolveMinutes: 240, autoEscalate: true, enabled: true, version: 0 }, admin.accessToken)
  await api(client, '/api/admin/support-agents', 'POST', { userId: accounts.agent.id, groupId: group.id, enabled: true, version: 0 }, admin.accessToken)
  return { client, accounts, category, group }
}
export async function login(page: Page, account: Account, path = '/tickets') {
  await page.goto(path)
  await page.getByLabel('账号', { exact: true }).fill(account.username)
  await page.getByLabel('密码', { exact: true }).fill(credentials.password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.auth-screen')).toHaveCount(0)
}
export async function action(page: Page, name: string, reason: string) {
  const section = page.getByRole('region', { name: '工单处理操作', exact: true })
  await section.getByRole('button', { name, exact: true }).click()
  await section.getByLabel('操作说明').fill(reason)
  await section.getByRole('button', { name: `确认${name}`, exact: true }).click()
  await expect(section.locator('form')).toHaveCount(0)
  await expect(page.getByText('正在读取工单…', { exact: true })).toHaveCount(0)
}
