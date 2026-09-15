import { request } from '../../api/http'
import type { Priority, Role, SlaPolicy, SupportAgent, SupportCategory, SupportGroup, User } from '../../api/types'

export const roles: Record<Role, string> = { USER: '普通用户', AGENT: '客服', LEADER: '组长', ADMIN: '管理员' }
export const priorities: Record<Priority, string> = { LOW: '低', NORMAL: '普通', HIGH: '高', URGENT: '紧急' }
export const paths = { group: '/api/admin/support-groups', category: '/api/admin/ticket-categories', sla: '/api/admin/sla-policies', member: '/api/admin/support-agents' }
export type Kind = keyof typeof paths | 'roles' | 'enabled'
export type RecordItem = User | SupportGroup | SupportCategory | SlaPolicy | SupportAgent
export interface Lookups { users: User[]; groups: SupportGroup[]; categories: SupportCategory[] }
export interface Draft {
  kind: Kind; id: number | null; version: number; userId: number | ''; groupId: number | ''; leaderId: number | ''; categoryId: number | ''
  name: string; code: string; enabled: boolean; roles: Role[]; reason: string; priority: Priority
  responseMinutes: number; resolveMinutes: number; autoEscalate: boolean
}
export function makeDraft(kind: Kind, item?: RecordItem): Draft {
  const draft: Draft = { kind, id: item?.id ?? null, version: 0, userId: '', groupId: '', leaderId: '', categoryId: '',
    name: '', code: '', enabled: true, roles: [], reason: '', priority: 'NORMAL', responseMinutes: 60, resolveMinutes: 480, autoEscalate: true }
  if (item) Object.assign(draft, item, 'roles' in item ? { roles: [...item.roles], userId: item.id } : {})
  if (kind === 'enabled' && item) draft.enabled = !item.enabled
  return draft
}

// 按后端上限完整读取；超过上限报错，不把截断结果当作完整选择器。
export async function allPages<T>(path: string, signal: AbortSignal): Promise<T[]> {
  const result: T[] = []
  for (let offset = 0; offset <= 100000; offset += 100) {
    signal.throwIfAborted()
    const page = await request<T[] | { items: T[]; hasMore: boolean }>(`${path}${path.includes('?') ? '&' : '?'}offset=${offset}&limit=100`, { signal })
    signal.throwIfAborted()
    const items = Array.isArray(page) ? page : page.items
    result.push(...items)
    if (Array.isArray(page) ? items.length < 100 : !page.hasMore) return result
    if (!items.length) throw new Error('分页响应异常，请刷新后重试')
  }
  throw new Error('记录超过完整查找上限，请联系管理员缩小数据范围')
}

export function validateDraft(d: Draft, lookup: Lookups, selfId?: number) {
  if (d.kind === 'roles' || d.kind === 'enabled') {
    if (!d.userId || !lookup.users.some(u => u.id === d.userId)) return '请选择用户'
    if (d.userId === selfId) return '不能修改自己的角色或账号状态'
    if (!d.reason.trim() || d.reason.trim().length > 500) return '请填写不超过500字的调整原因'
    if (d.kind === 'roles' && (!d.roles.length || d.roles.some(r => !Object.hasOwn(roles, r)))) return '至少选择一个有效角色'
    return ''
  }
  if (['group', 'category'].includes(d.kind) && (!d.name.trim() || d.name.trim().length > 64)) return '名称需要1至64字'
  if (d.kind === 'category' && !/^[A-Z][A-Z0-9_]{1,31}$/.test(d.code.trim())) return '代码需2至32位大写字母、数字或下划线，且以字母开头'
  if (d.kind === 'group' || d.kind === 'member') {
    const id = d.kind === 'group' ? d.leaderId : d.userId, role = d.kind === 'group' ? 'LEADER' : 'AGENT'
    const user = lookup.users.find(u => u.id === id)
    if (!id || !user) return '请选择关联用户'
    if (d.enabled && (!user.enabled || !user.roles.includes(role))) return '启用前需选择具备对应角色的启用用户'
  }
  if (d.kind === 'category' || d.kind === 'member') {
    const group = lookup.groups.find(g => g.id === d.groupId)
    if (!group) return '请选择客服组'
    if (d.enabled && !group.enabled) return '启用前需选择启用的客服组'
  }
  if (d.kind === 'sla') {
    const category = lookup.categories.find(c => c.id === d.categoryId)
    if (!category) return '请选择工单分类'
    if (d.enabled && (!category.enabled || !lookup.groups.find(g => g.id === category.groupId)?.enabled)) return '启用前分类及关联客服组必须启用'
    if (!Object.hasOwn(priorities, d.priority)) return '请选择有效优先级'
    if (![d.responseMinutes, d.resolveMinutes].every(n => Number.isInteger(n) && n >= 1 && n <= 525600)) return '响应和解决分钟应为1至525600的整数'
    if (d.responseMinutes > d.resolveMinutes) return '首次响应时限不得超过解决时限'
  }
  return ''
}

export function payload(d: Draft) {
  const shared = { enabled: d.enabled, version: d.version }
  switch (d.kind) {
    case 'roles': return { roles: [...d.roles], reason: d.reason.trim() }
    case 'enabled': return { enabled: d.enabled, reason: d.reason.trim() }
    case 'group': return { ...shared, name: d.name.trim(), leaderId: d.leaderId }
    case 'member': return { ...shared, userId: d.userId, groupId: d.groupId }
    case 'category': return { ...shared, code: d.code.trim(), name: d.name.trim(), groupId: d.groupId }
    case 'sla': return { ...shared, categoryId: d.categoryId, priority: d.priority, responseMinutes: d.responseMinutes, resolveMinutes: d.resolveMinutes, autoEscalate: d.autoEscalate }
  }
}
