<script setup lang="ts">
import { shallowRef, watch } from 'vue'
import { ApiError, errorText, request, session } from '../../api/http'
import type { WorkloadPage } from '../../api/types'
const props = defineProps<{ groupId: number }>()
const emit = defineEmits<{ unavailable: [] }>()
const page = shallowRef<WorkloadPage | null>(null), offset = shallowRef(0), loading = shallowRef(false), error = shallowRef(''), reload = shallowRef(0)
const refreshedAt = shallowRef('')
watch(() => props.groupId, () => { offset.value = 0 }, { flush: 'sync' })
watch([() => props.groupId, session.token, offset, reload], async ([groupId, token], _, cleanup) => {
  page.value = null; loading.value = false; error.value = ''; refreshedAt.value = ''
  if (!token) return
  const controller = new AbortController(); cleanup(() => controller.abort())
  loading.value = true
  try {
    const result = await request<WorkloadPage>(`/api/support/groups/${groupId}/workload?offset=${offset.value}&limit=20`, { signal: controller.signal })
    if (!controller.signal.aborted) { page.value = result; refreshedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false }) }
  } catch (e) {
    if (!controller.signal.aborted) {
      error.value = errorText(e)
      if (e instanceof ApiError && [403, 404].includes(e.status)) emit('unavailable')
    }
  } finally { if (!controller.signal.aborted) loading.value = false }
}, { immediate: true })
</script>

<template>
  <section class="panel members" aria-labelledby="member-heading" :aria-busy="loading">
    <header><div><h2 id="member-heading">成员与工作量</h2><p class="muted">仅统计当前组。活动工单包含待接单、处理中、等待用户补充。</p></div><button :disabled="loading" @click="reload++">刷新工作量</button></header>
    <p v-if="loading" role="status">正在加载成员…</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="page && !page.items.length" class="muted">当前页没有客服成员。可返回上一页，或由管理员为该组添加成员。</p>
    <div v-if="page?.items.length" class="member-table">
      <table><caption class="sr-only">本组客服在线状态与工单量</caption><thead><tr><th scope="col">客服</th><th scope="col">接单状态</th><th scope="col">活动工单</th><th scope="col">处理中</th></tr></thead>
        <tbody><tr v-for="member in page.items" :key="member.userId"><th scope="row">{{ member.displayName }}<small>{{ member.username }}</small></th><td><span :class="{ online: member.available && member.online }">{{ !member.available ? '不可接单' : member.online ? '在线' : '离线' }}</span></td><td>{{ member.activeCount }}</td><td>{{ member.processingCount }}</td></tr></tbody>
      </table>
    </div>
    <small>不可接单表示账号、客服或组已停用，或客服角色已撤销。</small>
    <footer v-if="page"><span>本页 {{ page.items.length }} 位 · 更新于 {{ refreshedAt }}</span><div><button :disabled="loading || !offset" @click="offset = Math.max(0, offset - 20)">上一页成员</button><button :disabled="loading || !page.hasMore" @click="offset += 20">下一页成员</button></div></footer>
  </section>
</template>

<style scoped>
.members{margin-top:28px;padding:24px}.members header,.members footer{display:flex;justify-content:space-between;align-items:center;gap:16px}.members header p{font-size:13px;margin:8px 0 20px}.members header button{white-space:nowrap}.member-table{overflow-x:auto;margin-bottom:16px}table{width:100%;border-collapse:collapse;font-size:14px}th,td{text-align:left;padding:15px 12px;border-bottom:1px solid var(--line)}thead th{font-weight:500;color:var(--muted);white-space:nowrap}tbody th{font-weight:600}tbody th small{display:block;font-weight:400;margin-top:5px;overflow-wrap:anywhere}.online{color:var(--accent)}.members footer{font-size:12px;color:var(--muted);margin-top:22px}.members footer div{display:flex;gap:8px}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%)}@media(max-width:700px){.members{padding:16px}.members header,.members footer{align-items:flex-start;flex-direction:column}th,td{padding:12px 7px;font-size:12px}.members header p{margin-bottom:0}}
</style>
