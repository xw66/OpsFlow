<script setup lang="ts">
import { computed, nextTick, shallowRef } from 'vue'
import { useRoute } from 'vue-router'
import { session } from '../../api/http'
import { useTicketDetail } from './useTicketDetail'
import { dateTime, priorityNames, statusNames, statusText } from './format'
import ReplyComposer from './ReplyComposer.vue'
import TicketActions from './TicketActions.vue'
import TicketAssignment from './TicketAssignment.vue'
import TicketAttachments from './TicketAttachments.vue'
import TicketEdit from './TicketEdit.vue'
import TicketHistoryPanel from './TicketHistoryPanel.vue'
import AiAssistPanel from './AiAssistPanel.vue'
import { useLeaveGuard } from './useLeaveGuard'
const route = useRoute(), id = computed(() => Number(route.params.id))
const returnTo = computed(() => typeof route.query.from === 'string' && /^\/(tickets|work|team)(\?|$)/.test(route.query.from) ? route.query.from : '/tickets')
const { data, ticket, comments, error, notice, busy, loading, ready, hasMore, successCount, reload, more, submit, cancel: cancelTicket } = useTicketDetail(id)
const dirty = shallowRef(false), pendingFiles = shallowRef(false), actionDirty = shallowRef(false), reason = shallowRef('')
const assignmentDirty = shallowRef(false), assignmentBusy = shallowRef(false)
const editDirty = shallowRef(false), editBusy = shallowRef(false), aiDraft = shallowRef('')
const canReply = computed(() => Boolean(ready.value && ticket.value && !['CLOSED', 'CANCELLED'].includes(ticket.value.status)))
const canCancel = computed(() => ready.value && ticket.value?.status === 'CREATED' && (ticket.value.userId === session.user.value?.id || session.user.value?.roles.includes('ADMIN')))
useLeaveGuard(() => dirty.value || pendingFiles.value || actionDirty.value || assignmentDirty.value || editDirty.value || Boolean(reason.value))
async function importAiDraft(value: string) { aiDraft.value = ''; await nextTick(); aiDraft.value = value }
async function cancel() { if (!busy.value && canCancel.value && await cancelTicket(reason.value)) reason.value = '' }
function authorName(authorId: number) {
  if (authorId === session.user.value?.id) return `${session.user.value.displayName}（我）`
  if (authorId === data.value?.display.userId) return data.value.display.userName
  if (authorId === data.value?.display.assigneeId) return data.value.display.assigneeName
  return `服务团队成员 #${authorId}`
}
</script>

