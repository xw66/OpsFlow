<script setup lang="ts">
import { reactive, shallowRef } from 'vue'
import { errorText, login, post } from '../../api/http'
defineProps<{ expired: boolean }>()
const form = reactive({ username: '', password: '', displayName: '' })
const registering = shallowRef(false), busy = shallowRef(false), error = shallowRef(''), notice = shallowRef('')
async function submit() {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try {
    if (registering.value) {
      await post('/api/auth/register', { ...form, displayName: form.displayName.trim() })
      registering.value = false; notice.value = '注册成功，请使用刚才的密码登录。'
    } else await login(form.username, form.password)
  } catch (e) { error.value = errorText(e) }
  finally { busy.value = false }
}
</script>

<template>
  <section class="auth-card" role="dialog" aria-modal="true" aria-labelledby="auth-title">
    <div class="brand"><span class="brand-mark">of</span>OpsFlow</div>
    <p class="eyebrow">企业服务中心</p>
    <h1 id="auth-title">{{ registering ? '创建你的账号' : expired ? '请重新登录' : '让问题得到回应' }}</h1>
    <p class="muted">{{ expired ? '会话已过期，同一账号登录后可继续当前页面。' : '提交问题、追踪进展，与服务团队保持联系。' }}</p>
    <form @submit.prevent="submit">
      <fieldset :disabled="busy">
        <label>账号<input v-model="form.username" autocomplete="username" required pattern="[a-zA-Z0-9_]{3,32}" maxlength="32" placeholder="3–32位字母、数字或下划线"></label>
        <label v-if="registering">姓名<input v-model="form.displayName" autocomplete="name" required maxlength="64"></label>
        <label>密码<input v-model="form.password" type="password" :autocomplete="registering ? 'new-password' : 'current-password'" required :minlength="registering ? 10 : undefined" maxlength="72" :placeholder="registering ? '至少10位字符' : '输入你的密码'"></label>
        <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p>
        <button class="primary full" type="submit">{{ busy ? '正在提交…' : registering ? '注册账号' : '登录' }}</button>
        <button v-if="!expired" type="button" class="link-button full" @click="registering = !registering; error = ''; notice = ''">{{ registering ? '已有账号，返回登录' : '还没有账号？创建账号' }}</button>
      </fieldset>
    </form>
  </section>
</template>

<style scoped>
.auth-card{width:min(440px,100%);background:white;border:1px solid var(--line);border-radius:12px;padding:36px}.auth-card h1{font-size:26px}.auth-card .eyebrow{margin-top:36px}.auth-card .full{margin-top:12px}.auth-card .muted{margin-bottom:25px}@media(max-width:500px){.auth-card{padding:25px}}
</style>
