<script setup lang="ts">
import { computed, onScopeDispose, shallowRef, watch } from 'vue'
import { ApiError, errorText, request, session } from '../../api/http'
import type { Detail, TicketInput, WorkspaceDetail } from '../../api/types'
import TicketForm from './TicketForm.vue'
import { priorityNames, statusNames } from './format'

const props = defineProps<{ data: WorkspaceDetail | null; disabled: boolean }>()
const emit = defineEmits<{ changed: []; dirty: [value: boolean]; busy: [value: boolean] }>()
const canEdit = computed(() => props.data?.detail.ticket.status === 'CREATED' && (props.data.detail.ticket.userId === session.user.value?.id || session.user.value?.roles.includes('ADMIN')))
const attempt = shallowRef<{ id: number; version: number; input: TicketInput; categoryName: string } | null>(null)
const busy = shallowRef(false), dirty = shallowRef(false), error = shallowRef(''), notice = shallowRef(''), needsReview = shallowRef(false)
const stale = computed(() => needsReview.value || !canEdit.value || attempt.value?.version !== props.data?.detail.ticket.version)
let scope = new AbortController()
onScopeDispose(() => scope.abort())
watch(session.token, () => { scope.abort(); scope = new AbortController(); busy.value = false }, { flush: 'sync' })
watch(dirty, value => emit('dirty', value))
watch(busy, value => emit('busy', value))
function open() {
  if (!props.data || !canEdit.value || props.disabled) return
  const { ticket, tags } = props.data.detail
  attempt.value = { id: ticket.id, version: ticket.version, categoryName: props.data.display.categoryName,
    input: { title: ticket.title, description: ticket.description, categoryId: ticket.categoryId, priority: ticket.priority, tags: [...tags] } }
  error.value = ''; notice.value = ''; needsReview.value = false
}
function close() {
  if (dirty.value && !window.confirm('确定丢弃未保存的工单修改？')) return
  attempt.value = null; dirty.value = false; error.value = ''
}
function confirmVersion() {
  if (!attempt.value || !props.data || !canEdit.value || props.disabled || busy.value) return
  if (window.confirm('请先核对最新工单。继续后将用当前草稿修改最新版本，确定继续吗？')) {
    attempt.value = { ...attempt.value, version: props.data.detail.ticket.version }; needsReview.value = false; error.value = ''
  }
}
async function save(input: TicketInput) {
  if (!attempt.value || busy.value || props.disabled || stale.value) return
  const current = scope, snapshot = attempt.value
  busy.value = true; error.value = ''
  try {
    await request<Detail>(`/api/tickets/${snapshot.id}`, { method: 'PUT', body: JSON.stringify({ ticket: input, version: snapshot.version }), signal: current.signal })
    if (current.signal.aborted) return
    attempt.value = null; dirty.value = false; notice.value = '工单修改已保存。'; emit('changed')
  } catch (e) {
    if (current.signal.aborted) return
    error.value = errorText(e)
    if (!(e instanceof ApiError) || [0, 401, 403, 404, 409].includes(e.status) || e.status >= 500) { needsReview.value = true; emit('changed') }
  } finally { if (!current.signal.aborted) busy.value = false }
}
</script>

<template>
  <section v-show="canEdit || attempt || notice" class="ticket-edit" aria-label="编辑工单">
    <button v-if="canEdit && !attempt" :disabled="disabled" @click="open">编辑工单</button>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <template v-if="attempt">
      <div class="edit-heading"><h2>编辑工单</h2><button :disabled="busy" @click="close">取消编辑</button></div>
      <p v-if="stale" class="notice" role="status">工单状态或版本已变化，或保存结果尚未确认。草稿已保留，请核对最新内容。</p>
      <p v-if="stale && data" class="notice">当前工单：{{ data.detail.ticket.title }} · {{ statusNames[data.detail.ticket.status] }} · {{ data.display.categoryName }} · {{ priorityNames[data.detail.ticket.priority] }}</p>
      <button v-if="stale && canEdit" :disabled="busy || disabled" @click="confirmVersion">已核对最新内容，保留草稿继续编辑</button>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <TicketForm :key="attempt.id" :initial="attempt.input" :initial-category-name="attempt.categoryName" :disabled="busy || disabled || stale" @submit="save" @dirty="dirty = $event" />
    </template>
  </section>
</template>

<style scoped>.ticket-edit{margin-bottom:20px}.edit-heading{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:16px}.edit-heading h2{margin:0}.ticket-edit>.notice{overflow-wrap:anywhere}</style>
