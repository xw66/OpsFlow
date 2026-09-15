<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import type { User, WorkspaceDetail } from '../../api/types'
import { ApiError, errorText, post } from '../../api/http'
const props = defineProps<{ data: WorkspaceDetail | null; user: User; disabled: boolean }>()
const emit = defineEmits<{ changed: []; dirty: [value: boolean] }>()
const actions = computed(() => {
  if (!props.data?.staff) return []
  const ticket = props.data.detail.ticket
  const result: { endpoint: string; label: string; publicReason: boolean }[] = []
  if (ticket.status === 'ASSIGNED' && props.user.roles.includes('AGENT') && ticket.assigneeId === props.user.id) result.push({ endpoint: 'accept', label: '开始处理', publicReason: false })
  if (ticket.status === 'PROCESSING') result.push({ endpoint: 'resolve', label: '标记已解决', publicReason: true }, { endpoint: 'suspend', label: '等待用户补充', publicReason: true })
  if (ticket.status === 'PENDING') result.push({ endpoint: 'resume', label: '恢复处理', publicReason: false })
  if (ticket.status === 'RESOLVED') result.push({ endpoint: 'close', label: '关闭工单', publicReason: false })
  if (['RESOLVED', 'CLOSED'].includes(ticket.status) && props.data.manager) result.push({ endpoint: 'reopen', label: '重新打开', publicReason: false })
  return result
})
const selected = shallowRef<{ endpoint: string; label: string; publicReason: boolean; version: number; id: number } | null>(null)
const reason = shallowRef(''), error = shallowRef(''), busy = shallowRef(false)
const stale = computed(() => selected.value && (selected.value.version !== props.data?.detail.ticket.version || selected.value.id !== props.data?.detail.ticket.id || !actions.value.some(a => a.endpoint === selected.value?.endpoint)))
watch(reason, value => emit('dirty', Boolean(value)))
function choose(action: typeof actions.value[number]) {
  if (!props.data || props.disabled || busy.value) return
  selected.value = { ...action, version: props.data.detail.ticket.version, id: props.data.detail.ticket.id }; error.value = ''
}
async function submit() {
  if (!selected.value || stale.value || busy.value || props.disabled || !reason.value.trim()) return
  const attempt = { ...selected.value }
  busy.value = true; error.value = ''
  try {
    await post(`/api/tickets/${attempt.id}/${attempt.endpoint}`, { version: attempt.version, reason: reason.value.trim() })
    selected.value = null; reason.value = ''; emit('changed')
  } catch (e) { error.value = errorText(e); if (e instanceof ApiError && [403, 409].includes(e.status)) emit('changed') }
  finally { busy.value = false }
}
</script>
<template>
  <section v-show="actions.length || selected" class="actions" aria-label="工单处理操作">
    <div class="action-buttons"><button v-for="action in actions" :key="action.endpoint" :disabled="busy || disabled" @click="choose(action)">{{ action.label }}</button></div>
    <form v-if="selected" class="panel panel-padding" @submit.prevent="submit">
      <h2>{{ selected.label }}</h2><p class="muted">{{ selected.publicReason ? '说明会作为公开回复发送给用户。' : '说明记录在工单操作历史中；本操作不会发送公开回复。' }}</p>
      <label>操作说明<textarea v-model="reason" required maxlength="500" rows="3" :disabled="busy"></textarea></label>
      <p v-if="stale" class="notice" role="status">工单状态或版本已变化，说明已保留。请核对最新记录，重新选择可用操作后确认。</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="form-actions"><button type="button" :disabled="busy" @click="selected = null; reason = ''">取消操作</button><button class="primary" :disabled="busy || disabled || Boolean(stale) || !reason.trim()">确认{{ selected.label }}</button></div>
    </form>
  </section>
</template>
<style scoped>.actions{margin-bottom:24px}.action-buttons{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:16px}.actions textarea{width:100%}</style>
