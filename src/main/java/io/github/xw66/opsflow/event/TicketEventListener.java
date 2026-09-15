package io.github.xw66.opsflow.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "opsflow.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class TicketEventListener {
    private final EventProcessor processor;
    public TicketEventListener(EventProcessor processor) { this.processor = processor; }

    @KafkaListener(id = "opsflowNotifications", groupId = EventProcessor.CONSUMER, topics = "${opsflow.messaging.topic}")
    public void receive(String payload) { processor.process(payload); }
}
