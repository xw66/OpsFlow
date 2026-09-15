package io.github.xw66.opsflow.sla;

import io.github.xw66.opsflow.common.ApiResponse;
import io.github.xw66.opsflow.ticket.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class SlaController {
    private final SlaMapper data;
    private final TicketService tickets;
    public SlaController(SlaMapper data, TicketService tickets) { this.data = data; this.tickets = tickets; }

    @GetMapping("/api/tickets/escalations")
    @PreAuthorize("hasAnyRole('ADMIN','LEADER')")
    public ApiResponse<List<TicketModels.Ticket>> escalations(Authentication actor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(data.escalations(TicketActor.from(actor), offset, limit));
    }

    @GetMapping("/api/tickets/{id}/sla-events")
    public ApiResponse<List<SlaMapper.SlaEvent>> events(@PathVariable @Positive long id, Authentication actor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        tickets.read(id, TicketActor.from(actor));
        return ApiResponse.success(data.events(id, offset, limit));
    }
}
