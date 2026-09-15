<script setup lang="ts">
import { onMounted, onScopeDispose, shallowRef, watch } from 'vue'
import { ApiError, errorText, rawRequest, request, session } from '../../api/http'
import type { Attachment, WorkspaceDetail } from '../../api/types'
const props = withDefaults(defineProps<{ ticketId: number; canUpload: boolean; accessible?: boolean; initialFiles?: File[]; version?: number }>(), { accessible: true })
const emit = defineEmits<{ changed: []; pending: [value: boolean]; unavailable: [] }>()
const attachments = shallowRef<Attachment[]>([]), pending = shallowRef<File[]>(props.initialFiles || [])
const busy = shallowRef(false), error = shallowRef('')
const needsReview = shallowRef(false)
let attemptVersion: number | null = null
let scope = new AbortController()
onScopeDispose(() => scope.abort())
watch(pending, value => emit('pending', value.length > 0), { immediate: true })
async function load(current = scope) {
  const result = await request<Attachment[]>(`/api/tickets/${props.ticketId}/attachments`, { signal: current.signal })
  if (!current.signal.aborted) attachments.value = result
}
function fail(e: unknown) {
  error.value = errorText(e)
  if (e instanceof ApiError && [401, 403, 404].includes(e.status)) { attachments.value = []; emit('unavailable') }
}
watch([session.token, () => props.ticketId, () => props.accessible], async ([token, , accessible]) => {
  scope.abort(); scope = new AbortController()
  const current = scope
  attachments.value = []; busy.value = false
  if (!token || accessible === false) return
  try { await load(current) } catch (e) { if (!current.signal.aborted) fail(e) }
}, { immediate: true, flush: 'sync' })
async function upload() {
  if (busy.value || !pending.value.length || !props.canUpload || props.accessible === false || !session.token.value) return
  const current = scope
  let changed = false
  busy.value = true; error.value = ''
  try {
    while (pending.value.length && !current.signal.aborted && props.canUpload) {
      if (attemptVersion === null) {
        const detail = await request<WorkspaceDetail>(`/api/workspace/tickets/${props.ticketId}`, { signal: current.signal })
        if (current.signal.aborted) return
        attemptVersion = detail.detail.ticket.version
      }
      const form = new FormData(); form.append('file', pending.value[0])
      // 响应丢失后仍使用原版本重试，避免把同一文件在新版本下再次插入。
      await request<Attachment>(`/api/tickets/${props.ticketId}/attachments?version=${attemptVersion}`, { method: 'POST', body: form, signal: current.signal })
      if (current.signal.aborted) return
      attemptVersion = null; needsReview.value = false
      pending.value = pending.value.slice(1)
      changed = true
    }
  } catch (e) {
    if (!current.signal.aborted) {
      needsReview.value = e instanceof ApiError && e.status === 409
      fail(e)
      error.value = `工单已保存，附件未全部上传：${errorText(e)}。请先检查下方附件，再重试未上传的文件。`
    }
  } finally {
    if (!current.signal.aborted) {
      busy.value = false
      try { await load(current) } catch (e) { if (!current.signal.aborted) fail(e) }
      if (changed && !current.signal.aborted) emit('changed')
    }
  }
}
async function download(file: Attachment) {
  if (props.accessible === false || !session.token.value) return
  const current = scope
  error.value = ''
  try {
    const response = await rawRequest(`/api/tickets/${props.ticketId}/attachments/${file.id}`, { signal: current.signal })
    const blob = await response.blob()
    if (current.signal.aborted) return
    const url = URL.createObjectURL(blob), anchor = document.createElement('a')
    anchor.href = url; anchor.download = file.originalName; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) { if (!current.signal.aborted) fail(e) }
}
function canDelete(file: Attachment) {
  return props.accessible && props.canUpload && (file.uploaderId === session.user.value?.id || session.user.value?.roles.includes('ADMIN'))
}
async function remove(file: Attachment) {
  if (busy.value || !canDelete(file)) return
  const current = scope
  busy.value = true; error.value = ''
  let attempted = false
  try {
    const version = props.version ?? (await request<WorkspaceDetail>(`/api/workspace/tickets/${props.ticketId}`, { signal: current.signal })).detail.ticket.version
    if (current.signal.aborted || !window.confirm(`确定删除附件“${file.originalName}”？删除后不能恢复。`)) return
    attempted = true
    await request(`/api/tickets/${props.ticketId}/attachments/${file.id}?version=${version}`, { method: 'DELETE', signal: current.signal })
  } catch (e) {
    if (!current.signal.aborted) { fail(e); error.value = `删除未确认：${errorText(e)}。请核对刷新后的附件列表，再决定是否重试。` }
  } finally {
    if (!current.signal.aborted) {
      busy.value = false
      if (attempted) {
        try { await load(current) } catch (e) { if (!current.signal.aborted) fail(e) }
        if (!current.signal.aborted) emit('changed')
      }
    }
  }
}
function select(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files || [])
  if (files.length + attachments.value.length > 10 || files.some(f => f.size > 5 * 1024 * 1024 || f.size === 0 || !/\.(png|jpe?g|pdf)$/i.test(f.name))) {
    error.value = '最多10个附件，每个不超过5MB，支持PNG、JPEG、PDF'; return
  }
  pending.value = files; error.value = ''
}
function discard() { pending.value = []; attemptVersion = null; needsReview.value = false }
function prepareAgain() {
  if (window.confirm('请核对已上传附件。只有确认当前待上传文件尚未保存，才重新读取版本上传。继续吗？')) {
    attemptVersion = null; needsReview.value = false; upload()
  }
}
onMounted(() => { if (pending.value.length) upload() })
</script>

<template>
  <section class="attachments panel panel-padding" aria-label="工单附件"><h2>附件</h2>
    <ul v-if="attachments.length" class="file-list"><li v-for="file in attachments" :key="file.id"><button class="link-button" @click="download(file)">{{ file.originalName }}</button><small>{{ Math.ceil(file.sizeBytes / 1024) }} KB</small><button v-if="canDelete(file)" :disabled="busy" class="danger" :aria-label="`删除附件 ${file.originalName}`" @click="remove(file)">删除</button></li></ul>
    <p v-else class="muted">暂无附件</p>
    <template v-if="canUpload"><label>添加附件<input type="file" multiple accept="image/png,image/jpeg,application/pdf" :disabled="busy || pending.length > 0" @change="select"></label>
    </template>
    <div v-if="pending.length" class="notice"><p>等待上传：{{ pending.map(f => f.name).join('、') }}</p><template v-if="canUpload"><button v-if="!needsReview" :disabled="busy" @click="upload">{{ busy ? '正在上传…' : '上传 / 重试' }}</button><button v-else :disabled="busy" @click="prepareAgain">核对附件后重新准备上传</button></template><span v-else>当前不能上传，文件仍保留在本页。</span><button :disabled="busy" class="link-button" @click="discard">移除待上传文件</button></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>.attachments{margin-top:24px}.file-list{list-style:none;padding:0}.file-list li{display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid var(--line);padding:8px 0;gap:10px}.file-list button{padding-left:0;overflow-wrap:anywhere;text-align:left}.attachments label{margin:20px 0 0}</style>
