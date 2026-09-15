import type { Priority, Status, TicketRow } from '../../api/types'
export const statusNames: Record<Status, string> = { CREATED: '已创建', ASSIGNED: '待接单', PROCESSING: '处理中', PENDING: '等待用户补充', RESOLVED: '已解决', CLOSED: '已关闭', CANCELLED: '已取消' }
export const statusText = (status: Status, mine = false) => status === 'PENDING' && mine ? '待我补充' : statusNames[status]
export const priorityNames: Record<Priority, string> = { LOW: '低', NORMAL: '普通', HIGH: '高', URGENT: '紧急' }
export const dateTime = (value: string | null) => value ? new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)) : '—'
export function slaInfo(ticket: Pick<TicketRow, 'status' | 'firstResponseAt' | 'responseDeadline' | 'resolveDeadline'>, now: number) {
  if (['RESOLVED', 'CLOSED', 'CANCELLED'].includes(ticket.status)) return { label: statusNames[ticket.status], tone: 'done', deadline: null }
  const response = !ticket.firstResponseAt && Date.parse(ticket.responseDeadline) <= Date.parse(ticket.resolveDeadline)
  const deadline = response ? ticket.responseDeadline : ticket.resolveDeadline
  const minutes = Math.ceil((Date.parse(deadline) - now) / 60000)
  return { label: `${response ? '响应' : '解决'}${minutes <= 0 ? '已到期' : `剩余 ${minutes < 60 ? `${minutes} 分钟` : `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分钟`}`}`,
    tone: minutes <= 0 ? 'danger' : minutes <= 5 ? 'warning' : '', deadline }
}
