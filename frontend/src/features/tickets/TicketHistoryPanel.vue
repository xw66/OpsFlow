<script setup lang="ts">
import { onScopeDispose, shallowRef, watch } from 'vue'
import { ApiError, errorText, request, session } from '../../api/http'
import type { TicketAssignmentRecord, TicketHistory } from '../../api/types'
import { dateTime, statusNames } from './format'
const props = defineProps<{ ticketId: number; version: number | undefined; accessible: boolean }>()
const emit = defineEmits<{ unavailable: [] }>()
const opened = shallowRef(false), kind = shallowRef<'history' | 'assignments'>('history')
const records = shallowRef<(TicketHistory | TicketAssignmentRecord)[]>([])
const loading = shallowRef(false), hasMore = shallowRef(false), error = shallowRef(''), reload = shallowRef(0)
let scope = new AbortController()
onScopeDispose(() => scope.abort())
watch([opened, kind, () => props.ticketId, () => props.version, () => props.accessible, session.token, reload], () => {
  scope.abort(); scope = new AbortController(); records.value = []; loading.value = false; hasMore.value = false; error.value = ''
  if (opened.value && props.accessible && session.token.value) load()
}, { flush: 'sync' })
async function load() {
  if (loading.value || !props.accessible || !session.token.value) return
  const current = scope
  loading.value = true; error.value = ''
  try {
    const page = await request<(TicketHistory | TicketAssignmentRecord)[]>(`/api/tickets/${props.ticketId}/${kind.value}?offset=${records.value.length}&limit=20`, { signal: current.signal })
    if (current.signal.aborted) return
    records.value = [...records.value, ...page]; hasMore.value = page.length === 20
  } catch (e) {
    if (!current.signal.aborted) {
      error.value = errorText(e)
      if (e instanceof ApiError && [401, 403, 404].includes(e.status)) { records.value = []; emit('unavailable') }
    }
  } finally { if (!current.signal.aborted) loading.value = false }
}
</script>

<template>
  <details v-show="accessible" class="history panel panel-padding" @toggle="opened = ($event.target as HTMLDetailsElement).open">
    <summary>操作记录</summary>
    <div class="history-tabs" role="group" aria-label="记录类型"><button :aria-pressed="kind === 'history'" @click="kind = 'history'">状态变化</button><button :aria-pressed="kind === 'assignments'" @click="kind = 'assignments'">分配记录</button></div>
    <p v-if="loading" role="status">正在读取记录…</p>
    <p v-if="error" class="error" role="alert">{{ error }} <button @click="reload++">重试记录</button></p>
    <p v-if="!loading && !error && !records.length" class="muted">暂无{{ kind === 'history' ? '状态变化' : '分配' }}记录。</p>
    <ol class="history-list"><li v-for="record in records" :key="record.id">
      <div class="record-heading"><strong>{{ record.operatorName || (record.operatorId === null ? '系统自动操作' : `操作人 #${record.operatorId}`) }}</strong><time>{{ dateTime(record.createdAt) }}</time></div>
      <template v-if="'toStatus' in record"><p>{{ record.fromStatus ? statusNames[record.fromStatus] : '开始' }} → {{ statusNames[record.toStatus] }}</p><p class="pre-wrap">{{ record.remark }}</p></template>
      <template v-else><p>{{ record.fromAssigneeName || '尚未分配' }} → {{ record.toAssigneeName }}</p><p class="muted">{{ record.fromGroupName }} → {{ record.toGroupName }}</p><p class="pre-wrap">{{ record.reason }}</p></template>
    </li></ol>
    <button v-if="hasMore" :disabled="loading" @click="load">加载更多记录</button>
  </details>
</template>

<style scoped>.history{margin-top:24px}.history summary{cursor:pointer;font-size:18px;font-weight:600}.history-tabs{display:flex;gap:10px;margin-top:20px}.history-tabs [aria-pressed=true]{color:var(--accent);border-color:var(--accent)}.history-list{list-style:none;padding:0}.history-list li{padding:18px 0;border-bottom:1px solid var(--line)}.record-heading{display:flex;gap:12px;flex-wrap:wrap;justify-content:space-between}.record-heading time{font-size:12px;color:var(--muted)}.history-list p{margin:8px 0 0}</style>
