<script setup lang="ts">
import { reactive } from 'vue'
import { session } from '../../api/http'
import AdminEditor from './AdminEditor.vue'
import { roles } from './adminData'
import { useAdmin } from './useAdmin'
const admin = reactive(useAdmin())
const tabs = { users: '用户账号', groups: '客服组', members: '客服成员', categories: '工单分类', sla: 'SLA规则' } as const
const userName = (id: number) => admin.lookups.users.find(u => u.id === id)?.displayName ?? '关联用户不可用'
const groupName = (id: number) => admin.lookups.groups.find(g => g.id === id)?.name ?? '关联客服组不可用'
const categoryName = (id: number) => admin.lookups.categories.find(c => c.id === id)?.name ?? '关联分类不可用'
</script>

<template>
  <div class="page-heading">
    <div><h1>管理配置</h1><p class="muted">管理账号、客服组、成员、工单分类和 SLA 规则。</p></div>
    <button v-if="admin.allowed" :disabled="admin.saving || admin.refreshing" @click="admin.refresh">刷新</button>
  </div>
  <p v-if="!admin.allowed" class="notice" role="status">{{ admin.error || '管理配置仅向管理员开放。' }}</p>
  <template v-else>
    <nav class="admin-tabs" aria-label="管理配置模块">
      <button v-for="(label, value) in tabs" :key="value" :aria-pressed="admin.tab === value" :disabled="admin.saving || admin.refreshing" @click="admin.tab = value">{{ label }}</button>
    </nav>
    <p v-if="admin.error" class="error" role="alert">{{ admin.error }}</p>
    <p v-if="admin.notice" class="notice" role="status">{{ admin.notice }}</p>
    <p v-if="admin.lookupError" class="error" role="alert">{{ admin.lookupError }}</p>
    <div class="lookup-status"><span v-if="admin.lookupLoading" role="status">正在读取完整名称…</span><button :disabled="admin.lookupLoading || admin.saving || admin.refreshing" @click="admin.loadLookups">重新读取名称</button></div>
    <AdminEditor v-if="admin.draft" v-model="admin.draft" v-model:confirmed="admin.confirmed"
      :lookups="admin.lookups" :ready="admin.lookupReady" :pending="admin.saving" :refreshing="admin.refreshing"
      :self-id="session.user.value?.id" :original="admin.original" :conflict="admin.conflict" :latest="admin.latest" :reviewed="admin.reviewed" :error="admin.formError"
      @save="admin.save" @cancel="admin.close" @reload="admin.refreshVersion" @choose-user="admin.chooseUser" />

    <section v-if="admin.tab === 'users'" class="panel panel-padding" aria-label="用户账号">
      <form class="admin-filter" aria-label="用户查询" @submit.prevent="admin.search">
        <label>查找账号或姓名<input v-model="admin.filters.keyword" name="keyword" maxlength="100"></label>
        <label>账号状态<select v-model="admin.filters.enabled" name="status"><option value="">全部状态</option><option value="true">启用</option><option value="false">停用</option></select></label>
        <label>角色<select v-model="admin.filters.role" name="role"><option value="">全部角色</option><option v-for="(label, value) in roles" :key="value" :value="value">{{ label }}</option></select></label>
        <button>查询</button>
      </form>
      <button :disabled="admin.saving || admin.refreshing || !admin.lookupReady" @click="admin.open('roles')">选择用户编辑角色</button>
      <p v-if="admin.loading" role="status">正在读取用户…</p>
      <div v-else-if="admin.users" class="table-scroll">
        <table><thead><tr><th>用户</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
          <tbody><tr v-for="user in admin.users.items" :key="user.id">
            <th>{{ user.displayName }}<small>{{ user.username }}</small></th><td>{{ user.roles.map(r => roles[r]).join('、') }}</td><td>{{ user.enabled ? '启用' : '停用' }}</td>
            <td><button :disabled="user.id === session.user.value?.id || admin.saving || admin.refreshing || !admin.lookupReady" @click="admin.open('roles', user)">编辑角色</button><button :disabled="user.id === session.user.value?.id || admin.saving || admin.refreshing || !admin.lookupReady" @click="admin.open('enabled', user)">{{ user.enabled ? '停用账号' : '启用账号' }}</button></td>
          </tr></tbody>
        </table><p v-if="!admin.users.items.length" class="muted">没有符合条件的用户。</p>
      </div>
    </section>

    <section v-else-if="admin.tab === 'members'" class="panel panel-padding" aria-label="客服成员">
      <h2>客服成员</h2>
      <label>选择客服组<select v-model="admin.selectedGroupId" name="memberGroup" :disabled="admin.lookupLoading || admin.saving || admin.refreshing"><option value="">请选择客服组</option><option v-for="group in admin.lookups.groups" :key="group.id" :value="group.id">{{ group.name }}{{ group.enabled ? '' : '（已停用）' }}</option></select></label>
      <button :disabled="admin.saving || admin.refreshing || !admin.selectedGroupId" @click="admin.open('member')">新增成员</button>
      <button :disabled="admin.memberLoading || !admin.selectedGroupId" @click="admin.loadMembers">刷新成员</button>
      <p v-if="admin.memberLoading" role="status">正在读取成员…</p>
      <p v-else-if="admin.memberError" class="error" role="alert">{{ admin.memberError }}</p>
      <div v-else-if="admin.selectedGroupId" class="table-scroll">
        <table><thead><tr><th>客服</th><th>客服组</th><th>在线状态</th><th>成员状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="member in admin.members" :key="member.id"><th>{{ userName(member.userId) }}</th><td>{{ groupName(member.groupId) }}</td><td>{{ member.online ? '在线' : '离线' }}</td><td>{{ member.enabled ? '启用' : '停用' }}</td><td><button :disabled="admin.saving || admin.refreshing" @click="admin.open('member', member)">编辑成员</button></td></tr>
        </tbody></table><p v-if="!admin.members.length">该页没有成员。</p>
      </div>
      <footer v-if="admin.selectedGroupId" class="pager" aria-label="成员分页">
        <span>第 {{ admin.memberOffset / 20 + 1 }} 页</span><button :disabled="admin.memberLoading || admin.memberOffset === 0" @click="admin.memberOffset -= 20">上一页成员</button><button :disabled="admin.memberLoading || !admin.memberHasMore || admin.memberOffset >= 100000" @click="admin.memberOffset += 20">下一页成员</button>
      </footer>
    </section>

    <section v-else class="panel panel-padding" :aria-label="tabs[admin.tab]">
      <div class="section-heading"><h2>{{ tabs[admin.tab] }}</h2><button :disabled="admin.saving || admin.refreshing" @click="admin.open(admin.tab === 'groups' ? 'group' : admin.tab === 'categories' ? 'category' : 'sla')">新增</button></div>
      <p v-if="admin.loading" role="status">正在读取配置…</p>
      <div v-else-if="!admin.error" class="table-scroll">
        <table v-if="admin.tab === 'groups'"><thead><tr><th>名称</th><th>负责人</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="group in admin.groups" :key="group.id"><th>{{ group.name }}</th><td>{{ userName(group.leaderId) }}</td><td>{{ group.enabled ? '启用' : '停用' }}</td><td><button :disabled="admin.saving || admin.refreshing" @click="admin.open('group', group)">编辑</button></td></tr>
        </tbody></table>
        <table v-else-if="admin.tab === 'categories'"><thead><tr><th>代码</th><th>名称</th><th>客服组</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="category in admin.categories" :key="category.id"><th>{{ category.code }}</th><td>{{ category.name }}</td><td>{{ groupName(category.groupId) }}</td><td>{{ category.enabled ? '启用' : '停用' }}</td><td><button :disabled="admin.saving || admin.refreshing" @click="admin.open('category', category)">编辑</button></td></tr>
        </tbody></table>
        <table v-else><thead><tr><th>分类</th><th>优先级</th><th>响应时限</th><th>解决时限</th><th>自动升级</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="policy in admin.policies" :key="policy.id"><th>{{ categoryName(policy.categoryId) }}</th><td>{{ policy.priority }}</td><td>{{ policy.responseMinutes }}分钟</td><td>{{ policy.resolveMinutes }}分钟</td><td>{{ policy.autoEscalate ? '是' : '否' }}</td><td>{{ policy.enabled ? '启用' : '停用' }}</td><td><button :disabled="admin.saving || admin.refreshing" @click="admin.open('sla', policy)">编辑</button></td></tr>
        </tbody></table>
        <p v-if="!admin.groups.length && !admin.categories.length && !admin.policies.length">该页没有配置。</p>
      </div>
    </section>
    <footer v-if="admin.tab !== 'members'" class="pager" aria-label="管理列表分页">
      <span>第 {{ admin.offset / 20 + 1 }} 页</span><button :disabled="admin.loading || admin.offset === 0" @click="admin.offset -= 20">上一页</button><button :disabled="admin.loading || !admin.hasMore || admin.offset >= 100000" @click="admin.offset += 20">下一页</button>
    </footer>
  </template>
</template>

<style scoped>
.admin-tabs{display:flex;gap:6px;overflow:auto;border-bottom:1px solid var(--line);margin-bottom:20px}.admin-tabs button{white-space:nowrap;border:0;border-radius:0;background:transparent;padding:14px 20px;border-bottom:3px solid transparent}.admin-tabs button[aria-pressed=true]{border-bottom-color:var(--accent);color:var(--accent)}.lookup-status{display:flex;justify-content:flex-end;align-items:center;gap:12px;margin-bottom:12px}.admin-filter{display:flex;align-items:end;gap:14px;flex-wrap:wrap;margin-bottom:20px}.admin-filter label{min-width:160px;margin:0}.table-scroll{overflow-x:auto}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:12px;border-bottom:1px solid var(--line);font-size:13px}small{display:block;font-weight:400;margin-top:5px}td button{margin:3px}.pager{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-top:18px}.pager span{margin-right:auto}@media(max-width:700px){.admin-filter label{width:100%;min-width:0}.panel-padding{padding:18px}.admin-tabs button{padding:12px}.table-scroll table{min-width:520px}.lookup-status{flex-wrap:wrap}}
</style>
