<script setup lang="ts">
import { computed, onMounted, useTemplateRef, watch } from 'vue'
import { priorities, roles, type Draft, type Lookups, type RecordItem } from './adminData'
const draft = defineModel<Draft>({ required: true })
const confirmed = defineModel<boolean>('confirmed', { required: true })
const props = defineProps<{ lookups: Lookups; ready: boolean; pending: boolean; refreshing: boolean; selfId?: number; original?: RecordItem; conflict: boolean; latest: RecordItem | null; reviewed: boolean; error: string }>()
const emit = defineEmits<{ save: []; cancel: []; reload: []; chooseUser: [id: number | ''] }>()
const heading = useTemplateRef<HTMLHeadingElement>('heading')
onMounted(() => heading.value?.focus())
const userAction = computed(() => draft.value.kind === 'roles' || draft.value.kind === 'enabled')
const self = computed(() => userAction.value && draft.value.userId === props.selfId)
const title = computed(() => ({ roles: '编辑用户角色', enabled: '调整账号状态', group: '客服组', member: '客服成员', category: '工单分类', sla: 'SLA规则' })[draft.value.kind])
const candidates = computed(() => props.lookups.users.filter(u => {
  if (userAction.value) return true
  const originalId = props.original && ('leaderId' in props.original ? props.original.leaderId : 'userId' in props.original ? props.original.userId : 0)
  return u.id === originalId || u.roles.includes(draft.value.kind === 'group' ? 'LEADER' : 'AGENT')
}))
const busy = computed(() => props.pending || props.refreshing)
const userName = (id: number) => props.lookups.users.find(u => u.id === id)?.displayName ?? '原关联用户不可用'
const groupName = (id: number) => props.lookups.groups.find(g => g.id === id)?.name ?? '原关联客服组不可用'
const categoryName = (id: number) => props.lookups.categories.find(c => c.id === id)?.name ?? '原关联分类不可用'
const currentSummary = computed(() => {
  const item = props.latest
  if (!item) return ''
  const state = item.enabled ? '启用' : '停用'
  if ('roles' in item) return `${item.displayName}；角色：${item.roles.map(r => roles[r]).join('、')}；${state}`
  if ('leaderId' in item) return `${item.name}；负责人：${userName(item.leaderId)}；${state}`
  if ('code' in item) return `${item.code} / ${item.name}；客服组：${groupName(item.groupId)}；${state}`
  if ('categoryId' in item) return `${categoryName(item.categoryId)}；${priorities[item.priority]}；响应${item.responseMinutes}分钟 / 解决${item.resolveMinutes}分钟；${item.autoEscalate ? '自动升级' : '不自动升级'}；${state}`
  return `${userName(item.userId)}；客服组：${groupName(item.groupId)}；${state}`
})
watch(() => JSON.stringify(draft.value), () => { confirmed.value = false })
</script>

