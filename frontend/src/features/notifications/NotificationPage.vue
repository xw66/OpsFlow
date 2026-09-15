<script setup lang="ts">
import { onScopeDispose, shallowRef, watch } from 'vue'
import { ApiError, errorText, request, session } from '../../api/http'
import type { Notification } from '../../api/types'
const items = shallowRef<Notification[]>([]), loading = shallowRef(false), error = shallowRef(''), offset = shallowRef(0), hasMore = shallowRef(false), reload = shallowRef(0)
let scope = new AbortController()
onScopeDispose(() => scope.abort())
function clear() { items.value = []; hasMore.value = false }
function fail(e: unknown) { error.value = errorText(e); if (e instanceof ApiError && [401, 403].includes(e.status)) clear() }
async function load(current = scope) {
  if (!session.token.value) { clear(); return }
  clear(); loading.value = true; error.value = ''
  try {
    const page = await request<Notification[]>(`/api/notifications?offset=${offset.value}&limit=20`, { signal: current.signal })
    if (current.signal.aborted) return
    items.value = page; hasMore.value = page.length === 20
  } catch (e) { if (!current.signal.aborted) fail(e) }
  finally { if (!current.signal.aborted) loading.value = false }
}
async function read(item: Notification) {
  if (item.readAt || loading.value) return
  const current = scope
  try {
    await request(`/api/notifications/${item.id}/read`, { method: 'PUT', signal: current.signal })
    if (current.signal.aborted) return
    items.value = items.value.map(row => row.id === item.id ? { ...row, readAt: new Date().toISOString() } : row)
    window.dispatchEvent(new Event('notifications-changed'))
  } catch (e) { if (!current.signal.aborted) fail(e) }
}
watch([session.token, offset, reload], () => { scope.abort(); scope = new AbortController(); loading.value = false; load(scope) }, { immediate: true, flush: 'sync' })
</script>
<template><div class="page-heading"><div><h1>通知</h1><p class="muted">查看工单进展、分配和 SLA 提醒。</p></div><button :disabled="loading" @click="reload++">刷新</button></div><p v-if="error" class="error" role="alert">{{ error }} <button @click="reload++">重试</button></p><p v-if="loading" role="status">正在读取通知…</p><section v-else-if="!error" class="panel panel-padding"><p v-if="!items.length" class="muted">暂无通知。</p><ul v-else class="notifications"><li v-for="item in items" :key="item.id" :class="{ unread: !item.readAt }"><div><strong>{{ item.content }}</strong><small>{{ new Date(item.createdAt).toLocaleString() }}</small></div><span><RouterLink :to="`/tickets/${item.ticketId}`" @click="read(item)">查看工单</RouterLink><button v-if="!item.readAt" @click="read(item)">标为已读</button></span></li></ul><footer v-if="items.length || offset" class="pager"><button :disabled="loading || !offset" @click="offset = Math.max(0, offset - 20)">上一页</button><button :disabled="loading || !hasMore" @click="offset += 20">下一页</button></footer></section></template>
<style scoped>.notifications{list-style:none;padding:0;margin:0}.notifications li{display:flex;justify-content:space-between;gap:20px;padding:16px 0;border-bottom:1px solid var(--line)}.notifications li.unread{border-left:3px solid var(--accent);padding-left:12px}.notifications small{display:block;color:var(--muted);margin-top:6px}.notifications span{display:flex;align-items:center;gap:10px;white-space:nowrap}@media(max-width:700px){.notifications li{display:block}.notifications span{margin-top:10px}}</style>
