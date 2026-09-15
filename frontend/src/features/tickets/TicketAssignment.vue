<script setup lang="ts">
import { computed, onScopeDispose, shallowRef, watch } from 'vue'
import { ApiError, errorText, post, request, session } from '../../api/http'
import type { AssignmentCandidate, CandidatePage, WorkspaceDetail } from '../../api/types'

const props = defineProps<{ data: WorkspaceDetail | null; disabled: boolean }>()
const emit = defineEmits<{ changed: []; dirty: [value: boolean]; busy: [value: boolean] }>()
const mode = computed(() => props.data?.manager && props.data.detail.ticket.status === 'CREATED' ? 'assign'
  : props.data?.staff && ['ASSIGNED', 'PROCESSING', 'PENDING'].includes(props.data.detail.ticket.status) ? 'transfer' : null)
const attempt = shallowRef<{ id: number; version: number; groupId: number; mode: 'assign' | 'transfer' } | null>(null)
const candidates = shallowRef<AssignmentCandidate[]>([]), selected = shallowRef<AssignmentCandidate | null>(null)
const keyword = shallowRef(''), reason = shallowRef(''), error = shallowRef(''), notice = shallowRef('')
const loading = shallowRef(false), busy = shallowRef(false), hasMore = shallowRef(false), needsReview = shallowRef(false)
const stale = computed(() => needsReview.value || !props.data || attempt.value?.version !== props.data.detail.ticket.version || attempt.value?.mode !== mode.value)
const label = computed(() => attempt.value?.mode === 'assign' ? '分配工单' : '转派工单')
let scope = new AbortController(), search = ''
onScopeDispose(() => scope.abort())
watch(reason, value => emit('dirty', Boolean(value)))
watch(busy, value => emit('busy', value))
watch([session.token, () => props.data?.detail.ticket.id], () => {
  scope.abort(); scope = new AbortController(); candidates.value = []; selected.value = null; loading.value = false
}, { flush: 'sync' })

async function load(reset = true) {
  if (!props.data || !mode.value || busy.value || props.disabled) return
  if (reset) {
    scope.abort(); scope = new AbortController()
    const ticket = props.data.detail.ticket
    attempt.value = { id: ticket.id, version: ticket.version, groupId: ticket.groupId, mode: mode.value }
    candidates.value = []; selected.value = null; search = keyword.value.trim(); needsReview.value = false
  }
  if (!attempt.value || loading.value && !reset) return
  const current = scope, snapshot = attempt.value
  loading.value = true; error.value = ''; notice.value = ''
  try {
    const page = await request<CandidatePage>(`/api/workspace/tickets/${snapshot.id}/assignment-candidates?limit=20&offset=${candidates.value.length}&keyword=${encodeURIComponent(search)}`, { signal: current.signal })
    if (current.signal.aborted) return
    if (page.ticketVersion !== snapshot.version) { needsReview.value = true; emit('changed'); return }
    candidates.value = [...candidates.value, ...page.items]; hasMore.value = page.hasMore
  } catch (e) {
    if (!current.signal.aborted) {
      error.value = errorText(e)
      if (e instanceof ApiError && [401, 403, 404, 409].includes(e.status)) { candidates.value = []; selected.value = null; needsReview.value = true; emit('changed') }
    }
  } finally { if (!current.signal.aborted) loading.value = false }
}
function close() { scope.abort(); attempt.value = null; candidates.value = []; selected.value = null; reason.value = ''; error.value = ''; loading.value = false }
async function submit() {
  if (!attempt.value || !selected.value || stale.value || loading.value || busy.value || props.disabled || !reason.value.trim()) return
  const snapshot = attempt.value, target = selected.value, token = session.token.value
  busy.value = true; error.value = ''
  try {
    await post(`/api/tickets/${snapshot.id}/${snapshot.mode}`, { assigneeId: target.userId, version: snapshot.version, reason: reason.value.trim() })
    if (token !== session.token.value) return
    close(); notice.value = `${snapshot.mode === 'assign' ? '分配' : '转派'}已完成，负责客服为${target.displayName}。`; emit('changed')
  } catch (e) {
    if (token !== session.token.value) return
    error.value = errorText(e); needsReview.value = true; selected.value = null; candidates.value = []; emit('changed')
  } finally { busy.value = false }
}
</script>

<template>
  <section v-show="mode || attempt || notice" class="assignment" aria-label="工单分配与转派">
    <button v-if="mode && !attempt" :disabled="disabled" @click="load()">{{ mode === 'assign' ? '分配工单' : '转派工单' }}</button>
    <p v-if="notice" class="notice" role="status">{{ notice }}若已无查看权限，可返回工作台继续处理其他工单。</p>
    <form v-if="attempt" class="panel panel-padding" @submit.prevent="submit">
      <h2>{{ label }}</h2>
      <p class="muted">仅列出授权范围内在线、启用的客服。按活动工单量、最久未分配时间排序。</p>
      <div class="candidate-search"><label>查找客服或组<input v-model="keyword" maxlength="64" :disabled="busy" @keydown.enter.prevent="load()"></label><button type="button" :disabled="busy || disabled || !mode" @click="load()">{{ stale ? '重新读取候选' : '搜索 / 刷新候选' }}</button></div>
      <p v-if="loading" role="status">正在读取可用客服…</p>
      <p v-if="stale" class="notice" role="status">工单或候选已变化，或上次操作结果尚未确认。说明仍保留，请核对最新工单并重新读取候选后再确认。</p>
      <fieldset :disabled="busy || disabled || stale || loading"><legend>选择负责客服</legend>
        <label v-for="candidate in candidates" :key="candidate.userId" class="candidate"><input v-model="selected" type="radio" name="assignee" :value="candidate"><span><strong>{{ candidate.displayName }}</strong> <small>{{ candidate.username }}</small><span>{{ candidate.groupName }} · 在线 · 活动工单 {{ candidate.activeCount }}</span></span></label>
        <p v-if="!candidates.length && !loading && !error && !stale" class="muted">没有符合条件的可用客服。可调整搜索条件或稍后重试；工单保持原状。</p>
        <button v-if="hasMore && candidates.length" type="button" @click="load(false)">加载更多客服</button>
      </fieldset>
      <small>活动工单包含已分配、处理中、等待用户回复。</small>
      <label>分配 / 转派说明<textarea v-model="reason" required maxlength="500" rows="3" :readonly="busy"></textarea></label>
      <p v-if="selected" class="notice">将交给 {{ selected.groupName }} 的 {{ selected.displayName }} 处理。{{ selected.groupId !== attempt.groupId ? '这是跨组转派，当前组可能失去访问权限。' : '' }}</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="form-actions"><button type="button" :disabled="busy" @click="close">取消操作</button><button type="submit" class="primary" :disabled="busy || disabled || stale || loading || !selected || !reason.trim()">{{ busy ? '正在提交…' : `确认${label}` }}</button></div>
    </form>
  </section>
</template>

<style scoped>
.assignment{margin-bottom:24px}.candidate-search{display:flex;align-items:end;gap:12px;margin-bottom:18px}.candidate-search label{flex:1;margin:0}.candidate{display:flex;align-items:center;gap:12px;border-bottom:1px solid var(--line);padding:12px 0;margin:0}.candidate input{width:auto;min-height:auto}.candidate span span{display:block;margin-top:5px;font-size:13px;color:var(--muted)}.candidate-search button{white-space:nowrap}.assignment fieldset{margin:16px 0}.assignment textarea{width:100%}@media(max-width:600px){.candidate-search{align-items:stretch;flex-direction:column}}
</style>
