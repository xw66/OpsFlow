package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.common.AuditMapper;
import io.github.xw66.opsflow.common.BusinessException;
import io.github.xw66.opsflow.event.OutboxService;
import io.github.xw66.opsflow.sla.SlaService;
import io.github.xw66.opsflow.statistics.StatisticsMapper;
import io.github.xw66.opsflow.statistics.StatisticsService;
import io.github.xw66.opsflow.support.SupportMapper;
import io.github.xw66.opsflow.support.SupportModels.Policy;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class TicketService {
    private final TicketMapper data;
    private final SupportMapper support;
    private final AuditMapper audit;
    private final OutboxService outbox;
    private final ObjectMapper json;
    private final Clock clock;
    private final SlaService sla;
    private final ApplicationEventPublisher changes;
    private final StatisticsMapper statistics;

    public TicketService(TicketMapper data, SupportMapper support, AuditMapper audit, OutboxService outbox,
            ObjectMapper json, Clock clock, SlaService sla, ApplicationEventPublisher changes, StatisticsMapper statistics) {
        this.data = data; this.support = support; this.audit = audit; this.outbox = outbox; this.json = json; this.clock = clock;
        this.sla = sla;
        this.changes = changes;
        this.statistics = statistics;
    }

    @Transactional
    public Detail create(TicketInput input, TicketActor actor) {
        return create(input, actor, null, null);
    }

    @Transactional
    public Detail create(TicketInput input, TicketActor actor, String requestKey, String requestHash) {
        Policy policy = activePolicy(input.categoryId(), input.priority());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long groupId = support.category(input.categoryId()).groupId();
        data.insert(actor.id(), input, groupId, policy, now.plusSeconds(policy.responseMinutes() * 60L),
                now.plusSeconds(policy.resolveMinutes() * 60L), now, requestKey, requestHash);
        long id = data.insertedId();
        saveTags(id, input.tags(), false);
        data.history(id, null, TicketStatus.CREATED, actor.id(), "创建工单", 0, now);
        Ticket ticket = data.find(id);
        audit(actor.id(), "TICKET_CREATED", ticket.id(), null, ticket, "创建工单");
        outbox.append(id, 0, "TicketCreatedEvent", ticket, now);
        Detail result = new Detail(ticket, data.tags(id));
        if (requestKey != null) data.creationResponse(id, json.writeValueAsString(result));
        return result;
    }

    @Transactional(readOnly = true)
    public Detail detail(long id, TicketActor actor) {
        return new Detail(read(id, actor), data.tags(id));
    }

    public List<Ticket> list(TicketActor actor, TicketStatus status, Long categoryId, int offset, int limit) {
        return data.list(actor, status, categoryId, offset, limit);
    }

    public Ticket read(long id, TicketActor actor) {
        Ticket ticket = data.find(id);
        if (ticket == null) throw new BusinessException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "工单不存在");
        if (ticket.userId() != actor.id() && !isStaff(ticket, actor)) forbidden();
        return ticket;
    }

    public boolean isStaff(Ticket ticket, TicketActor actor) {
        return actor.admin() || (actor.agent() && Objects.equals(ticket.assigneeId(), actor.id()))
                || (actor.leader() && support.group(ticket.groupId()).leaderId() == actor.id());
    }

    @Transactional
    public Detail edit(long id, EditInput edit, TicketActor actor) {
        return editInternal(id, edit, actor, false);
    }

    @Transactional
    public Detail applyClassification(long id, EditInput edit, TicketActor actor) {
        return editInternal(id, edit, actor, true);
    }

    private Detail editInternal(long id, EditInput edit, TicketActor actor, boolean allowStaff) {
        Ticket before = read(id, actor);
        if (!allowStaff || !isStaff(before, actor)) requireOwner(before, actor);
        checkVersion(before.version() == edit.version() ? 1 : 0);
        List<String> beforeTags = data.tags(id);
        if (before.status() != TicketStatus.CREATED) throw new BusinessException(HttpStatus.CONFLICT, "TICKET_NOT_EDITABLE", "仅新建工单可以修改");
        TicketInput input = edit.ticket();
        boolean changed = input.categoryId() != before.categoryId() || input.priority() != before.priority();
        Policy policy = changed ? activePolicy(input.categoryId(), input.priority())
                : new Policy(before.slaPolicyId(), before.categoryId(), before.priority(), before.responseMinutes(),
                        before.resolveMinutes(), before.autoEscalate(), true, 0);
        long groupId = changed ? support.category(input.categoryId()).groupId() : before.groupId();
        // 从原始创建时间计算，修改标题或分类不能延长已经消耗的 SLA 时间。
        checkVersion(data.edit(id, edit.version(), input, groupId, policy,
                before.createdAt().plusSeconds(policy.responseMinutes() * 60L),
                before.createdAt().plusSeconds(policy.resolveMinutes() * 60L), clock.instant()));
        saveTags(id, input.tags(), true);
        Ticket after = data.find(id);
        audit(actor.id(), "TICKET_EDITED", id, new Detail(before, beforeTags), new Detail(after, data.tags(id)), "修改工单");
        // 修改分类可能改变历史工单归属；同事务标记创建日报，不能只依赖今天/昨天的定时重算。
        if (before.groupId() != after.groupId()) statistics.requestDay(before.createdAt().atZone(StatisticsService.ZONE).toLocalDate());
        return new Detail(after, data.tags(id));
    }

    @Transactional
    public Ticket cancel(long id, ActionInput input, TicketActor actor) {
        Ticket before = read(id, actor);
        requireOwner(before, actor);
        checkVersion(before.version() == input.version() ? 1 : 0);
        before.status().requireTransitionTo(TicketStatus.CANCELLED);
        Instant now = clock.instant();
        checkVersion(data.cancel(id, input.version(), now));
        Ticket after = data.find(id);
        data.history(id, before.status(), after.status(), actor.id(), input.reason(), after.version(), now);
        audit(actor.id(), "TICKET_CANCELLED", id, before, after, input.reason());
        outbox.append(id, after.version(), "TicketStatusChangedEvent",
                Map.of("fromStatus", before.status(), "toStatus", after.status(), "operatorId", actor.id(), "remark", input.reason()), now);
        return after;
    }

    @Transactional
    public Comment comment(long id, CommentInput input, TicketActor actor) {
        Ticket before = read(id, actor);
        boolean staff = isStaff(before, actor);
        if (input.internal() && !staff) forbidden();
        Instant now = clock.instant();
        touch(before, input.version(), now, staff && !input.internal() ? now : null);
        data.comment(id, actor.id(), input.content(), input.internal(), now);
        Comment result = data.commentById(data.insertedId());
        sla.recordCompleted(id);
        audit(actor.id(), "TICKET_COMMENTED", id, null, Map.of("commentId", result.id(), "internal", result.internal()), "追加工单交流");
        return result;
    }

    public List<Comment> comments(long id, TicketActor actor, int offset, int limit) {
        Ticket ticket = read(id, actor);
        return data.comments(id, isStaff(ticket, actor), offset, limit);
    }

    public List<History> history(long id, TicketActor actor, int offset, int limit) {
        read(id, actor);
        return data.histories(id, offset, limit);
    }

    public void touch(Ticket ticket, long version, Instant now, Instant responseAt) {
        // 请求版本必须等于本次读取版本，不能猜测未来版本后等待另一事务提交再覆盖。
        checkVersion(ticket.version() == version ? 1 : 0);
        checkVersion(data.touch(ticket.id(), version, now, responseAt));
    }

    public void audit(Long actor, String action, long ticketId, Object before, Object after, String reason) {
        audit.insert(actor, action, "TICKET", ticketId, before == null ? null : json.writeValueAsString(before),
                after == null ? null : json.writeValueAsString(after), reason);
        changes.publishEvent(new TicketChanged(ticketId));
    }

    private Policy activePolicy(long categoryId, Priority priority) {
        var category = support.category(categoryId);
        if (category == null || !category.enabled() || !support.group(category.groupId()).enabled()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CATEGORY", "分类不存在或当前不可用");
        }
        Policy policy = data.activePolicy(categoryId, priority);
        if (policy == null) throw new BusinessException(HttpStatus.CONFLICT, "SLA_POLICY_MISSING", "该分类和优先级尚未配置有效 SLA 规则");
        return policy;
    }

    private void saveTags(long id, Set<String> tags, boolean replace) {
        Set<String> normalized = new TreeSet<>();
        for (String tag : tags) {
            String value = tag.strip().toLowerCase(Locale.ROOT);
            if (value.isBlank() || value.length() > 32) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TAG", "标签长度不合法");
            normalized.add(value);
        }
        // 先按相同顺序锁定共享标签，再改关联；新工单不删除空关联，避免间隙锁与标签行锁构成死锁。
        for (String tag : normalized) data.tag(tag);
        if (replace) data.unlinkTags(id);
        for (String tag : normalized) data.linkTag(id, tag);
    }

    private void requireOwner(Ticket ticket, TicketActor actor) {
        if (ticket.userId() != actor.id() && !actor.admin()) forbidden();
    }

    private void forbidden() { throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权操作该工单"); }

    public static void checkVersion(int rows) {
        if (rows != 1) throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "工单已变化或不可修改，请刷新后重试");
    }
}
