<script setup lang="ts">
import { computed, onUnmounted, shallowRef } from 'vue'
import type { TicketPage } from '../../api/types'
import { dateTime, priorityNames, slaInfo, statusText } from './format'
const props = defineProps<{ page: TicketPage; loading: boolean; mine?: boolean; team?: boolean; returnTo?: string }>()
const emit = defineEmits<{ page: [offset: number] }>()
const now = shallowRef(Date.now())
const timer = setInterval(() => { now.value = Date.now() }, 30000)
onUnmounted(() => clearInterval(timer))
const rows = computed(() => props.page.items.map(ticket => ({ ticket, sla: slaInfo(ticket, now.value) })))
</script>

<template>
  <div :aria-busy="loading">
    <div class="table-head row-grid"><span>问题 / 工单</span><span>状态</span><span>负责客服</span><span>当前时限</span></div>
    <article v-for="{ ticket, sla } in rows" :key="ticket.id" class="ticket-row row-grid" :class="sla.tone">
      <div><RouterLink class="ticket-title" :to="{ path: `/tickets/${ticket.id}`, query: { from: returnTo } }">{{ ticket.title }}</RouterLink><div class="ticket-meta">#{{ ticket.id }} <span>{{ ticket.categoryName }}</span><span>{{ priorityNames[ticket.priority] }}</span></div></div>
      <span class="status" :class="ticket.status.toLowerCase()">{{ statusText(ticket.status, mine) }}</span>
      <span class="assignee">{{ ticket.assigneeName || '等待分配' }}</span>
      <div class="sla" :class="sla.tone">{{ sla.label }}<small>{{ dateTime(sla.deadline) }}</small></div>
    </article>
    <div v-if="!rows.length && !loading" class="empty"><h2>没有符合条件的工单</h2><p>{{ mine ? '可以清除筛选，或创建一张新工单。' : team ? '可以清除筛选，或切换客服组与队列查看其他工单。' : '可以清除筛选；保持在线后等待工单分配。' }}</p><RouterLink v-if="mine" class="button primary" to="/tickets/new">创建工单</RouterLink></div>
    <p v-if="loading" class="loading" role="status">正在加载工单…</p>
    <footer class="pagination"><span>本页 {{ page.items.length }} 张工单</span><div><button :disabled="loading || page.offset === 0" @click="emit('page', Math.max(0, page.offset - page.limit))">上一页</button><button :disabled="loading || !page.hasMore" @click="emit('page', page.offset + page.limit)">下一页</button></div></footer>
  </div>
</template>

<style scoped>
.row-grid{display:grid;grid-template-columns:minmax(200px,1fr) 95px 110px 180px;gap:20px;align-items:center}.table-head{padding:15px 24px;background:#f8fafb;font-size:12px;color:var(--muted)}.ticket-row{padding:21px 24px;border-bottom:1px solid var(--line);border-left:3px solid transparent}.ticket-row:hover{background:#f9fcfc}.ticket-row.warning{border-left-color:var(--warning)}.ticket-row.danger{border-left-color:var(--danger)}.ticket-title{font-weight:600;line-height:1.6}.ticket-title:hover{color:var(--accent)}.ticket-meta{display:flex;gap:12px;flex-wrap:wrap;margin-top:9px;color:var(--muted);font-size:12px}.assignee,.sla{font-size:13px}.sla small{display:block;margin-top:7px;color:var(--muted);font-size:12px}.pagination{display:flex;justify-content:space-between;align-items:center;padding:18px 24px;color:var(--muted);font-size:12px}.pagination div{display:flex;gap:8px}.loading{padding:20px;text-align:center}@media(max-width:1100px){.row-grid{grid-template-columns:minmax(160px,1fr) 90px 160px;gap:12px}.assignee,.table-head span:nth-child(3){display:none}}@media(max-width:700px){.table-head{display:none}.row-grid{grid-template-columns:1fr auto}.ticket-row{padding:18px 15px}.ticket-row>div:first-child{grid-column:1/-1}.sla{font-size:12px;text-align:right}.sla small{display:none}.pagination{padding:16px}.pagination button{padding-inline:10px}}
</style>