<template>
  <RouterLink class="back-link" :to="returnTo">‹ 返回{{ returnTo.startsWith('/work') ? '服务工作台' : returnTo.startsWith('/team') ? '团队工作台' : '我的工单' }}</RouterLink>
  <p v-if="loading" role="status" class="muted">正在读取工单…</p>
  <p v-if="error" class="error" role="alert">{{ error }} <button @click="reload++">刷新工单</button></p>
  <p v-if="notice" class="notice" role="status">{{ notice }}</p>
  <template v-if="ticket && data">
    <div class="page-heading"><div><div class="detail-meta"><span>#{{ ticket.id }}</span><span class="status" :class="ticket.status.toLowerCase()">{{ statusText(ticket.status, ticket.userId === session.user.value?.id) }}</span></div><h1>{{ ticket.title }}</h1><p class="muted">由 {{ data.display.userName }} 提交 · {{ dateTime(ticket.createdAt) }}</p></div></div>
  </template>
    <TicketEdit :key="id" :data="data" :disabled="busy || assignmentBusy || !ready" @changed="reload++" @dirty="editDirty = $event" @busy="editBusy = $event" />
    <TicketActions v-if="session.user.value" :key="id" :data="data" :user="session.user.value" :disabled="busy || assignmentBusy || editBusy || !ready" @changed="reload++" @dirty="actionDirty = $event" />
    <TicketAssignment :key="id" :data="data" :disabled="busy || editBusy || !ready" @changed="reload++" @dirty="assignmentDirty = $event" @busy="assignmentBusy = $event" />
    <AiAssistPanel v-if="ticket && data" :ticket="ticket" :staff="data.staff"  :disabled="!ready || busy || assignmentBusy || editBusy || !canReply" @draft="importAiDraft" @changed="reload++" />
    <div class="detail-layout"><div>
      <section v-show="data || dirty" class="panel panel-padding"><template v-if="ticket && data"><h2>问题描述</h2><p class="pre-wrap">{{ ticket.description }}</p><div class="tags"><span v-for="tag in data.detail.tags" :key="tag">{{ tag }}</span></div>
        <section class="conversation"><h2>交流记录</h2><p v-if="!comments.length" class="muted">{{ canReply ? '还没有交流记录，你可以补充问题信息。' : '暂无交流记录。' }}</p>
          <article v-for="comment in comments" :key="comment.id" class="message"><div class="message-heading"><strong>{{ comment.authorName || authorName(comment.authorId) }}</strong><span v-if="comment.internal" class="internal">内部备注</span><time>{{ dateTime(comment.createdAt) }}</time></div><p class="pre-wrap">{{ comment.content }}</p></article>
          <button v-if="hasMore" :disabled="busy" @click="more">加载后续交流</button>
        </section>
        </template>
        <ReplyComposer v-show="canReply || dirty" :key="id" :staff="Boolean(data?.staff)" :busy="busy" :disabled="!canReply || assignmentBusy || editBusy" :success-count="successCount" :initial-draft="aiDraft" @dirty="dirty = $event" @submit="submit" />
        <p v-if="ticket && ['CLOSED', 'CANCELLED'].includes(ticket.status)" class="notice">工单已归档，不能继续回复或修改附件。</p>
        <p v-if="!canReply && dirty" class="notice">当前不能发送回复。未发送草稿仍保留在当前页面，可复制或丢弃；刷新并确认权限及状态后才能发送。</p>
      </section>
      <TicketAttachments v-show="data || pendingFiles" :key="id" :ticket-id="id" :version="ticket?.version" :accessible="Boolean(data)" :can-upload="canReply && !editBusy && !assignmentBusy" @changed="reload++" @unavailable="reload++" @pending="pendingFiles = $event" />
      <TicketHistoryPanel :key="id" :ticket-id="id" :version="ticket?.version" :accessible="Boolean(data)" @unavailable="reload++" />
    </div><aside v-if="ticket && data" class="panel panel-padding"><h2>处理信息</h2><dl><div><dt>负责客服</dt><dd>{{ data.display.assigneeName || '等待分配' }}</dd></div><div><dt>客服组</dt><dd>{{ data.display.groupName }}</dd></div><div><dt>分类</dt><dd>{{ data.display.categoryName }}</dd></div><div><dt>优先级</dt><dd>{{ priorityNames[ticket.priority] }}</dd></div></dl>
      <section class="sla-section"><h2>服务时限</h2><p v-if="['CLOSED', 'CANCELLED', 'RESOLVED'].includes(ticket.status)" class="muted">当前处理已结束，以下为记录的服务时限。</p><p><small>首次响应截止</small><strong>{{ dateTime(ticket.responseDeadline) }}</strong><small>{{ ticket.firstResponseAt ? `已响应 ${dateTime(ticket.firstResponseAt)}` : '尚未首次响应' }}</small></p><p><small>解决截止</small><strong>{{ dateTime(ticket.resolveDeadline) }}</strong><small v-if="ticket.resolvedAt">已解决 {{ dateTime(ticket.resolvedAt) }}</small></p><p v-if="ticket.responseBreached || ticket.resolveBreached" class="warning">存在历史 SLA 违约记录</p><small>等待用户期间，服务时限持续计时。</small></section>
      <form v-if="canCancel" class="cancel-form" @submit.prevent="cancel"><details><summary>取消工单</summary><label>取消原因<textarea v-model="reason" required maxlength="500" rows="3"></textarea></label><button type="submit" class="danger" :disabled="busy">确认取消</button></details></form>
    </aside></div>
</template>

<style scoped>
.detail-meta{display:flex;gap:12px;align-items:center;color:var(--muted)}.action-panel{margin-bottom:24px}.action-panel label{margin:0}.detail-layout{display:grid;grid-template-columns:minmax(0,1fr) 300px;gap:24px;align-items:start}.detail-layout h1{overflow-wrap:anywhere}.conversation,.sla-section,.cancel-form{border-top:1px solid var(--line);padding-top:25px;margin-top:25px}.message{padding:20px 0;border-bottom:1px solid var(--line)}.message-heading{display:flex;align-items:center;gap:10px;font-size:13px;flex-wrap:wrap}.message-heading time{margin-left:auto;color:var(--muted);font-size:12px}.message p{margin-bottom:0}.internal{background:#fbf3e3;padding:4px 6px;font-size:12px}.tags{display:flex;gap:8px;flex-wrap:wrap}.tags span{font-size:12px;background:#f2f6f7;padding:5px 8px;border-radius:4px}dl{margin:0}dl>div{display:flex;justify-content:space-between;gap:15px;margin:20px 0;font-size:13px}dt{color:var(--muted);white-space:nowrap}dd{margin:0;text-align:right}.sla-section strong{display:block;margin:7px 0;font-size:18px}.cancel-form summary{cursor:pointer;color:var(--danger)}.cancel-form label{margin-top:18px}@media(max-width:1100px){.detail-layout{grid-template-columns:1fr}}@media(max-width:700px){.detail-layout{gap:18px}.message-heading time{width:100%;margin-left:0}.detail-layout aside{grid-row:auto}.sla-section{display:block}}
</style>
