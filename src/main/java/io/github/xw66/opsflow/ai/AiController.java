package io.github.xw66.opsflow.ai;

import io.github.xw66.opsflow.ai.AiModels.Kind;
import io.github.xw66.opsflow.common.ApiResponse;
import io.github.xw66.opsflow.ticket.TicketActor;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tickets/{ticketId}/ai-analyses")
@SecurityRequirement(name = "bearerAuth")
public class AiController {
    private final AiAnalysisService service;
    public AiController(AiAnalysisService service) { this.service = service; }
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AiMapper.Analysis> request(@PathVariable @Positive long ticketId, @Valid @RequestBody Request input, Authentication actor) {
        return ApiResponse.success(service.request(ticketId, input.kind(), input.version(), TicketActor.from(actor)));
    }
    @GetMapping
    public ApiResponse<List<AiMapper.Analysis>> list(@PathVariable @Positive long ticketId, Authentication actor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(service.list(ticketId, TicketActor.from(actor), offset, limit));
    }
    @PostMapping("/{id}/accept")
    public ApiResponse<AiMapper.Analysis> accept(@PathVariable @Positive long ticketId, @PathVariable @Positive long id,
            @Valid @RequestBody Confirm input, Authentication actor) {
        return ApiResponse.success(service.accept(ticketId, id, input.version(), TicketActor.from(actor)));
    }
    @GetMapping("/{id}/calls")
    public ApiResponse<List<AiMapper.CallLog>> calls(@PathVariable @Positive long ticketId, @PathVariable @Positive long id, Authentication actor) {
        return ApiResponse.success(service.logs(ticketId, id, TicketActor.from(actor)));
    }
    public record Request(@NotNull Kind kind, @PositiveOrZero long version) { }
    public record Confirm(@PositiveOrZero long version) { }
}
