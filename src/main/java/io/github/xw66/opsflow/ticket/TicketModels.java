package io.github.xw66.opsflow.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class TicketModels {
    private TicketModels() { }

    public record Ticket(long id, long userId, long categoryId, long groupId, Long assigneeId,
            String title, String description, Priority priority, TicketStatus status, long version,
            long slaPolicyId, int responseMinutes, int resolveMinutes, boolean autoEscalate,
            Instant responseDeadline, Instant resolveDeadline, Instant firstResponseAt,
            Instant resolvedAt, Instant closedAt, Instant cancelledAt, Instant createdAt, Instant updatedAt,
            int slaCycle, Instant cycleStartedAt, boolean responseBreached, boolean resolveBreached, int escalationLevel) { }
    public record Detail(Ticket ticket, List<String> tags) { }
    public record TicketInput(@NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 10000) String description, @Positive long categoryId,
            @NotNull Priority priority, @NotNull @Size(max = 10) Set<@NotBlank @Size(max = 32) String> tags) { }
    public record EditInput(@NotNull @Valid TicketInput ticket, @PositiveOrZero long version) { }
    public record ActionInput(@PositiveOrZero long version, @NotBlank @Size(max = 500) String reason) { }
    public record AssignInput(@Positive Long assigneeId, @PositiveOrZero long version,
            @NotBlank @Size(max = 500) String reason) { }
    public record TransferInput(@Positive long assigneeId, @PositiveOrZero long version,
            @NotBlank @Size(max = 500) String reason) { }
    public record Assignment(long id, long ticketId, Long fromAssigneeId, long toAssigneeId,
            long fromGroupId, long toGroupId, Long operatorId, String reason, long ticketVersion, Instant createdAt,
            String fromAssigneeName, String toAssigneeName, String fromGroupName, String toGroupName, String operatorName) { }
    public record CommentInput(@NotBlank @Size(max = 5000) String content,
            @NotNull Boolean internal, @PositiveOrZero long version) { }
    public record Comment(long id, long ticketId, long authorId, String content, boolean internal, Instant createdAt, String authorName) { }
    public record History(long id, long ticketId, TicketStatus fromStatus, TicketStatus toStatus,
            Long operatorId, String remark, long ticketVersion, Instant createdAt, String operatorName) { }
    public record Attachment(long id, long ticketId, long uploaderId, String originalName,
            @com.fasterxml.jackson.annotation.JsonIgnore String storageKey, String contentType, long sizeBytes, Instant createdAt) { }
}
