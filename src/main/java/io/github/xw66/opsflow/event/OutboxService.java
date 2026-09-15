package io.github.xw66.opsflow.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxService {
    private final OutboxMapper data;
    private final ObjectMapper json;

    public OutboxService(OutboxMapper data, ObjectMapper json) { this.data = data; this.json = json; }

    // 强制复用业务事务，防止工单提交成功却遗漏待投递事件。
    @Transactional(propagation = Propagation.MANDATORY)
    public String append(long ticketId, long version, String type, Object payload, Instant now) {
        String id = UUID.randomUUID().toString();
        data.insert(id, ticketId, version, type,
                json.writeValueAsString(new Event(id, ticketId, version, type, 1, now, payload)), now);
        return id;
    }

    public record Event(String eventId, long ticketId, long ticketVersion, String eventType,
            int schemaVersion, Instant occurredAt, Object data) { }
}
