import { computed, onScopeDispose, shallowRef, watch, type Ref } from 'vue'
import { ApiError, errorText, post, request, session } from '../../api/http'
import type { Comment, WorkspaceDetail } from '../../api/types'

export function useTicketDetail(id: Ref<number>) {
  const data = shallowRef<WorkspaceDetail | null>(null), comments = shallowRef<Comment[]>([])
  const error = shallowRef(''), notice = shallowRef(''), busy = shallowRef(false), loading = shallowRef(false)
  const ready = shallowRef(false), hasMore = shallowRef(false), successCount = shallowRef(0), reload = shallowRef(0)
  const ticket = computed(() => data.value?.detail.ticket)
  let scope = new AbortController()
  let commentOffset = 0
  onScopeDispose(() => scope.abort())
  function clear() { data.value = null; comments.value = []; commentOffset = 0; hasMore.value = false; ready.value = false }
  function fail(e: unknown) {
    error.value = errorText(e)
    if (e instanceof ApiError && [401, 403, 404].includes(e.status)) clear()
  }
  watch([id, session.token, reload], async ([ticketId, token], previous) => {
    scope.abort(); scope = new AbortController()
    const current = scope
    busy.value = false; ready.value = false; loading.value = false
    if (!token || ticketId !== previous?.[0] || token !== previous?.[1]) clear()
    if (!token) return
    loading.value = true; error.value = ''
    // 重新确认权限之前隐藏旧的内部记录；两个接口都成功后才允许继续写入。
    comments.value = comments.value.filter(comment => !comment.internal)
    try {
      const [detail, messages] = await Promise.all([
        request<WorkspaceDetail>(`/api/workspace/tickets/${ticketId}`, { signal: current.signal }),
        request<Comment[]>(`/api/tickets/${ticketId}/comments?limit=100`, { signal: current.signal }),
      ])
      if (current.signal.aborted) return
      data.value = detail
      comments.value = messages.filter(comment => detail.staff || !comment.internal)
      commentOffset = messages.length; hasMore.value = messages.length === 100; ready.value = true
    } catch (e) { if (!current.signal.aborted) fail(e) }
    finally { if (!current.signal.aborted) loading.value = false }
  }, { immediate: true, flush: 'sync' })

  async function more() {
    if (busy.value || !ready.value) return
    const current = scope, ticketId = id.value
    busy.value = true; error.value = ''
    try {
      const batch = await request<Comment[]>(`/api/tickets/${ticketId}/comments?offset=${commentOffset}&limit=100`, { signal: current.signal })
      if (current.signal.aborted) return
      comments.value = [...comments.value, ...batch.filter(comment => data.value?.staff || !comment.internal)]
      commentOffset += batch.length; hasMore.value = batch.length === 100
    } catch (e) { if (!current.signal.aborted) fail(e) }
    finally { if (!current.signal.aborted) busy.value = false }
  }

  async function write(endpoint: string, payload: object) {
    if (!ticket.value || busy.value || !ready.value) return false
    const current = scope, ticketId = id.value, version = ticket.value.version
    busy.value = true; error.value = ''; notice.value = ''
    try {
      await post(`/api/tickets/${ticketId}/${endpoint}`, { ...payload, version })
      if (current.signal.aborted) return false
      return true
    } catch (e) {
      if (!current.signal.aborted) {
        fail(e)
        if (e instanceof ApiError && e.status === 409) {
          notice.value = '工单已变更，草稿仍保留。正在刷新，请核对最新记录后重新确认操作。'
          reload.value++
        }
      }
      return false
    } finally { if (!current.signal.aborted) busy.value = false }
  }
  async function submit(content: string, internal: boolean) {
    if (internal && !data.value?.staff) return
    if (await write('comments', { content, internal })) {
      successCount.value++; notice.value = '回复已发送。'; reload.value++
    }
  }
  async function cancel(reason: string) {
    if (!reason.trim() || !window.confirm('确定取消这张工单？取消后不能继续回复。')) return false
    if (!await write('cancel', { reason: reason.trim() })) return false
    notice.value = '工单已取消。'; reload.value++
    return true
  }
  return { data, ticket, comments, error, notice, busy, loading, ready, hasMore, successCount, reload, more, submit, cancel }
}
