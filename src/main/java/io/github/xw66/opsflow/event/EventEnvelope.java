package io.github.xw66.opsflow.event;

import jakarta.validation.constraints.*;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

public record EventEnvelope(
        @NotNull @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String eventId,
        @Positive long ticketId, @PositiveOrZero long ticketVersion,
        @NotNull @Pattern(regexp = "Ticket(Created|Assigned|StatusChanged|SlaWarning|SlaBreached|AiAnalysisRequested)Event") String eventType,
        @Min(1) @Max(1) int schemaVersion, @NotNull Instant occurredAt, @NotNull JsonNode data) { }
