import { computed, onUnmounted, reactive, ref, shallowRef, watch, type Ref } from 'vue'
import { ApiError, errorText, request, session } from '../../api/http'
import type { AdminUserPage, SlaPolicy, SupportAgent, SupportCategory, SupportGroup, User } from '../../api/types'
import { allPages, makeDraft, paths, payload, validateDraft, type Draft, type Kind, type Lookups, type RecordItem } from './adminData'

export function useAdmin() {
  const denied = shallowRef(false)
  const allowed = computed(() => !!session.token.value && session.user.value?.enabled === true && session.user.value.roles.includes('ADMIN') && !denied.value)
  const tab = shallowRef<'users' | 'groups' | 'members' | 'categories' | 'sla'>('users')
  const filters = reactive({ keyword: '', enabled: '', role: '' }), applied = shallowRef({ ...filters })
  const offset = shallowRef(0), memberOffset = shallowRef(0), selectedGroupId = shallowRef<number | ''>('')
  const users = shallowRef<AdminUserPage | null>(null), groups = shallowRef<SupportGroup[]>([])
  const categories = shallowRef<SupportCategory[]>([]), policies = shallowRef<SlaPolicy[]>([]), members = shallowRef<SupportAgent[]>([])
  const lookups = shallowRef<Lookups>({ users: [], groups: [], categories: [] })
  const loading = shallowRef(false), memberLoading = shallowRef(false), lookupLoading = shallowRef(false), lookupReady = shallowRef(false)
  const hasMore = shallowRef(false), memberHasMore = shallowRef(false), saving = shallowRef(false), refreshing = shallowRef(false)
  const error = shallowRef(''), lookupError = shallowRef(''), memberError = shallowRef(''), formError = shallowRef(''), notice = shallowRef('')
  const draft = ref<Draft | null>(null), original = shallowRef<RecordItem | undefined>()
  const conflict = shallowRef(false), latest = shallowRef<RecordItem | null>(null), confirmed = shallowRef(false), reviewed = shallowRef(false)
  const lanes = new Map<string, AbortController>()

  function abortAll() { for (const controller of lanes.values()) controller.abort(); lanes.clear() }
  function clear() {
    abortAll()
    users.value = null; groups.value = []; categories.value = []; policies.value = []; members.value = []
    lookups.value = { users: [], groups: [], categories: [] }; lookupReady.value = false
    draft.value = null; original.value = undefined; latest.value = null; conflict.value = false; confirmed.value = false; reviewed.value = false
    loading.value = false; memberLoading.value = false; lookupLoading.value = false; saving.value = false; refreshing.value = false
    error.value = ''; lookupError.value = ''; memberError.value = ''; formError.value = ''; notice.value = ''
    hasMore.value = false; memberHasMore.value = false; selectedGroupId.value = ''; memberOffset.value = 0; offset.value = 0
    Object.assign(filters, { keyword: '', enabled: '', role: '' }); applied.value = { ...filters }
  }
  function revoke() { denied.value = true; clear(); error.value = '登录或管理权限已失效，请重新登录后继续。' }

  // 每个读取通道只接受最后一次响应；会话变化和卸载同时中止所有通道。
  async function run<T>(lane: string, busy: Ref<boolean>, failure: Ref<string>, task: (signal: AbortSignal) => Promise<T>, commit: (value: T) => void, onError?: (e: unknown) => void) {
    lanes.get(lane)?.abort()
    if (!allowed.value) return
    const controller = new AbortController(), token = session.token.value, identity = session.user.value?.id
    lanes.set(lane, controller); busy.value = true; failure.value = ''
    const current = () => !controller.signal.aborted && lanes.get(lane) === controller && allowed.value && session.token.value === token && session.user.value?.id === identity
    try {
      const value = await task(controller.signal)
      if (current()) commit(value)
    } catch (e) {
      if (current()) {
        if (e instanceof ApiError && (e.status === 401 || e.status === 403)) revoke()
        else { failure.value = errorText(e); onError?.(e) }
      }
    } finally {
      controller.abort()
      if (lanes.get(lane) === controller) { lanes.delete(lane); busy.value = false }
    }
  }

  function loadLookups() {
    lookupReady.value = false
    lookups.value = { users: [], groups: [], categories: [] }
    return run('lookups', lookupLoading, lookupError, async signal => {
      const [users, groups, categories] = await Promise.all([
        allPages<User>('/api/admin/users', signal), allPages<SupportGroup>(paths.group, signal), allPages<SupportCategory>(paths.category, signal),
      ])
      return { users, groups, categories }
    }, value => { lookups.value = value; lookupReady.value = true })
  }
  function loadPage() {
    users.value = null; groups.value = []; categories.value = []; policies.value = []; hasMore.value = false
    const currentTab = tab.value
    if (currentTab === 'members') return
    const query = new URLSearchParams({ offset: String(offset.value), limit: '20' })
    if (currentTab === 'users') {
      query.set('keyword', applied.value.keyword.trim())
      if (applied.value.enabled) query.set('enabled', applied.value.enabled)
      if (applied.value.role) query.set('role', applied.value.role)
    }
    const path = currentTab === 'users' ? '/api/admin/users' : currentTab === 'groups' ? paths.group : currentTab === 'categories' ? paths.category : paths.sla
    return run('page', loading, error, signal => request<AdminUserPage | SupportGroup[] | SupportCategory[] | SlaPolicy[]>(`${path}?${query}`, { signal }), value => {
      if (currentTab === 'users') { users.value = value as AdminUserPage; hasMore.value = users.value.hasMore }
      else {
        if (currentTab === 'groups') groups.value = value as SupportGroup[]
        if (currentTab === 'categories') categories.value = value as SupportCategory[]
        if (currentTab === 'sla') policies.value = value as SlaPolicy[]
        hasMore.value = (value as unknown[]).length === 20
      }
    })
  }
  function loadMembers() {
    members.value = []; memberHasMore.value = false
    lanes.get('members')?.abort(); memberLoading.value = false
    if (tab.value !== 'members' || !selectedGroupId.value) return
    return run('members', memberLoading, memberError,
      signal => request<SupportAgent[]>(`/api/support/groups/${selectedGroupId.value}/agents?offset=${memberOffset.value}&limit=20`, { signal }),
      value => { members.value = value; memberHasMore.value = value.length === 20 })
  }
  function refresh() { void loadPage(); void loadMembers(); void loadLookups() }
  function search() { offset.value = 0; applied.value = { ...filters } }
  function close() {
    if (saving.value || refreshing.value) return
    draft.value = null; original.value = undefined; formError.value = ''; conflict.value = false; latest.value = null; confirmed.value = false; reviewed.value = false
  }
  function open(kind: Kind, item?: RecordItem) {
    if (!allowed.value || saving.value || refreshing.value) return
    close(); notice.value = ''
    original.value = item ? { ...item } : undefined
    draft.value = makeDraft(kind, item)
    if (kind === 'member' && !item) draft.value.groupId = selectedGroupId.value
    const opened = draft.value
    void loadLookups().then(() => {
      if (draft.value === opened && lookupReady.value && (kind === 'roles' || kind === 'enabled') && opened.userId) chooseUser(opened.userId)
    })
  }
  function chooseUser(id: number | '') {
    if (!draft.value || saving.value) return
    const user = lookups.value.users.find(u => u.id === id)
    draft.value.userId = id; draft.value.id = user?.id ?? null; draft.value.roles = [...(user?.roles ?? [])]; draft.value.reason = ''
    if (draft.value.kind === 'enabled' && user) draft.value.enabled = !user.enabled
    original.value = user; conflict.value = false; latest.value = null; confirmed.value = false; reviewed.value = false
  }

  async function save() {
    if (!allowed.value || !draft.value || saving.value || refreshing.value || !lookupReady.value || (conflict.value && (!reviewed.value || !confirmed.value))) return
    const input = { ...draft.value, roles: [...draft.value.roles] }
    const invalid = validateDraft(input, lookups.value, session.user.value?.id)
    if (invalid) { formError.value = invalid; return }
    const userAction = input.kind === 'roles' || input.kind === 'enabled'
    const path = userAction ? `/api/admin/users/${input.userId}/${input.kind}` : `${paths[input.kind as keyof typeof paths]}${input.id ? `/${input.id}` : ''}`
    if (latest.value && 'version' in latest.value) input.version = latest.value.version
    return run('save', saving, formError, signal => request(path, { method: userAction || input.id ? 'PUT' : 'POST', body: JSON.stringify(payload(input)), signal }), () => {
      draft.value = null; original.value = undefined; conflict.value = false; latest.value = null; confirmed.value = false
      notice.value = '保存成功'; refresh()
    }, e => {
      if (e instanceof ApiError && e.status === 409) { conflict.value = true; latest.value = null; confirmed.value = false; reviewed.value = false; formError.value = `${errorText(e)}。输入已保留，请读取最新记录并核对后再次确认。` }
    })
  }

  async function refreshVersion() {
    if (!draft.value || refreshing.value || saving.value) return
    const input = { ...draft.value }
    latest.value = null; confirmed.value = false; reviewed.value = false
    return run('version', refreshing, formError, async signal => {
      const [users, groups, categories] = await Promise.all([
        allPages<User>('/api/admin/users', signal), allPages<SupportGroup>(paths.group, signal), allPages<SupportCategory>(paths.category, signal),
      ])
      let record: RecordItem | undefined
      if (input.kind === 'roles' || input.kind === 'enabled') record = users.find(u => u.id === input.userId)
      else if (input.kind === 'group') record = groups.find(g => g.id === input.id)
      else if (input.kind === 'category') record = categories.find(c => c.id === input.id)
      else if (input.kind === 'sla') record = (await allPages<SlaPolicy>(paths.sla, signal)).find(p => p.id === input.id)
      else if (input.kind === 'member') {
        // 成员可能已被并发调组，不能只在旧组内查找。
        for (const group of groups) {
          record = (await allPages<SupportAgent>(`/api/support/groups/${group.id}/agents`, signal)).find(a => input.id ? a.id === input.id : a.userId === input.userId)
          if (record) break
        }
      }
      if (input.id && !record) throw new Error('原记录已不存在或不可访问，输入已保留，请取消后重新选择')
      if (!input.id && input.kind === 'member' && record) throw new Error('该客服已有成员记录，请取消新增并到其所属组编辑')
      return { record: record ?? null, lookup: { users, groups, categories } }
    }, ({ record, lookup }) => {
      latest.value = record; reviewed.value = true; lookups.value = lookup; lookupReady.value = true
      formError.value = '已读取最新记录。请核对下方当前值与保留的输入，确认后再保存。'
    })
  }

  watch([session.token, () => session.user.value?.id, () => session.user.value?.roles.join(','), () => session.user.value?.enabled], () => {
    clear(); denied.value = false
    if (allowed.value) refresh()
  }, { immediate: true, flush: 'sync' })
  watch(tab, () => { lanes.get('page')?.abort(); loading.value = false; error.value = ''; offset.value = 0; close(); void loadPage(); void loadMembers() })
  watch([offset, applied], () => { void loadPage() })
  watch(selectedGroupId, () => { memberOffset.value = 0 }, { flush: 'sync' })
  watch([selectedGroupId, memberOffset], () => { void loadMembers() })
  onUnmounted(clear)
  return { allowed, tab, filters, offset, memberOffset, selectedGroupId, users, groups, categories, policies, members, lookups,
    loading, memberLoading, lookupLoading, lookupReady, hasMore, memberHasMore, saving, refreshing,
    error, lookupError, memberError, formError, notice, draft, original, conflict, latest, confirmed, reviewed,
    refresh, search, close, open, chooseUser, save, refreshVersion, loadLookups, loadMembers }
}
