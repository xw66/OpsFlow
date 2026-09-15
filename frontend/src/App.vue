<script setup lang="ts">
import { computed, shallowRef, useTemplateRef, watch } from 'vue'
import { request, session, logout } from './api/http'
import AuthForm from './features/auth/AuthForm.vue'
const user = session.user
const authenticated = computed(() => Boolean(session.token.value && user.value))
const main = useTemplateRef<HTMLElement>('main')
const unread = shallowRef(0)
async function loadUnread() { if (!session.token.value) { unread.value = 0; return }; try { unread.value = await request<number>('/api/notifications/unread-count') } catch { unread.value = 0 } }
watch(session.token, loadUnread, { immediate: true })
window.addEventListener('notifications-changed', loadUnread)
</script>

<template>
  <template v-if="user">
    <a class="skip" href="#main" @click.prevent="main?.focus()">跳到主要内容</a>
    <div class="shell" :inert="!authenticated">
      <aside class="sidebar">
        <RouterLink class="brand" to="/tickets"><span class="brand-mark">of</span>OpsFlow</RouterLink>
        <p class="workspace-name">企业服务中心<small>每一个问题，都有回应</small></p>
        <nav aria-label="主要导航"><RouterLink to="/tickets">我的工单</RouterLink><RouterLink to="/notifications">通知<span v-if="unread" class="unread-badge">{{ unread }}</span></RouterLink><RouterLink v-if="user.roles.some(role => role === 'LEADER' || role === 'ADMIN')" to="/statistics">统计看板</RouterLink><RouterLink v-if="user.roles.includes('AGENT')" to="/work">服务工作台</RouterLink><RouterLink v-if="user.roles.some(role => role === 'LEADER' || role === 'ADMIN')" to="/team">团队工作台</RouterLink><RouterLink v-if="user.roles.includes('ADMIN')" to="/admin">管理配置</RouterLink></nav>
        <div class="profile"><strong>{{ user.displayName }}</strong><small>{{ user.username }}</small><button class="link-button" @click="logout">退出登录</button></div>
      </aside>
      <div class="app-content">
        <header class="topbar">服务中心 <span>智能工单与 SLA 管理平台</span></header>
        <main id="main" ref="main" tabindex="-1"><RouterView v-slot="{ Component, route }"><component :is="Component" :key="`${user.id}:${route.path}`" /></RouterView></main>
        <footer class="app-footer">OpsFlow · 清晰的进展，可靠的协作</footer>
      </div>
    </div>
  </template>
  <div v-if="!authenticated" class="auth-screen" :class="{ overlay: user }"><AuthForm :expired="Boolean(user)" /></div>
</template>
