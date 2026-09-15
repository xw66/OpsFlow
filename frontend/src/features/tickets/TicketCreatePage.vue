<script setup lang="ts">
import { shallowRef } from 'vue'
import { ApiError, errorText, post } from '../../api/http'
import type { Detail, TicketInput } from '../../api/types'
import TicketForm from './TicketForm.vue'
import TicketAttachments from './TicketAttachments.vue'
import { useLeaveGuard } from './useLeaveGuard'
const busy = shallowRef(false), dirty = shallowRef(false), error = shallowRef(''), uncertain = shallowRef(false)
const created = shallowRef<Detail | null>(null), files = shallowRef<File[]>([])
let attempt: { input: TicketInput; key: string } | null = null
useLeaveGuard(() => dirty.value || uncertain.value)
async function create(input?: TicketInput, selectedFiles?: File[]) {
  if (busy.value || created.value) return
  if (input && !attempt) { attempt = { input: structuredClone(input), key: crypto.randomUUID() }; files.value = selectedFiles || [] }
  if (!attempt) return
  busy.value = true; error.value = ''
  try {
    created.value = await post<Detail>('/api/tickets', attempt.input, { 'Idempotency-Key': attempt.key })
    dirty.value = false; uncertain.value = false
  } catch (e) {
    error.value = errorText(e)
    // 响应未知时冻结原始快照并复用幂等键，不能改内容后创建第二张工单。
    uncertain.value = !(e instanceof ApiError) || e.status === 0 || e.status >= 500
    if (!uncertain.value) attempt = null
  } finally { busy.value = false }
}
</script>

<template>
  <div class="create-page"><RouterLink class="back-link" to="/tickets">‹ 返回我的工单</RouterLink>
    <div class="page-heading"><div><p class="eyebrow">向服务团队提出问题</p><h1>{{ created ? '工单已创建' : '创建工单' }}</h1><p class="muted">{{ created ? `工单 #${created.ticket.id} 已保存，无需再次提交。` : '描述清楚问题，帮助我们更快开始处理。' }}</p></div></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="uncertain" class="notice" role="status">创建结果暂不确定，输入已保留。请重试原请求，系统会防止重复创建。<button :disabled="busy" @click="create()">重试原请求</button></div>
    <TicketForm v-if="!created" :disabled="busy || uncertain" @dirty="dirty = $event" @submit="create" />
    <template v-else><TicketAttachments :ticket-id="created.ticket.id" :initial-files="files" :can-upload="true" @pending="dirty = $event" /><div class="form-actions"><RouterLink class="button primary" :to="`/tickets/${created.ticket.id}`">查看工单进展</RouterLink></div></template>
  </div>
</template>

<style scoped>.create-page{max-width:800px;margin:auto}.notice button{margin:10px 0 0 10px}</style>
