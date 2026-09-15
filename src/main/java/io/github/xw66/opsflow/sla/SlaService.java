package io.github.xw66.opsflow.sla;

import io.github.xw66.opsflow.common.AuditMapper;
import io.github.xw66.opsflow.event.OutboxService;
import io.github.xw66.opsflow.ticket.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SlaService {
    private final SlaMapper data;
    private final TicketMapper tickets;
    private final OutboxService outbox;
    private final AuditMapper audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final long warningSeconds;
    private final ApplicationEventPublisher changes;

    public SlaService(SlaMapper data, TicketMapper tickets, OutboxService outbox, AuditMapper audit,
            ObjectMapper json, Clock clock, ApplicationEventPublisher changes, @Value("${opsflow.sla.warning-seconds:300}") long warningSeconds) {
        if (warningSeconds < 0 || warningSeconds > 86400) throw new IllegalArgumentException("SLA预警窗口必须为0至86400秒");
        this.data = data; this.tickets = tickets; this.outbox = outbox; this.audit = audit;
        this.json = json; this.clock = clock; this.warningSeconds = warningSeconds;
        this.changes = changes;
    }

    public long warningSeconds() { return warningSeconds; }

    @Transactional
    public boolean inspect(long id, boolean response) { return inspect(id, response, false); }

    @Transactional
    public void recordCompleted(long id) {
        // 完成发生在两次扫描之间时，在完成操作的事务内补记违约，避免关闭或重开抹去超时事实。
        inspect(id, true, true);
        inspect(id, false, true);
    }

    private boolean inspect(long id, boolean response, boolean completedOnly) {
        var before = tickets.find(id);
        if (before == null || before.status() == TicketStatus.CANCELLED) return false;
        Instant deadline = response ? before.responseDeadline() : before.resolveDeadline();
        Instant completedAt = response ? before.firstResponseAt() : before.resolvedAt();
        Instant now = clock.instant();
        boolean active = Set.of(TicketStatus.CREATED, TicketStatus.ASSIGNED, TicketStatus.PROCESSING, TicketStatus.PENDING).contains(before.status());
        if (!completedOnly && !active) return false;
        if (completedOnly && completedAt == null) return false;
        if (completedAt == null && !active) return false;
        boolean breached = completedAt == null ? deadline.isBefore(now) : completedAt.isAfter(deadline);
        if (!breached && (completedAt != null || deadline.isAfter(now.plusSeconds(warningSeconds)))) return false;
        int cycle = response ? 1 : before.slaCycle();
        String type = (response ? "RESPONSE_" : "RESOLVE_") + (breached ? "BREACHED" : "WARNING");
        if (data.exists(id, cycle, type) != 0) return false;
        int escalation = before.autoEscalate() ? 1 : 0;
        // 版本条件胜出的事务才能写事件；唯一约束兜底，失败时标记、审计和Outbox一并回滚。
        if (data.mark(before, response && breached, !response && breached, escalation, now) != 1) return false;
        var payload = Map.of("groupId", before.groupId(), "slaCycle", cycle, "type", type,
                "deadline", deadline, "escalated", before.autoEscalate());
        String eventId = outbox.append(id, before.version() + 1,
                breached ? "TicketSlaBreachedEvent" : "TicketSlaWarningEvent", payload, now);
        data.insert(id, cycle, type, deadline, now, eventId);
        audit.insert(null, type, "TICKET", id, null, json.writeValueAsString(payload), "系统SLA检查");
        changes.publishEvent(new TicketChanged(id));
        return true;
    }
}
