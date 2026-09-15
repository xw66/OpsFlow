package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.auth.Role;
import io.github.xw66.opsflow.common.BusinessException;
import io.github.xw66.opsflow.event.OutboxService;
import io.github.xw66.opsflow.sla.SlaService;
import io.github.xw66.opsflow.support.SupportMapper;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static io.github.xw66.opsflow.ticket.TicketService.checkVersion;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class WorkflowService {
    private final TicketMapper data;
    private final WorkflowMapper workflow;
    private final TicketService tickets;
    private final SupportMapper support;
    private final OutboxService events;
    private final Clock clock;
    private final SlaService sla;

    public WorkflowService(TicketMapper data, WorkflowMapper workflow, TicketService tickets, SupportMapper support,
            OutboxService events, Clock clock, SlaService sla) {
        this.data = data; this.workflow = workflow; this.tickets = tickets; this.support = support; this.events = events; this.clock = clock;
        this.sla = sla;
    }

    public Ticket assign(long id, AssignInput input, TicketActor actor) {
        Ticket before = tickets.read(id, actor);
        requireManager(before.groupId(), actor);
        requireVersion(before, input.version());
        lockGroups(before.groupId(), before.groupId());
        before = data.find(id);
        requireManager(before.groupId(), actor);
        requireVersion(before, input.version());
        before.status().requireTransitionTo(TicketStatus.ASSIGNED);
        requireActiveGroup(before.groupId());
        Long assignee = input.assigneeId() == null ? workflow.leastLoaded(before.groupId()) : input.assigneeId();
        if (assignee == null) throw new BusinessException(HttpStatus.CONFLICT, "NO_AVAILABLE_AGENT", "暂无可用客服，工单保留待人工分配");
        return assignTo(before, assignee, before.groupId(), actor.id(), input.reason());
    }

    public boolean autoAssign(long id) {
        Ticket before = data.find(id);
        if (before == null || before.status() != TicketStatus.CREATED || before.assigneeId() != null) return false;
        long groupId = before.groupId();
        lockGroups(groupId, groupId);
        before = data.find(id);
        if (before.status() != TicketStatus.CREATED || before.assigneeId() != null || before.groupId() != groupId
                || !support.group(groupId).enabled()) return false;
        Long assignee = workflow.leastLoaded(groupId);
        if (assignee == null) return false;
        assignTo(before, assignee, groupId, null, "按活动负载及最久未分配顺序自动分配");
        return true;
    }

    public Ticket transfer(long id, TransferInput input, TicketActor actor) {
        Ticket before = tickets.read(id, actor);
        requireVersion(before, input.version());
        if (!tickets.isStaff(before, actor)) forbidden();
        var target = support.agentForUser(input.assigneeId());
        if (target == null) throw new BusinessException(HttpStatus.CONFLICT, "INVALID_ASSIGNEE", "目标用户不是可分配客服");
        long sourceGroup = before.groupId(), targetGroup = target.groupId();
        lockGroups(sourceGroup, targetGroup);
        before = tickets.read(id, actor);
        requireVersion(before, input.version());
        if (before.groupId() != sourceGroup || support.agentForUser(input.assigneeId()).groupId() != targetGroup) conflict();
        if (!tickets.isStaff(before, actor)) forbidden();
        if (targetGroup != sourceGroup) {
            requireManager(sourceGroup, actor);
            requireManager(targetGroup, actor);
        }
        if (!Set.of(TicketStatus.ASSIGNED, TicketStatus.PROCESSING, TicketStatus.PENDING).contains(before.status())) conflict();
        if (Objects.equals(before.assigneeId(), input.assigneeId())) throw new BusinessException(HttpStatus.CONFLICT, "SAME_ASSIGNEE", "工单已由该客服负责");
        return assignTo(before, input.assigneeId(), targetGroup, actor.id(), input.reason());
    }

    public Ticket transition(long id, TicketStatus expected, TicketStatus target, ActionInput input, TicketActor actor) {
        Ticket before = tickets.read(id, actor);
        requireVersion(before, input.version());
        lockGroups(before.groupId(), before.groupId());
        before = tickets.read(id, actor);
        requireVersion(before, input.version());
        if (before.status() != expected) conflict();
        if (!tickets.isStaff(before, actor)) forbidden();
        if (before.status() == TicketStatus.ASSIGNED && target == TicketStatus.PROCESSING
                && (!actor.agent() || !Objects.equals(before.assigneeId(), actor.id()))) forbidden();
        if (target == TicketStatus.ASSIGNED || target == TicketStatus.CANCELLED) conflict();
        before.status().requireTransitionTo(target);
        Instant now = clock.instant();
        checkVersion(workflow.transition(before, target, now));
        sla.recordCompleted(id);
        Ticket after = data.find(id);
        // 挂起与解决说明作为公开处理反馈，计入首次响应；接单本身不计首次响应。
        if (target == TicketStatus.PENDING || target == TicketStatus.RESOLVED) {
            data.comment(id, actor.id(), input.reason(), false, now);
        }
        recordStatus(before, after, actor.id(), input.reason(), now);
        tickets.audit(actor.id(), "TICKET_STATUS_CHANGED", id, before, after, input.reason());
        return after;
    }

    public Ticket reopen(long id, ActionInput input, TicketActor actor) {
        Ticket before = tickets.read(id, actor);
        requireVersion(before, input.version());
        lockGroups(before.groupId(), before.groupId());
        before = tickets.read(id, actor);
        requireManager(before.groupId(), actor);
        requireVersion(before, input.version());
        before.status().requireReopen(actor.admin() ? Set.of(Role.ADMIN) : Set.of(Role.LEADER), input.reason());
        Instant now = clock.instant();
        checkVersion(workflow.reopen(before, now, now.plusSeconds(before.resolveMinutes() * 60L)));
        Ticket after = data.find(id);
        recordStatus(before, after, actor.id(), input.reason(), now);
        tickets.audit(actor.id(), "TICKET_REOPENED", id, before, after, input.reason());
        return after;
    }

    private Ticket assignTo(Ticket before, long assignee, long groupId, Long operator, String reason) {
        if (workflow.eligible(assignee, groupId) != 1) throw new BusinessException(HttpStatus.CONFLICT, "INVALID_ASSIGNEE", "客服不在线、已停用或不属于目标组");
        Instant now = clock.instant();
        TicketStatus status = before.status() == TicketStatus.CREATED ? TicketStatus.ASSIGNED : before.status();
        checkVersion(workflow.assign(before, assignee, groupId, status, now));
        workflow.assignedAt(assignee, now);
        Ticket after = data.find(before.id());
        workflow.assignment(before, assignee, groupId, operator, reason, after.version(), now);
        events.append(after.id(), after.version(), "TicketAssignedEvent",
                Map.of("assigneeId", assignee, "groupId", groupId, "reason", reason), now);
        if (before.status() != after.status()) recordStatus(before, after, operator, reason, now);
        tickets.audit(operator, "TICKET_ASSIGNED", after.id(), before, after, reason);
        return after;
    }

    private void recordStatus(Ticket before, Ticket after, Long actor, String reason, Instant now) {
        data.history(after.id(), before.status(), after.status(), actor, reason, after.version(), now);
        events.append(after.id(), after.version(), "TicketStatusChangedEvent",
                new StatusChange(before.status(), after.status(), actor, reason), now);
    }

    private void lockGroups(long first, long second) {
        // 所有分配按组编号顺序加数据库行锁；获得锁后读取最新负载，避免跨组转派反向加锁。
        support.lockGroup(Math.min(first, second));
        if (first != second) support.lockGroup(Math.max(first, second));
    }

    private void requireManager(long groupId, TicketActor actor) {
        if (!actor.admin() && !(actor.leader() && support.group(groupId).leaderId() == actor.id())) forbidden();
    }
    private void requireActiveGroup(long groupId) {
        if (!support.group(groupId).enabled()) throw new BusinessException(HttpStatus.CONFLICT, "GROUP_DISABLED", "客服组已停用");
    }
    private void requireVersion(Ticket ticket, long expected) { checkVersion(ticket.version() == expected ? 1 : 0); }
    private void forbidden() { throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权执行该工单操作"); }
    private void conflict() { throw new BusinessException(HttpStatus.CONFLICT, "INVALID_WORKFLOW_ACTION", "当前工单不能执行该操作"); }
    public record StatusChange(TicketStatus fromStatus, TicketStatus toStatus, Long operatorId, String remark) { }
}
