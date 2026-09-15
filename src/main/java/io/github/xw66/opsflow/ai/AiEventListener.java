package io.github.xw66.opsflow.ai;

import io.github.xw66.opsflow.event.MessageMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "opsflow.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class AiEventListener {
    private final AiAnalysisService service;
    private final MessageMapper failures;
    private final ObjectMapper json;
    private final Clock clock;
    public AiEventListener(AiAnalysisService service, MessageMapper failures, ObjectMapper json, Clock clock) {
        this.service = service; this.failures = failures; this.json = json; this.clock = clock;
    }
    @KafkaListener(id = "opsflowAi", groupId = "opsflow-ai", topics = "${opsflow.messaging.topic}", containerFactory = "aiKafkaFactory")
    public void receive(String raw) {
        service.enqueue(raw);
        failures.resolved("opsflow-ai", json.readTree(raw).path("eventId").asText(), clock.instant());
    }
}
