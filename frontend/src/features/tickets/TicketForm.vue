<script setup lang="ts">
import { computed, reactive, shallowRef, watch } from 'vue'
import { errorText, request, session } from '../../api/http'
import type { AvailablePriority, Priority, TicketInput } from '../../api/types'
import { useCategories } from './useCategories'
import { priorityNames } from './format'
const props = defineProps<{ disabled: boolean; initial?: TicketInput; initialCategoryName?: string }>()
const emit = defineEmits<{ submit: [input: TicketInput, files: File[]]; dirty: [value: boolean] }>()
const { categories, categoryError, reloadCategories } = useCategories()
const original = { title: props.initial?.title || '', description: props.initial?.description || '', categoryId: props.initial ? String(props.initial.categoryId) : '', priority: props.initial?.priority || '' as Priority | '', tags: props.initial?.tags.join('，') || '' }
const form = reactive({ ...original })
const priorities = shallowRef<AvailablePriority[]>([]), loading = shallowRef(false), error = shallowRef(''), files = shallowRef<File[]>([]), reload = shallowRef(0)
const selectedPolicy = computed(() => priorities.value.find(p => p.priority === form.priority))
const retainsPolicy = computed(() => Boolean(props.initial && form.categoryId === original.categoryId && form.priority === original.priority))
watch([() => form.categoryId, session.token, reload], async ([id, token], previous, cleanup) => {
  const previousPriority = form.priority
  const preservePriority = previous?.[0] === id || !previous?.[0] && id === original.categoryId
  priorities.value = []; error.value = ''; loading.value = false
  if (!preservePriority) form.priority = ''
  if (!id || !token) return
  const controller = new AbortController(); cleanup(() => controller.abort())
  loading.value = true
  try {
    const result = await request<AvailablePriority[]>(`/api/support/categories/${id}/priorities`, { signal: controller.signal })
    if (!controller.signal.aborted) {
      priorities.value = result
      form.priority = preservePriority && (result.some(p => p.priority === previousPriority) || id === original.categoryId && previousPriority === original.priority)
        ? previousPriority : result.some(p => p.priority === 'NORMAL') ? 'NORMAL' : ''
    }
  } catch (e) { if (!controller.signal.aborted) error.value = errorText(e) }
  finally { if (!controller.signal.aborted) loading.value = false }
}, { immediate: true })
watch([form, files], () => emit('dirty', JSON.stringify(form) !== JSON.stringify(original) || files.value.length > 0))
function submit() {
  if (props.disabled) return
  const tags = [...new Set(form.tags.split(/[,，]/).map(t => t.trim()).filter(Boolean))]
  if (!form.title.trim() || !form.description.trim()) { error.value = '请填写问题标题与描述'; return }
  if ((!selectedPolicy.value && !retainsPolicy.value) || !form.priority) { error.value = '请先选择有效分类与优先级'; return }
  if (tags.length > 10 || tags.some(t => t.length > 32)) { error.value = '最多10个标签，每个不超过32字'; return }
  if (files.value.length > 10 || files.value.some(f => f.size > 5 * 1024 * 1024 || f.size === 0 || !/\.(png|jpe?g|pdf)$/i.test(f.name))) {
    error.value = '附件最多10个，每个不超过5MB且不能为空，支持PNG、JPEG、PDF'; return
  }
  emit('submit', { title: form.title.trim(), description: form.description.trim(), categoryId: Number(form.categoryId), priority: form.priority, tags }, files.value)
}
</script>

<template>
  <form class="panel panel-padding" @submit.prevent="submit">
    <fieldset :disabled="disabled">
      <label>问题标题<input v-model="form.title" required maxlength="200" placeholder="例如：无法连接公司 VPN"></label>
      <label>问题描述<textarea v-model="form.description" required maxlength="10000" rows="6" placeholder="发生了什么？影响了哪些工作？你已尝试过哪些方法？"></textarea></label>
      <p v-if="categoryError" class="error" role="alert">{{ categoryError }} <button type="button" @click="reloadCategories++">重试</button></p>
      <div class="form-columns"><label>问题分类<select aria-label="问题分类" v-model="form.categoryId" required><option value="" disabled>请选择分类</option><option v-if="initial && !categories.some(c => c.id === initial?.categoryId)" :value="original.categoryId">{{ initialCategoryName || '原工单分类' }}（原分类）</option><option v-for="c in categories" :key="c.id" :value="String(c.id)">{{ c.name }}</option></select></label>
        <label>优先级<select aria-label="优先级" v-model="form.priority" required :disabled="loading || !priorities.length && !retainsPolicy"><option value="" disabled>请选择优先级</option><option v-if="retainsPolicy && !selectedPolicy" :value="original.priority">{{ priorityNames[initial!.priority] }}（原优先级）</option><option v-for="p in priorities" :key="p.priority" :value="p.priority">{{ priorityNames[p.priority] }}</option></select></label></div>
      <p v-if="loading" class="muted" role="status">正在读取服务时限…</p>
      <p v-else-if="form.categoryId && !priorities.length && !error && !retainsPolicy" class="notice">该分类暂未配置有效服务时限，请选择其他分类或联系管理员。</p>
      <p v-if="retainsPolicy" class="notice">沿用工单创建时的服务时限，编辑不会重新开始计时。</p>
      <p v-else-if="selectedPolicy" class="notice">首次响应 {{ selectedPolicy.responseMinutes }} 分钟内，解决 {{ selectedPolicy.resolveMinutes }} 分钟内。按自然时间计算。{{ initial ? '修改后从原始创建时间重算。' : '' }}</p>
      <label>标签 <span class="muted">选填</span><input v-model="form.tags" maxlength="329" placeholder="用逗号分隔，最多10个标签"></label>
      <label v-if="!initial">附件 <span class="muted">选填</span><input type="file" accept="image/png,image/jpeg,application/pdf" multiple @change="files = Array.from(($event.target as HTMLInputElement).files || [])"><small>PNG、JPEG、PDF，每个最多5MB。创建成功后上传，失败可单独重试。</small></label>
      <p v-if="error" class="error" role="alert">{{ error }} <button v-if="form.categoryId && !priorities.length" type="button" @click="reload++">重试服务时限</button></p>
      <div class="form-actions"><button class="primary" type="submit" :disabled="loading || !selectedPolicy && !retainsPolicy">{{ initial ? '保存修改' : disabled ? '正在创建…' : '提交工单' }}</button></div>
    </fieldset>
  </form>
</template>

<style scoped>
.form-columns{display:grid;grid-template-columns:1fr 1fr;gap:20px}.form-columns select{width:100%}small{margin-top:8px}@media(max-width:500px){.form-columns{grid-template-columns:1fr;gap:0}}
</style>
