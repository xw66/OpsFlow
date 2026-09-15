import { shallowRef, watch } from 'vue'
import { errorText, request, session } from '../../api/http'
import type { Category } from '../../api/types'

export function useCategories() {
  const categories = shallowRef<Category[]>([]), categoryError = shallowRef(''), reloadCategories = shallowRef(0)
  watch([session.token, reloadCategories], async ([token], _, cleanup) => {
    if (!token) return
    const controller = new AbortController(); cleanup(() => controller.abort())
    categoryError.value = ''
    try {
      const all: Category[] = []
      for (let offset = 0; offset <= 100000; offset += 100) {
        const batch = await request<Category[]>(`/api/support/categories?offset=${offset}&limit=100`, { signal: controller.signal })
        all.push(...batch); if (batch.length < 100) break
      }
      if (!controller.signal.aborted) categories.value = all
    } catch (e) { if (!controller.signal.aborted) categoryError.value = `分类加载失败：${errorText(e)}` }
  }, { immediate: true })
  return { categories, categoryError, reloadCategories }
}
