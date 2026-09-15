<script setup lang="ts">
import { shallowRef, watch } from 'vue'
import { errorText, request, session } from '../../api/http'
interface Agent { online: boolean; enabled: boolean; version: number }
const agent = shallowRef<Agent | null>(null), busy = shallowRef(false), error = shallowRef(''), reload = shallowRef(0)
watch([session.token, reload], async ([token], _, cleanup) => {
  if (!token) return
  const controller = new AbortController(); cleanup(() => controller.abort())
  error.value = ''
  try {
    const result = await request<Agent>('/api/support/agents/me', { signal: controller.signal })
    if (!controller.signal.aborted) agent.value = result
  } catch (e) { if (!controller.signal.aborted) { agent.value = null; error.value = errorText(e) } }
}, { immediate: true })
async function toggle() {
  if (!agent.value || busy.value) return
  busy.value = true; error.value = ''
  try { agent.value = await request<Agent>('/api/support/agents/me/online', { method: 'PUT', body: JSON.stringify({ online: !agent.value.online, version: agent.value.version }) }) }
  catch (e) { error.value = errorText(e); agent.value = null }
  finally { busy.value = false }
}
</script>
<template>
  <div><button v-if="agent" :disabled="busy || !agent.enabled" @click="toggle">{{ busy ? '正在更新…' : agent.online ? '在线接单 · 切换离线' : '当前离线 · 开始接单' }}</button>
    <span v-else-if="!error" role="status">正在读取在线状态…</span>
    <p v-if="error" role="alert" class="error">{{ error }} <button @click="reload++">重新读取</button></p></div>
</template>