<template>
  <section class="panel panel-padding admin-editor" aria-labelledby="admin-editor-heading">
    <h2 id="admin-editor-heading" ref="heading" tabindex="-1">{{ userAction ? title : `${draft.id ? '编辑' : '新增'}${title}` }}</h2>
    <p v-if="!ready" role="status">正在准备名称选择器；读取失败时可使用“重新读取名称”。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="self" class="notice">不能修改自己的角色或账号状态。</p>
    <form aria-label="管理员编辑表单" @submit.prevent="emit('save')">
      <fieldset :disabled="busy || !ready">
        <legend class="sr-only">{{ title }}</legend>
        <label v-if="userAction">用户
          <select name="userId" :value="draft.userId" required @change="emit('chooseUser', Number(($event.target as HTMLSelectElement).value) || '')">
            <option value="" disabled>请选择用户</option>
            <option v-for="user in candidates" :key="user.id" :value="user.id">{{ user.displayName }} · {{ user.username }}{{ user.enabled ? '' : '（停用）' }}</option>
          </select>
        </label>
        <label v-if="draft.kind === 'group' || draft.kind === 'category'">名称<input v-model="draft.name" name="name" required maxlength="64"></label>
        <label v-if="draft.kind === 'category'">代码<input v-model="draft.code" name="code" required maxlength="32" pattern="[A-Z][A-Z0-9_]{1,31}" title="2至32位大写字母、数字或下划线，以字母开头"></label>
        <label v-if="draft.kind === 'group'">负责人
          <select v-model="draft.leaderId" name="leaderId" required>
            <option value="" disabled>请选择负责人</option>
            <option v-for="user in candidates" :key="user.id" :value="user.id">{{ user.displayName }} · {{ user.username }}{{ user.enabled && user.roles.includes('LEADER') ? '' : '（不可启用，请更换或停用记录）' }}</option>
          </select>
        </label>
        <label v-if="draft.kind === 'member'">客服用户
          <select v-model="draft.userId" name="userId" :disabled="draft.id !== null" required>
            <option value="" disabled>请选择客服</option>
            <option v-for="user in candidates" :key="user.id" :value="user.id">{{ user.displayName }} · {{ user.username }}{{ user.enabled && user.roles.includes('AGENT') ? '' : '（不可启用，请停用记录）' }}</option>
          </select>
        </label>
        <label v-if="draft.kind === 'category' || draft.kind === 'member'">客服组
          <select v-model="draft.groupId" name="groupId" required>
            <option value="" disabled>请选择客服组</option>
            <option v-for="group in lookups.groups" :key="group.id" :value="group.id">{{ group.name }}{{ group.enabled ? '' : '（已停用）' }}</option>
          </select>
        </label>
        <template v-if="draft.kind === 'sla'">
          <label>工单分类<select v-model="draft.categoryId" name="categoryId" required><option value="" disabled>请选择工单分类</option><option v-for="category in lookups.categories" :key="category.id" :value="category.id">{{ category.name }} · {{ category.code }}{{ category.enabled ? '' : '（已停用）' }}</option></select></label>
          <label>优先级<select v-model="draft.priority" name="priority"><option v-for="(label, value) in priorities" :key="value" :value="value">{{ label }} · {{ value }}</option></select></label>
          <label>响应分钟<input v-model.number="draft.responseMinutes" name="responseMinutes" type="number" min="1" max="525600" step="1" required></label>
          <label>解决分钟<input v-model.number="draft.resolveMinutes" name="resolveMinutes" type="number" min="1" max="525600" step="1" required></label>
          <label class="check"><input v-model="draft.autoEscalate" name="autoEscalate" type="checkbox">自动升级</label>
        </template>
        <fieldset v-if="draft.kind === 'roles'" :disabled="self"><legend>角色</legend><label v-for="(label, value) in roles" :key="value" class="check"><input v-model="draft.roles" type="checkbox" name="roles" :value="value">{{ label }}</label></fieldset>
        <label v-else class="check"><input v-model="draft.enabled" name="enabled" type="checkbox" :disabled="self">启用{{ draft.kind === 'enabled' ? '账号' : '' }}</label>
        <label v-if="userAction">调整原因<textarea v-model="draft.reason" name="reason" required maxlength="500" :disabled="self"></textarea></label>
      </fieldset>
      <div v-if="conflict" class="notice">
        <button type="button" :disabled="busy" @click="emit('reload')">读取最新记录</button>
        <p v-if="latest">当前记录：{{ currentSummary }}</p>
        <p v-if="reviewed && !latest">新增配置所需关联已重新读取。</p>
        <label v-if="reviewed" class="check"><input v-model="confirmed" name="confirmed" type="checkbox" :disabled="busy">我已核对最新记录，确认使用保留的输入保存</label>
      </div>
      <div class="actions"><button type="submit" :disabled="busy || !ready || self || (conflict && (!reviewed || !confirmed))">{{ pending ? '保存中…' : '保存' }}</button><button type="button" :disabled="busy" @click="emit('cancel')">取消</button></div>
    </form>
  </section>
</template>

<style scoped>
.sr-only{position:absolute;width:1px;height:1px;padding:0;overflow:hidden;clip-path:inset(50%);white-space:nowrap}
.admin-editor{margin-bottom:20px}.admin-editor form{max-width:680px}.admin-editor fieldset{border:0;padding:0;min-width:0}.admin-editor fieldset fieldset{border:1px solid var(--line);padding:12px;margin-bottom:16px}.check{display:flex;gap:8px;align-items:center}.check input{width:auto;margin:0}.actions{display:flex;gap:12px;margin-top:16px}.admin-editor select,.admin-editor input,.admin-editor textarea{max-width:100%}.admin-editor .check{line-height:1.6}
</style>
