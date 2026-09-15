<script setup lang="ts">
import { shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, errorText, request, session } from '../../api/http'
import type { TicketPage } from '../../api/types'
import { useCategories } from './useCategories'
import TicketFilters, { type Filters } from './TicketFilters.vue'
import TicketList from './TicketList.vue'
const props = defineProps<{ view: 'MINE_CREATED' | 'MINE_ASSIGNED' | 'MY_GROUP'; groupId?: number; queue?: 'ALL' | 'MANUAL' | 'ESCALATED' }>()
const emit = defineEmits<{ unavailable: [] }>()
const route = useRoute(), router = useRouter()
const { categories, categoryError, reloadCategories } = useCategories()
const empty = (): TicketPage => ({ items: [], hasMore: false, offset: 0, limit: 20 })
const page = shallowRef<TicketPage>(empty()), loading = shallowRef(false), error = shallowRef(''), reload = shallowRef(0)
const filters = shallowRef<Filters>({ keyword: '', status: '', categoryId: '', priority: '', sla: 'ALL' })
watch([() => route.query, () => props.view, session.token, reload, () => props.groupId, () => props.queue], async ([query, view, token], _, cleanup) => {
  page.value = empty(); loading.value = false; error.value = ''
  if (!token) return
  const controller = new AbortController(); cleanup(() => controller.abort())
  loading.value = true
  filters.value = { keyword: String(query.keyword || ''), status: String(query.status || '') as Filters['status'],
    categoryId: String(query.categoryId || ''), priority: String(query.priority || '') as Filters['priority'], sla: String(query.sla || 'ALL') as Filters['sla'] }
  const params = new URLSearchParams({ view, limit: '20', offset: String(query.offset || '0') })
  if (view === 'MY_GROUP') {
    if (props.groupId) params.set('groupId', String(props.groupId))
    params.set('queue', props.queue || 'ALL')
  }
  Object.entries(filters.value).forEach(([key, value]) => { if (value) params.set(key, value) })
  try { const result = await request<TicketPage>(`/api/workspace/tickets?${params}`, { signal: controller.signal }); if (!controller.signal.aborted) page.value = result }
  catch (e) { if (!controller.signal.aborted) { error.value = errorText(e); if (e instanceof ApiError && [403, 404].includes(e.status)) emit('unavailable') } }
  finally { if (!controller.signal.aborted) loading.value = false }
}, { immediate: true })
function apply(value: Filters) { router.push({ query: { ...route.query, ...value, offset: '0' } }) }
</script>
<template>
  <p v-if="error" class="error" role="alert">{{ error }} <button @click="reload++">重新加载</button></p>
  <p v-if="categoryError" class="error" role="alert">{{ categoryError }} <button @click="reloadCategories++">重试分类</button></p>
  <section class="panel"><TicketFilters :categories="categories" :filters="filters" :busy="loading" :mine="view === 'MINE_CREATED'" @apply="apply" /><TicketList v-if="!error" :page="page" :loading="loading" :mine="view === 'MINE_CREATED'" :team="view === 'MY_GROUP'" :return-to="route.fullPath" @page="router.push({ query: { ...route.query, offset: String($event) } })" /></section>
</template>
