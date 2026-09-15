package io.github.xw66.opsflow.ticket;

import java.time.Instant;
import java.util.List;

public final class TicketWorkspace {
    private TicketWorkspace() { }
    public enum View { MINE_CREATED, MINE_ASSIGNED, MY_GROUP, ACCESSIBLE }
    public enum SlaFilter { ALL, WARNING, BREACHED }
    public enum Queue { ALL, MANUAL, ESCALATED }
    public record Row(long id, String title, long userId, String userName, long categoryId,
            String categoryName, long groupId, String groupName, Long assigneeId, String assigneeName,
            Priority priority, TicketStatus status, long version, Instant createdAt, Instant responseDeadline,
            Instant resolveDeadline, Instant firstResponseAt, Instant resolvedAt, boolean responseBreached,
            boolean resolveBreached, int escalationLevel) { }
    public record Page(List<Row> items, boolean hasMore, int offset, int limit) { }
    public record Detail(TicketModels.Detail detail, Row display, boolean staff, boolean manager) { }
    public record Candidate(long userId, String username, String displayName, long groupId, String groupName,
            boolean online, long activeCount, Instant lastAssignedAt) { }
    public record CandidatePage(List<Candidate> items, boolean hasMore, int offset, int limit, long ticketVersion) { }
}
