<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
const props = defineProps<{ busy: boolean; disabled?: boolean; successCount: number; staff: boolean; initialDraft?: string }>()
const emit = defineEmits<{ submit: [content: string, internal: boolean]; dirty: [value: boolean] }>()
const publicDraft = shallowRef(''), internalDraft = shallowRef('')
const internal = shallowRef(false)
const draft = computed({ get: () => internal.value ? internalDraft.value : publicDraft.value,
  set: (value: string) => { if (internal.value) internalDraft.value = value; else publicDraft.value = value } })
const blocked = computed(() => props.busy || props.disabled || (internal.value && !props.staff))
let submittedInternal = false
watch([publicDraft, internalDraft], () => emit('dirty', Boolean(publicDraft.value || internalDraft.value)))
watch(() => props.initialDraft, value => {
  if (!value || value === publicDraft.value) return
  if (!publicDraft.value || window.confirm('公开回复草稿尚未发送，是否用 AI 建议替换？')) { internal.value = false; publicDraft.value = value }
})
watch(() => props.successCount, () => { if (submittedInternal) internalDraft.value = ''; else publicDraft.value = '' })
function submit() {
  if (blocked.value || !draft.value.trim()) return
  submittedInternal = internal.value
  emit('submit', draft.value.trim(), internal.value)
}
function switchMode(event: Event) { internal.value = (event.target as HTMLInputElement).checked }
function discard() {
  if (window.confirm('确定丢弃当前草稿？')) draft.value = ''
}
</script>

<template>
  <form class="composer" @submit.prevent="submit">
    <label for="reply">{{ internal ? '内部备注' : staff ? '公开回复' : '补充信息' }}<span>{{ internal ? '仅服务团队可见' : '提交人和服务团队可见' }}</span></label>
    <label v-if="staff || internalDraft" class="internal-toggle"><input :checked="internal" type="checkbox" :disabled="busy" @change="switchMode">仅内部可见</label>
    <p v-if="internal && !staff" class="notice" role="status">当前无内部备注权限。草稿仅供复制或丢弃，不会转为公开回复。</p>
    <textarea id="reply" v-model="draft" rows="4" maxlength="5000" required :readonly="blocked" placeholder="补充问题信息，或说明处理进展…"></textarea>
    <div class="composer-footer"><small>公开回复与内部备注分别保存草稿。</small><button v-if="draft" type="button" :disabled="busy" @click="discard">丢弃当前草稿</button><button class="primary" type="submit" :disabled="blocked || !draft.trim()">{{ busy ? '正在发送…' : '发送回复' }}</button></div>
  </form>
</template>

<style scoped>
.composer .internal-toggle{justify-content:flex-start;align-items:center}.internal-toggle input{width:auto;min-height:auto}.composer .notice{margin:0 16px 16px}.composer-footer{flex-wrap:wrap}
.composer{margin-top:25px;border:1px solid #cbdce0;border-radius:7px;overflow:hidden}.composer label{padding:16px;margin:0;display:flex;justify-content:space-between;gap:10px}.composer label span{color:var(--muted);font-size:12px;font-weight:400}.composer textarea{border:0;border-radius:0;padding:4px 16px 16px;outline-offset:-3px}.composer-footer{border-top:1px solid var(--line);padding:14px 16px;display:flex;justify-content:space-between;align-items:center;gap:15px}.composer-footer small{max-width:200px}@media(max-width:500px){.composer label{display:block}.composer label span{display:block;margin-top:6px}.composer-footer small{max-width:150px}.composer-footer button{white-space:nowrap;padding-inline:10px}}
</style>
