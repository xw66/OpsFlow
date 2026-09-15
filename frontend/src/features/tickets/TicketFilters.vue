<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { Category, Priority, Status } from '../../api/types'
import { priorityNames, statusNames, statusText } from './format'
export interface Filters { keyword: string; status: Status | ''; categoryId: string; priority: Priority | ''; sla: 'ALL' | 'WARNING' | 'BREACHED' }
const props = defineProps<{ categories: Category[]; filters: Filters; busy: boolean; mine?: boolean }>()
const emit = defineEmits<{ apply: [value: Filters] }>()
const draft = reactive({ ...props.filters })
watch(() => props.filters, value => Object.assign(draft, value))
</script>

<template>
  <form class="filters" @submit.prevent="emit('apply', { ...draft })">
    <label class="search-label">查找工单<input v-model="draft.keyword" type="search" maxlength="200" placeholder="问题关键词或完整编号"></label>
    <label>状态<select v-model="draft.status"><option value="">全部状态</option><option v-for="(_, value) in statusNames" :key="value" :value="value">{{ statusText(value, mine) }}</option></select></label>
    <label>分类<select v-model="draft.categoryId"><option value="">所有分类</option><option v-for="c in categories" :key="c.id" :value="String(c.id)">{{ c.name }}</option></select></label>
    <label>优先级<select v-model="draft.priority"><option value="">全部优先级</option><option v-for="(name, value) in priorityNames" :key="value" :value="value">{{ name }}</option></select></label>
    <label>服务时限<select v-model="draft.sla"><option value="ALL">全部时限</option><option value="WARNING">即将到期</option><option value="BREACHED">已经到期</option></select></label>
    <button type="submit" :disabled="busy">查询</button>
    <button type="button" class="link-button" :disabled="busy" @click="emit('apply', { keyword: '', status: '', categoryId: '', priority: '', sla: 'ALL' })">清除</button>
  </form>
</template>

<style scoped>
.filters{display:flex;align-items:end;gap:12px;flex-wrap:wrap;padding:20px 24px;border-bottom:1px solid var(--line)}.filters label{margin:0;font-size:12px}.search-label{flex:1;min-width:180px}.filters input{width:100%}.filters select{max-width:170px}.filters button{margin-bottom:0}@media(max-width:700px){.filters{padding:16px}.search-label{flex-basis:100%}.filters select{max-width:150px}}
</style>
