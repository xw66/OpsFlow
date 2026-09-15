export type Role = 'USER' | 'AGENT' | 'LEADER' | 'ADMIN'
export interface User { id: number; username: string; displayName: string; enabled: boolean; roles: Role[] }
export interface AdminUserPage { items: User[]; hasMore: boolean; offset: number; limit: number }
export interface SupportCategory { id: number; code: string; name: string; groupId: number; enabled: boolean; version: number }
export interface SlaPolicy { id: number; categoryId: number; priority: Priority; responseMinutes: number; resolveMinutes: number; autoEscalate: boolean; enabled: boolean; version: number }
export type Priority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
export type Status = 'CREATED' | 'ASSIGNED' | 'PROCESSING' | 'PENDING' | 'RESOLVED' | 'CLOSED' | 'CANCELLED'
export interface TicketRow {
  id: number; title: string; userId: number; userName: string; categoryId: number; categoryName: string
  groupId: number; groupName: string; assigneeId: number | null; assigneeName: string | null
  priority: Priority; status: Status; version: number; createdAt: string; responseDeadline: string
  resolveDeadline: string; firstResponseAt: string | null; resolvedAt: string | null
  responseBreached: boolean; resolveBreached: boolean; escalationLevel: number
}
export interface Ticket extends Omit<TicketRow, 'userName' | 'categoryName' | 'groupName' | 'assigneeName'> {
  description: string; slaCycle: number; cycleStartedAt: string; closedAt: string | null; cancelledAt: string | null
}
export interface Detail { ticket: Ticket; tags: string[] }
export interface WorkspaceDetail { detail: Detail; display: TicketRow; staff: boolean; manager: boolean }
export interface AssignmentCandidate { userId: number; username: string; displayName: string; groupId: number; groupName: string; online: boolean; activeCount: number; lastAssignedAt: string | null }
export interface CandidatePage { items: AssignmentCandidate[]; hasMore: boolean; offset: number; limit: number; ticketVersion: number }
export interface TicketPage { items: TicketRow[]; hasMore: boolean; offset: number; limit: number }
export interface Category { id: number; name: string; code: string; groupId: number }
export interface SupportGroup { id: number; name: string; leaderId: number; enabled: boolean; version: number }
export interface SupportAgent { id: number; userId: number; groupId: number; online: boolean; enabled: boolean; lastAssignedAt: string | null; version: number }
export interface MemberLoad { userId: number; username: string; displayName: string; online: boolean; available: boolean; activeCount: number; processingCount: number }
export interface WorkloadPage { items: MemberLoad[]; hasMore: boolean; offset: number; limit: number }
export interface AvailablePriority { priority: Priority; responseMinutes: number; resolveMinutes: number }
export interface TicketInput { title: string; description: string; categoryId: number; priority: Priority; tags: string[] }
export interface Comment { id: number; authorId: number; authorName?: string; content: string; internal: boolean; createdAt: string }
export interface Notification { id: number; recipientId: number; eventId: string; ticketId: number; content: string; readAt: string | null; createdAt: string }
export interface StatisticsOverview { from: string; until: string; asOf: string; totals: { createdCount: number; responseSamples: number; averageResponseSeconds: number | null; resolvedSamples: number; averageResolveSeconds: number | null; slaAchieved: number; overdueCount: number }; slaAchievementRate: number | null; ai: { successfulCount: number; acceptedCount: number }; aiClassificationAcceptanceRate: number | null; categories: { categoryId: number; ticketCount: number }[]; agents: { groupId: number; assigneeId: number; processingCount: number }[]; groups: { groupId: number; activeCount: number; processingCount: number; unassignedCount: number }[] }
export interface DailyStatistic { businessDate: string; requested: boolean; refreshedAt: string | null; createdCount: number | null }
export interface TicketHistory { id: number; ticketId: number; fromStatus: Status | null; toStatus: Status; operatorId: number | null; operatorName: string | null; remark: string; ticketVersion: number; createdAt: string }
export interface TicketAssignmentRecord { id: number; ticketId: number; fromAssigneeId: number | null; toAssigneeId: number; fromGroupId: number; toGroupId: number; operatorId: number | null; reason: string; ticketVersion: number; createdAt: string; fromAssigneeName: string | null; toAssigneeName: string; fromGroupName: string; toGroupName: string; operatorName: string | null }
export interface Attachment { id: number; originalName: string; sizeBytes: number; uploaderId: number; createdAt: string }
