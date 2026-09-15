const checks = new Set<() => boolean>()

export function registerUnsaved(check: () => boolean) {
  checks.add(check)
  return () => { checks.delete(check) }
}

export function confirmDiscard() {
  return !Array.from(checks).some(check => check()) || window.confirm('有尚未保存的内容，确定离开并丢弃吗？')
}
