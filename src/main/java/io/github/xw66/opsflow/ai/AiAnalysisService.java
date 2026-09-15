package io.github.xw66.opsflow.ai;

import io.github.xw66.opsflow.ai.AiModels.*;
import io.github.xw66.opsflow.common.*;
import io.github.xw66.opsflow.event.*;
import io.github.xw66.opsflow.ticket.*;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class AiAnalysisService {
    private final AiMapper data;
    private final TicketService tickets;
    private final TicketMapper ticketData;
    private final OutboxService events;
    private final EventProcessor parser;
    private final AiOutputValidator outputs;
    private final ObjectMapper json;
    private final Clock clock;
    public AiAnalysisService(AiMapper data, TicketService tickets, TicketMapper ticketData, OutboxService events,
            EventProcessor parser, AiOutputValidator outputs, ObjectMapper json, Clock clock) {
        this.data = data; this.tickets = tickets; this.ticketData = ticketData; this.events = events;
        this.parser = parser; this.outputs = outputs; this.json = json; this.clock = clock;
    }

    public AiMapper.Analysis request(long id, Kind kind, long version, TicketActor actor) {
        Ticket ticket = tickets.read(id, actor);
        requireKindAccess(ticket, kind, actor);
        TicketService.checkVersion(ticket.version() == version ? 1 : 0);
        if (Set.of(TicketStatus.CLOSED, TicketStatus.CANCELLED).contains(ticket.status())) conflict("已结束工单不能请求分析");
        var existing = data.snapshot(id, version, kind);
        if (existing != null && data.retry(existing.id()) != 1) return data.find(existing.id());
        long analysis = existing == null ? insert(ticket, kind, "WAITING", null, null, actor.id()) : existing.id();
        existing = data.find(analysis);
        if (data.eventId(analysis) != null) return existing;
        String eventId = events.append(id, version, "TicketAiAnalysisRequestedEvent", Map.of("analysisId", analysis), clock.instant());
        data.source(analysis, eventId);
        tickets.audit(actor.id(), "AI_ANALYSIS_REQUESTED", id, null, Map.of("analysisId", analysis, "kind", kind), "请求AI建议");
        return data.find(analysis);
    }

    public void enqueue(String raw) {
        var event = parser.parse(raw);
        String hash = ContentHash.sha256(raw);
        var known = data.sourceByEvent(event.eventId());
        if (known != null) {
            if (known.sourceEventHash() == null && !event.eventType().equals("TicketAiAnalysisRequestedEvent")) throw new IllegalArgumentException("AI请求事件类型不一致");
            if (known.ticketId() != event.ticketId() || known.ticketVersion() != event.ticketVersion()
                    || (known.sourceEventHash() != null && !hash.equals(known.sourceEventHash()))) throw new IllegalArgumentException("AI事件内容与记录不一致");
            if (event.eventType().equals("TicketAiAnalysisRequestedEvent")) {
                if (event.data().path("analysisId").asLong(-1) != known.id()) throw new IllegalArgumentException("AI分析编号不一致");
                data.ready(known.id(), hash);
            }
            return;
        }
        if (event.eventType().equals("TicketCreatedEvent")) {
            Ticket current = ticketData.find(event.ticketId());
            if (current == null) throw new IllegalStateException("工单不存在");
            // 使用创建事件的内容快照，不能把当前内容伪装为旧版本；旧建议在采纳时会被版本检查拒绝。
            Ticket snapshot = json.treeToValue(event.data(), Ticket.class);
            if (snapshot.id() != event.ticketId() || snapshot.version() != event.ticketVersion()) throw new IllegalArgumentException("创建快照不匹配");
            insert(snapshot, Kind.CLASSIFICATION, "PENDING", event.eventId(), hash, null);
        } else if (event.eventType().equals("TicketAiAnalysisRequestedEvent")) {
            var replaced = data.find(event.data().path("analysisId").asLong(-1));
            if (replaced != null && replaced.ticketId() == event.ticketId() && replaced.ticketVersion() == event.ticketVersion()) return;
            throw new IllegalArgumentException("AI分析请求没有对应数据库记录");
        }
    }

    private long insert(Ticket ticket, Kind kind, String status, String eventId, String hash, Long actor) {
        String input = "标题：" + ticket.title() + "\n描述：" + ticket.description();
        if (kind != Kind.CLASSIFICATION) {
            List<String> selected = new ArrayList<>();
            int remaining = 29000 - input.length();
            for (String comment : data.recentComments(ticket.id())) {
                if (remaining <= 0) break;
                String clipped = comment.substring(0, Math.min(comment.length(), Math.min(2000, remaining)));
                selected.add(clipped); remaining -= clipped.length() + 1;
            }
            Collections.reverse(selected);
            input += "\n最近公开交流（最多50条，超长已截断）：\n" + String.join("\n", selected);
        }
        try {
            data.insert(ticket.id(), ticket.version(), kind, status, eventId, hash, input,
                    json.writeValueAsString(data.categories()), actor, clock.instant());
            return data.insertedId();
        } catch (DuplicateKeyException ex) {
            var existing = data.snapshot(ticket.id(), ticket.version(), kind);
            if (existing == null) throw ex;
            return existing.id();
        }
    }

    public List<AiMapper.Analysis> list(long id, TicketActor actor, int offset, int limit) {
        Ticket ticket = tickets.read(id, actor);
        return data.list(id, tickets.isStaff(ticket, actor), offset, limit);
    }

    public AiMapper.Analysis accept(long ticketId, long analysisId, long version, TicketActor actor) {
        Ticket ticket = tickets.read(ticketId, actor);
        var analysis = data.lock(analysisId);
        if (analysis == null || analysis.ticketId() != ticketId) throw new BusinessException(HttpStatus.NOT_FOUND, "AI_ANALYSIS_NOT_FOUND", "分析不存在");
        requireKindAccess(ticket, analysis.kind(), actor);
        TicketService.checkVersion(ticket.version() == version && analysis.ticketVersion() == version ? 1 : 0);
        if (!analysis.status().equals("SUCCEEDED")) conflict("只有成功且未采纳的建议可以确认");
        Object result;
        try { result = outputs.validate(analysis.kind(), analysis.resultJson(), new HashSet<>(data.categories())); }
        catch (IllegalArgumentException ex) { throw new BusinessException(HttpStatus.CONFLICT, "AI_RESULT_INVALID", "建议已不符合当前规则，请重新分析或手动处理"); }
        if (result instanceof Classification classification) {
            Long category = data.categoryId(classification.category());
            if (category == null) conflict("建议分类当前不可用");
            tickets.applyClassification(ticketId, new EditInput(new TicketInput(ticket.title(), ticket.description(), category,
                    classification.priority(), new HashSet<>(classification.tags())), version), actor);
        } else if (result instanceof Reply reply) {
            tickets.comment(ticketId, new CommentInput(reply.suggestion(), false, version), actor);
        } else conflict("摘要用于阅读，无需采纳");
        TicketService.checkVersion(data.accept(analysisId, actor.id(), clock.instant()));
        tickets.audit(actor.id(), "AI_SUGGESTION_ACCEPTED", ticketId, null, Map.of("analysisId", analysisId, "kind", analysis.kind()), "人工确认AI建议");
        return data.find(analysisId);
    }

    public List<AiMapper.CallLog> logs(long ticketId, long analysisId, TicketActor actor) {
        Ticket ticket = tickets.read(ticketId, actor);
        if (!tickets.isStaff(ticket, actor)) forbidden();
        var analysis = data.find(analysisId);
        if (analysis == null || analysis.ticketId() != ticketId) throw new BusinessException(HttpStatus.NOT_FOUND, "AI_ANALYSIS_NOT_FOUND", "分析不存在");
        return data.logs(analysisId);
    }

    private void requireKindAccess(Ticket ticket, Kind kind, TicketActor actor) {
        if (kind != Kind.CLASSIFICATION && !tickets.isStaff(ticket, actor)) forbidden();
    }
    private void forbidden() { throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅负责客服、组长或管理员可查看或确认回复建议"); }
    private void conflict(String message) { throw new BusinessException(HttpStatus.CONFLICT, "AI_ANALYSIS_CONFLICT", message); }
}
