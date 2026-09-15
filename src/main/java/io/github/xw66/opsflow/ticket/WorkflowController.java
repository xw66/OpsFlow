package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.common.ApiResponse;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tickets")
@SecurityRequirement(name = "bearerAuth")
public class WorkflowController {
    private final WorkflowService service;
    private final WorkflowMapper workflow;
    private final TicketService tickets;

    public WorkflowController(WorkflowService service, WorkflowMapper workflow, TicketService tickets) {
        this.service = service; this.workflow = workflow; this.tickets = tickets;
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    public ApiResponse<Ticket> assign(@PathVariable @Positive long id, @Valid @RequestBody AssignInput input, Authentication actor) {
        return ApiResponse.success(service.assign(id, input, TicketActor.from(actor)));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<Ticket> transfer(@PathVariable @Positive long id, @Valid @RequestBody TransferInput input, Authentication actor) {
        return ApiResponse.success(service.transfer(id, input, TicketActor.from(actor)));
    }

    @PostMapping("/{id}/accept")
    public ApiResponse<Ticket> accept(@PathVariable @Positive long id, @Valid @RequestBody ActionInput input, Authentication actor) {
        return ApiResponse.success(service.transition(id, TicketStatus.ASSIGNED, TicketStatus.PROCESSING, input, TicketActor.from(actor)));
    }

    @PostMapping("/{id}/suspend")
    public ApiResponse<Ticket> suspend(@PathVariable @Positive long id, @Valid @RequestBody ActionInput input, Authentication actor) {
        return ApiResponse.success(service.transition(id, TicketStatus.PROCESSING, TicketStatus.PENDING, input, TicketActor.from(actor)));
    }

    @PostMapping("/{id}/resume")
    public ApiResponse<Ticket> resume(@PathVariable @Positive long id, @Valid @RequestBody ActionInput input, Authentication actor) {
        return ApiResponse.success(service.transition(id, TicketStatus.PENDING, TicketStatus.PROCESSING, input, TicketActor.from(actor)));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<Ticket> resolve(@PathVariable @Positive long id, @Valid @RequestBody ActionInput input, Authentication actor) {
        return ApiResponse.success(service.transition(id, TicketStatus.PROCESSING, TicketStatus.RESOLVED, input, TicketActor.from(actor)));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<Ticket> close(@PathVariable @Positive long id, @Valid @RequestBody ActionInput input, Authentication actor) {
        return ApiResponse.success(service.transition(id, TicketStatus.RESOLVED, TicketStatus.CLOSED, input, TicketActor.from(actor)));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    public ApiResponse<Ticket> reopen(@PathVariable @Positive long id, @Valid @RequestBody ActionInput input, Authentication actor) {
        return ApiResponse.success(service.reopen(id, input, TicketActor.from(actor)));
    }

    @GetMapping("/manual-queue")
    @PreAuthorize("hasAnyRole('LEADER','ADMIN')")
    public ApiResponse<List<Ticket>> manualQueue(Authentication actor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(workflow.manualQueue(TicketActor.from(actor), offset, limit));
    }

    @GetMapping("/{id}/assignments")
    public ApiResponse<List<Assignment>> assignments(@PathVariable @Positive long id, Authentication actor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        tickets.read(id, TicketActor.from(actor));
        return ApiResponse.success(workflow.assignments(id, offset, limit));
    }
}
