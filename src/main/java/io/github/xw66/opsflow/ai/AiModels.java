package io.github.xw66.opsflow.ai;

import io.github.xw66.opsflow.ticket.Priority;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class AiModels {
    private AiModels() { }
    public enum Kind { CLASSIFICATION, SUMMARY, REPLY }
    public record Classification(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,31}") String category,
            @NotNull Priority priority, @NotNull @Size(max = 10) List<@NotBlank @Size(max = 32) String> tags,
            @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal confidence,
            @NotBlank @Size(max = 500) String reason) { }
    public record Summary(@NotBlank @Size(max = 1000) String summary) { }
    public record Reply(@NotBlank @Size(max = 3000) String suggestion) { }
    public record CallResult(String outcome, String resultJson, String model, Integer inputTokens,
            Integer outputTokens, long durationMs, int attempts, String errorCode) { }
}
