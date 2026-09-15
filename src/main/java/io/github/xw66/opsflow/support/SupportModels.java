package io.github.xw66.opsflow.support;

import io.github.xw66.opsflow.ticket.Priority;
import jakarta.validation.constraints.*;
import java.time.Instant;

public final class SupportModels {
    private SupportModels() { }

    public record Group(long id, String name, long leaderId, boolean enabled, long version) { }
    public record Agent(long id, long userId, long groupId, boolean online, boolean enabled, Instant lastAssignedAt, long version) { }
    public record MemberLoad(long userId, String username, String displayName, boolean online,
            boolean available, long activeCount, long processingCount) { }
    public record WorkloadPage(java.util.List<MemberLoad> items, boolean hasMore, int offset, int limit) { }
    public record Category(long id, String code, String name, long groupId, boolean enabled, long version) { }
    public record Policy(long id, long categoryId, Priority priority, int responseMinutes, int resolveMinutes,
            boolean autoEscalate, boolean enabled, long version) { }
    public record AvailablePriority(Priority priority, int responseMinutes, int resolveMinutes) { }

    public record GroupInput(@NotBlank @Size(max = 64) String name, @Positive long leaderId,
            @NotNull Boolean enabled, @PositiveOrZero long version) { }
    public record AgentInput(@Positive long userId, @Positive long groupId,
            @NotNull Boolean enabled, @PositiveOrZero long version) { }
    public record CategoryInput(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,31}") String code,
            @NotBlank @Size(max = 64) String name, @Positive long groupId,
            @NotNull Boolean enabled, @PositiveOrZero long version) { }
    public record PolicyInput(@Positive long categoryId, @NotNull Priority priority,
            @Min(1) @Max(525600) int responseMinutes, @Min(1) @Max(525600) int resolveMinutes,
            @NotNull Boolean autoEscalate, @NotNull Boolean enabled, @PositiveOrZero long version) { }
    public record OnlineInput(@NotNull Boolean online, @PositiveOrZero long version) { }
}
