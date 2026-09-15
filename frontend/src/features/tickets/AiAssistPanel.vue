<script setup lang="ts">
import { onScopeDispose, shallowRef, watch } from 'vue'
import { ApiError, errorText, request, session } from '../../api/http'
import type { Ticket } from '../../api/types'
type Kind = 'CLASSIFICATION' | 'SUMMARY' | 'REPLY'
interface Analysis { id: number; ticketId: number; ticketVersion: number; kind: Kind; status: string; resultJson: string | null; acceptedAt: string | null }
const props = defineProps<{ ticket: Ticket; staff: boolean; disabled?: boolean }>()
const emit = defineEmits<{ draft: [value: string]; changed: [] }>()
const items = shallowRef<Analysis[]>([]), busy = shallowRef(false), error = shallowRef(''), notice = shallowRef('')
function result(item: Analysis) { try { return item.resultJson ? JSON.parse(item.resultJson) as Record<string, unknown> : {} } catch { return {} } }
let scope = new AbortController()
onScopeDispose(() => scope.abort())
function unavailable(e: unknown) {
  error.value = errorText(e)
  if (e instanceof ApiError && [401, 403, 404].includes(e.status)) items.value = []
}
async function load(current = scope) {
  if (!session.token.value || props.disabled) { items.value = []; return false }
  try {
    const page = await request<Analysis[]>(`/api/tickets/${props.ticket.id}/ai-analyses?offset=0&limit=20`, { signal: current.signal })
    if (current.signal.aborted) return false
    items.value = page
    return true
  } catch (e) { if (!current.signal.aborted) unavailable(e); return false }
}
async function requestAi(kind: Kind) {
  if (busy.value || props.disabled) return
  const current = scope, ticket = props.ticket
  busy.value = true; error.value = ''
  try {
    const created = await request<Analysis>(`/api/tickets/${ticket.id}/ai-analyses`, { method: 'POST', body: JSON.stringify({ kind, version: ticket.version }), signal: current.signal })
    if (current.signal.aborted) return
    notice.value = '已提交AI请求，正在刷新结果'
    for (let attempt = 0; attempt < 20 && !current.signal.aborted; attempt++) {
      await load(current)
      const found = items.value.find(item => item.id === created.id)
      if (found && !['WAITING', 'PENDING', 'PROCESSING'].includes(found.status)) break
      await new Promise(resolve => window.setTimeout(resolve, 500))
    }
  } catch (e) { if (!current.signal.aborted) unavailable(e) }
  finally { if (!current.signal.aborted) busy.value = false }
}
async function accept(item: Analysis) {
  if (busy.value || props.disabled) return
  const current = scope, ticket = props.ticket
  busy.value = true; error.value = ''
  try {
    await request(`/api/tickets/${ticket.id}/ai-analyses/${item.id}/accept`, { method: 'POST', body: JSON.stringify({ version: ticket.version }), signal: current.signal })
    if (current.signal.aborted) return
    notice.value = '建议已人工采纳，工单已刷新'; emit('changed'); await load(current)
  } catch (e) { if (!current.signal.aborted) unavailable(e) }
  finally { if (!current.signal.aborted) busy.value = false }
}
function useDraft(item: Analysis) { if (item.ticketVersion !== props.ticket.version || !props.staff || props.disabled) return; const value = String(result(item).suggestion || ''); if (value) emit('draft', value) }
watch([() => props.ticket.id, () => props.ticket.version, session.token, () => props.staff, () => props.disabled], () => {
  scope.abort(); scope = new AbortController(); busy.value = false; items.value = []; error.value = ''; load(scope)
}, { immediate: true, flush: 'sync' })
</script>
<template><section class="panel panel-padding ai-panel"><details><summary><strong>AI辅助</strong><small>建议放入公开回复后编辑，由客服确认发送</small></summary><div class="ai-actions"><button :disabled="busy || disabled" @click="requestAi('CLASSIFICATION')">请求分类建议</button><button v-if="staff" :disabled="busy || disabled" @click="requestAi('SUMMARY')">请求摘要</button><button v-if="staff" :disabled="busy || disabled" @click="requestAi('REPLY')">请求回复建议</button><button :disabled="busy || disabled" @click="load()">刷新建议</button></div><p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p><article v-for="item in items" :key="item.id" class="ai-result"><header><strong>{{ item.kind === 'CLASSIFICATION' ? '分类建议' : item.kind === 'SUMMARY' ? '摘要' : '回复建议' }}</strong><span>{{ item.status }} · 版本 {{ item.ticketVersion }}</span></header><template v-if="item.status === 'SUCCEEDED'"><p v-if="item.kind === 'SUMMARY'">{{ result(item).summary }}</p><template v-else-if="item.kind === 'REPLY'"><p>{{ result(item).suggestion }}</p><button :disabled="busy || disabled || item.ticketVersion !== ticket.version" @click="useDraft(item)">放入公开回复草稿</button></template><p v-else>{{ result(item).category }} / {{ result(item).priority }}：{{ result(item).reason }}</p><button v-if="item.kind === 'CLASSIFICATION'" :disabled="busy || disabled || item.ticketVersion !== ticket.version" @click="accept(item)">采纳分类</button></template><p v-else class="muted">建议尚未完成或已失败。</p></article><p v-if="!items.length" class="muted">还没有当前工单版本的AI建议。</p></details></section></template>
<style scoped>.ai-panel{margin:20px 0}.ai-panel summary{cursor:pointer}.ai-panel summary small{display:block;color:var(--muted);margin-top:5px}.ai-actions{display:flex;gap:10px;flex-wrap:wrap;margin:16px 0}.ai-result{border-top:1px solid var(--line);padding:14px 0}.ai-result header{display:flex;justify-content:space-between;gap:10px}.ai-result header span{color:var(--muted);font-size:12px}.ai-result textarea{width:100%;margin:12px 0}</style>
