package io.github.xw66.opsflow.event;

import io.github.xw66.opsflow.ticket.TicketMapper;
import io.github.xw66.opsflow.common.ContentHash;
import io.github.xw66.opsflow.statistics.StatisticsMapper;
import io.github.xw66.opsflow.statistics.StatisticsService;
import io.github.xw66.opsflow.support.SupportMapper;
import jakarta.validation.Validator;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class EventProcessor {
    public static final String CONSUMER = "opsflow-notifications";
    private final MessageMapper messages;
    private final TicketMapper tickets;
    private final SupportMapper support;
    private final ObjectMapper json;
    private final Validator validator;
    private final Clock clock;
    private final StatisticsMapper statistics;

    public EventProcessor(MessageMapper messages, TicketMapper tickets, SupportMapper support, ObjectMapper json, Validator validator, Clock clock, StatisticsMapper statistics) {
        this.messages = messages; this.tickets = tickets; this.support = support; this.json = json; this.validator = validator; this.clock = clock;
        this.statistics = statistics;
    }

    public EventEnvelope parse(String raw) {
        if (raw == null || raw.length() > 131072) throw new IllegalArgumentException("消息为空或过长");
        EventEnvelope event;
        try { event = json.readValue(raw, EventEnvelope.class); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("消息JSON不合法", ex); }
        if (event == null || !validator.validate(event).isEmpty() || !event.data().isObject()) throw new IllegalArgumentException("消息结构校验失败");
        return event;
    }

    @Transactional
    public void process(String raw) {
        EventEnvelope event = parse(raw);
        String hash = ContentHash.sha256(raw);
        try { messages.consumed(CONSUMER, event.eventId(), hash, clock.instant()); }
        catch (DuplicateKeyException ex) {
            if (!hash.equals(messages.hash(CONSUMER, event.eventId()))) throw new IllegalArgumentException("相同事件ID对应不同内容");
            messages.resolved(CONSUMER, event.eventId(), clock.instant());
            return;
        }
        // 去重记录和所有通知同事务提交，任一写入失败均允许消息完整重试。
        var ticket = tickets.find(event.ticketId());
        if (ticket == null) throw new IllegalStateException("事件关联工单不存在");
        statistics.requestDay(ticket.createdAt().atZone(StatisticsService.ZONE).toLocalDate());
        if (!event.eventType().equals("TicketAiAnalysisRequestedEvent")) {
            Set<Long> recipients = new HashSet<>();
            recipients.add(ticket.userId());
            if (event.eventType().equals("TicketAssignedEvent")) {
                var assignee = event.data().path("assigneeId");
                if (!assignee.isIntegralNumber() || !assignee.canConvertToLong() || assignee.longValue() <= 0) throw new IllegalArgumentException("分配事件缺少责任人");
                recipients.add(assignee.longValue());
            }
            if (event.eventType().startsWith("TicketSla") && event.data().path("escalated").asBoolean(false)) {
                long groupId = event.data().path("groupId").asLong(ticket.groupId());
                var group = support.group(groupId);
                if (group == null) throw new IllegalArgumentException("SLA事件客服组不存在");
                recipients.add(group.leaderId());
            }
            String description = switch (event.eventType()) {
                case "TicketCreatedEvent" -> "已创建";
                case "TicketAssignedEvent" -> "已分配客服";
                case "TicketSlaWarningEvent" -> "即将超过 SLA 时限";
                case "TicketSlaBreachedEvent" -> "已超过 SLA 时限";
                default -> "处理状态已更新";
            };
            for (long recipient : recipients) messages.notify(recipient, event.eventId(), event.ticketId(),
                    "工单 #" + event.ticketId() + "：" + description, clock.instant());
        }
        messages.resolved(CONSUMER, event.eventId(), clock.instant());
    }

}
