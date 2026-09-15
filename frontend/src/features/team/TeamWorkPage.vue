<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTeamGroups } from './useTeamGroups'
import MemberWorkload from './MemberWorkload.vue'
import TicketQueue from '../tickets/TicketQueue.vue'
const route = useRoute(), router = useRouter()
const { allowed, groups, loading, error, reload } = useTeamGroups()
const lostAccess = shallowRef(false)
const group = computed(() => route.query.groupId === undefined ? groups.value[0] : groups.value.find(item => String(item.id) === route.query.groupId))
const queues = { ESCALATED: '升级待处理', MANUAL: '待人工分配', ALL: '全部工单' } as const
const queue = computed(() => typeof route.query.queue === 'string' && Object.hasOwn(queues, route.query.queue) ? route.query.queue as keyof typeof queues : 'ESCALATED')
function refresh() { lostAccess.value = false; reload.value++ }
function changeGroup(event: Event) { router.push({ query: { ...route.query, groupId: (event.target as HTMLSelectElement).value, offset: '0' } }) }
function changeQueue(value: keyof typeof queues) { router.push({ query: { ...route.query, queue: value, offset: '0' } }) }
</script>

<template>
  <div class="page-heading"><div><h1>团队工作台</h1><p class="muted">先跟进升级问题，再为等待中的工单安排负责人。</p></div><button v-if="allowed" :disabled="loading" @click="refresh">刷新团队</button></div>
  <p v-if="!allowed" class="notice" role="status">团队工作台仅向组长和管理员开放。</p>
  <p v-else-if="loading" role="status">正在加载可管理的客服组…</p>
  <p v-else-if="error || lostAccess" class="error" role="alert">{{ error || '团队访问权限已变化，请刷新团队后继续。' }}</p>
  <p v-else-if="!groups.length" class="notice">你还没有可管理的客服组，请联系管理员设置负责组。</p>
  <template v-else>
    <div class="team-toolbar"><label>客服组<select aria-label="客服组" :value="group?.id ?? ''" @change="changeGroup"><option v-if="!group" value="" disabled>请选择可管理的客服组</option><option v-for="item in groups" :key="item.id" :value="item.id">{{ item.name }}{{ item.enabled ? '' : '（已停用）' }}</option></select></label><p v-if="group && !group.enabled" class="notice">该组已停用，不能分配新工单。已有工单仍可查看及按权限处理。</p></div>
    <template v-if="group">
      <nav class="queue-tabs" aria-label="团队工单队列"><button v-for="(label, value) in queues" :key="value" :aria-pressed="queue === value" @click="changeQueue(value)">{{ label }}</button></nav>
      <p class="queue-hint muted">{{ queue === 'ESCALATED' ? '显示已经升级、尚未解决的工单；按当前服务时限排序。' : queue === 'MANUAL' ? '显示尚未分配的已创建工单。打开工单选择可用客服；没有可用客服时保留在此队列。' : '查看当前组的全部工单，可按状态和服务时限筛选。' }}</p>
      <TicketQueue :key="`queue-${reload}`" view="MY_GROUP" :group-id="group.id" :queue="queue" @unavailable="lostAccess = true" />
      <MemberWorkload :key="`members-${reload}`" :group-id="group.id" @unavailable="lostAccess = true" />
    </template>
    <p v-else class="notice" role="status">所选客服组不存在或不在你的管理范围，请选择其他组。</p>
  </template>
</template>

<style scoped>
.page-heading button{white-space:nowrap;flex-shrink:0}
.team-toolbar{display:flex;align-items:center;gap:20px;margin-bottom:20px}.team-toolbar label{margin:0;min-width:230px;max-width:100%}.team-toolbar select{width:100%;max-width:450px}.queue-tabs{display:flex;gap:6px;border-bottom:1px solid var(--line)}.queue-tabs button{border:0;border-radius:0;background:transparent;color:var(--muted);border-bottom:3px solid transparent;padding:14px 20px}.queue-tabs button[aria-pressed=true]{border-bottom-color:var(--accent);color:var(--accent);font-weight:600}.queue-hint{font-size:13px;line-height:1.7;margin:14px 0 20px}@media(max-width:700px){.team-toolbar{flex-direction:column;align-items:stretch}.team-toolbar label{min-width:0}.queue-tabs button{padding:12px 13px;font-size:13px}}
</style>
