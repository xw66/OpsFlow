<script setup lang="ts">
import TicketQueue from './TicketQueue.vue'
import AgentAvailability from './AgentAvailability.vue'
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">客户支持 · 当前分配</p><h1>服务工作台</h1><p class="muted">先处理分配给你的工单，让每一次跟进都有明确下一步。</p></div><AgentAvailability /></div>
  <TicketQueue view="MINE_ASSIGNED" />
</template>

