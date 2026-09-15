<script setup lang="ts">
import TicketQueue from './TicketQueue.vue'
</script>

<template>
  <div class="page-heading"><div><p class="eyebrow">我的服务空间</p><h1>我的工单</h1><p class="muted">提出问题、补充信息，随时了解处理进展。</p></div><RouterLink class="button primary" to="/tickets/new">＋ 创建工单</RouterLink></div>
  <TicketQueue view="MINE_CREATED" />
</template>
