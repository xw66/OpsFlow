import { onMounted, onUnmounted } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { registerUnsaved } from '../../unsaved'
export function useLeaveGuard(dirty: () => boolean) {
  const unregister = registerUnsaved(dirty)
  const confirm = () => !dirty() || window.confirm('有尚未保存的内容，确定离开并丢弃吗？')
  onBeforeRouteLeave(confirm)
  onBeforeRouteUpdate((to, from) => to.path === from.path || confirm())
  const beforeUnload = (event: BeforeUnloadEvent) => { if (dirty()) { event.preventDefault(); event.returnValue = '' } }
  onMounted(() => window.addEventListener('beforeunload', beforeUnload))
  onUnmounted(() => { unregister(); window.removeEventListener('beforeunload', beforeUnload) })
}
