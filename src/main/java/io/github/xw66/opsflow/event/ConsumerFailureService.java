package io.github.xw66.opsflow.event;

import io.github.xw66.opsflow.common.AuditMapper;
import io.github.xw66.opsflow.common.BusinessException;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ConsumerFailureService {
    private final MessageMapper messages;
    private final OutboxMapper outbox;
    private final AuditMapper audit;
    private final ObjectMapper json;
    private final Clock clock;
    public ConsumerFailureService(MessageMapper messages, OutboxMapper outbox, AuditMapper audit, ObjectMapper json, Clock clock) {
        this.messages = messages; this.outbox = outbox; this.audit = audit; this.json = json; this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ConsumerRecord<?, ?> record, Exception error, int attempt) {
        record(EventProcessor.CONSUMER, record, error, attempt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String consumer, ConsumerRecord<?, ?> record, Exception error, int attempt) {
        String payload = record.value() instanceof String s ? s : null;
        String eventId = null;
        try {
            if (payload != null && payload.length() <= 131072) {
                String candidate = json.readTree(payload).path("eventId").asText();
                if (UUID.fromString(candidate).toString().equals(candidate)) eventId = candidate;
            }
        } catch (RuntimeException ignored) { }
        Throwable cause = error.getCause() == null ? error : error.getCause();
        messages.failure(consumer, eventId, record.topic(), record.partition(), record.offset(), attempt,
                cause.getClass().getSimpleName(), payload, clock.instant());
    }

    @Transactional
    public void replay(long id, long actor, String reason) {
        var failure = messages.failureById(id);
        if (failure == null) throw new BusinessException(HttpStatus.NOT_FOUND, "FAILURE_NOT_FOUND", "失败记录不存在");
        if (!failure.status().equals("OPEN") || failure.eventId() == null || outbox.requeue(failure.eventId(), clock.instant()) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "REPLAY_UNAVAILABLE", "仅可重放尚未解决且关联已发送 Outbox 的事件");
        }
        messages.requeued(id);
        audit.insert(actor, "CONSUMER_REPLAY_REQUESTED", "CONSUMER_FAILURE", id, null,
                json.writeValueAsString(Map.of("eventId", failure.eventId())), reason);
    }
}
