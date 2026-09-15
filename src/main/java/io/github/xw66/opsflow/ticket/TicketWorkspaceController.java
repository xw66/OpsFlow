package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.common.ApiResponse;
import io.github.xw66.opsflow.common.BusinessException;
import io.github.xw66.opsflow.support.SupportMapper;
import io.github.xw66.opsflow.ticket.TicketWorkspace.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.*;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspace/tickets")
@SecurityRequirement(name = "bearerAuth")
public class TicketWorkspaceController {
    private final TicketWorkspaceMapper data;
    private final TicketService tickets;
    private final Clock clock;
    private final SupportMapper support;
    private final long warningSeconds;

    public TicketWorkspaceController(TicketWorkspaceMapper data, TicketService tickets, Clock clock, SupportMapper support,
            @Value("${opsflow.sla.warning-seconds:300}") long warningSeconds) {
        this.data = data; this.tickets = tickets; this.clock = clock; this.warningSeconds = warningSeconds;
        this.support = support;
    }

    @GetMapping
    public ApiResponse<Page> list(Authentication authentication,
            @RequestParam(defaultValue = "MINE_CREATED") View view,
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) Priority priority,
            @RequestParam(defaultValue = "") @Size(max = 200) String keyword,
            @RequestParam(defaultValue = "ALL") SlaFilter sla,
            @RequestParam(required = false) @Positive Long groupId,
            @RequestParam(defaultValue = "ALL") Queue queue,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        TicketActor actor = TicketActor.from(authentication);
        if ((view == View.MINE_ASSIGNED && !actor.agent())
                || (view == View.MY_GROUP && !actor.leader() && !actor.admin())
                || (queue != Queue.ALL && view != View.MY_GROUP)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前角色不能使用该工作视图");
        }
        var now = clock.instant();
        var rows = data.list(actor, view, status, categoryId, priority, keyword.strip(), sla, groupId, queue, now,
                now.plusSeconds(warningSeconds), offset, limit + 1);
        return ApiResponse.success(new Page(rows.stream().limit(limit).toList(), rows.size() > limit, offset, limit));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ApiResponse<Detail> detail(@PathVariable @Positive long id, Authentication authentication) {
        TicketActor actor = TicketActor.from(authentication);
        // 编辑与冲突恢复直接读取同一数据库快照，不能拿缓存旧版本反复提交。
        var detail = tickets.detail(id, actor);
        return ApiResponse.success(new Detail(detail, data.row(id), tickets.isStaff(detail.ticket(), actor), manager(detail.ticket(), actor)));
    }

    @GetMapping("/{id}/assignment-candidates")
    @Transactional(readOnly = true)
    public ApiResponse<CandidatePage> candidates(@PathVariable @Positive long id, Authentication authentication,
            @RequestParam(required = false) @Positive Long groupId,
            @RequestParam(defaultValue = "") @Size(max = 64) String keyword,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        TicketActor actor = TicketActor.from(authentication);
        var ticket = tickets.read(id, actor);
        if (!tickets.isStaff(ticket, actor) || (ticket.status() == TicketStatus.CREATED && !manager(ticket, actor))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权查看该工单的分配候选");
        }
        if (ticket.status() != TicketStatus.CREATED && ticket.status() != TicketStatus.ASSIGNED
                && ticket.status() != TicketStatus.PROCESSING && ticket.status() != TicketStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_WORKFLOW_ACTION", "当前工单不能分配或转派");
        }
        var rows = data.candidates(ticket, actor, groupId, keyword.strip(), offset, limit + 1);
        return ApiResponse.success(new CandidatePage(rows.stream().limit(limit).toList(), rows.size() > limit,
                offset, limit, ticket.version()));
    }

    private boolean manager(TicketModels.Ticket ticket, TicketActor actor) {
        return actor.admin() || (actor.leader() && support.group(ticket.groupId()).leaderId() == actor.id());
    }
}
