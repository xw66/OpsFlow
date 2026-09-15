import { computed, shallowRef, watch } from 'vue'
import { errorText, request, session } from '../../api/http'
import type { SupportGroup } from '../../api/types'

export function useTeamGroups() {
  const allowed = computed(() => Boolean(session.token.value && session.user.value?.roles.some(role => role === 'LEADER' || role === 'ADMIN')))
  const groups = shallowRef<SupportGroup[]>([]), loading = shallowRef(false), error = shallowRef(''), reload = shallowRef(0)
  watch([session.token, allowed, reload], async ([token, permitted], _, cleanup) => {
    groups.value = []; error.value = ''; loading.value = false
    if (!token || !permitted) return
    const controller = new AbortController(); cleanup(() => controller.abort())
    loading.value = true
    try {
      const all: SupportGroup[] = []
      for (let offset = 0; offset <= 100000; offset += 100) {
        const batch = await request<SupportGroup[]>(`/api/support/groups?offset=${offset}&limit=100`, { signal: controller.signal })
        if (controller.signal.aborted) return
        all.push(...batch)
        if (batch.length < 100) { groups.value = all; return }
      }
      throw new Error('客服组数量超过当前页面上限，请联系管理员调整管理范围。')
    } catch (e) { if (!controller.signal.aborted) error.value = errorText(e) }
    finally { if (!controller.signal.aborted) loading.value = false }
  }, { immediate: true })
  return { allowed, groups, loading, error, reload }
}
