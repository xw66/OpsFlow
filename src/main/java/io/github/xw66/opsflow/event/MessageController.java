package io.github.xw66.opsflow.event;

import io.github.xw66.opsflow.common.ApiResponse;
import io.github.xw66.opsflow.common.BusinessException;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class MessageController {
    private final MessageMapper data;
    private final ConsumerFailureService failures;
    private final Clock clock;
    public MessageController(MessageMapper data, ConsumerFailureService failures, Clock clock) { this.data = data; this.failures = failures; this.clock = clock; }

    @GetMapping("/api/notifications")
    public ApiResponse<List<MessageMapper.Notification>> notifications(Authentication actor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(data.notifications(Long.parseLong(actor.getName()), offset, limit));
    }

    @GetMapping("/api/notifications/unread-count")
    public ApiResponse<Integer> unread(Authentication actor) {
        return ApiResponse.success(data.unread(Long.parseLong(actor.getName())));
    }

    @PutMapping("/api/notifications/{id}/read")
    public ApiResponse<Void> read(@PathVariable @Positive long id, Authentication actor) {
        if (data.markRead(id, Long.parseLong(actor.getName()), clock.instant()) != 1) throw new BusinessException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "通知不存在");
        return ApiResponse.success(null);
    }

    @GetMapping("/api/admin/consumer-failures")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<MessageMapper.Failure>> failures(
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(data.failures(offset, limit));
    }

    @PostMapping("/api/admin/consumer-failures/{id}/replay")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> replay(@PathVariable @Positive long id, @Valid @RequestBody ReplayInput input, Authentication actor) {
        failures.replay(id, Long.parseLong(actor.getName()), input.reason());
        return ApiResponse.success(null);
    }
    public record ReplayInput(@NotBlank @Size(max = 500) String reason) { }
}
