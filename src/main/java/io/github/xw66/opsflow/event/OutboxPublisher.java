package io.github.xw66.opsflow.event;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "opsflow.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxLeaseService leases;
    private final KafkaTemplate<Object, Object> kafka;
    private final String topic;
    private final boolean schedulingEnabled;

    public OutboxPublisher(OutboxLeaseService leases, KafkaTemplate<Object, Object> kafka,
            @Value("${opsflow.messaging.topic}") String topic,
            @Value("${opsflow.outbox.scheduling-enabled:true}") boolean schedulingEnabled) {
        this.leases = leases; this.kafka = kafka; this.topic = topic; this.schedulingEnabled = schedulingEnabled;
    }

    @Scheduled(fixedDelayString = "${opsflow.outbox.interval-ms:1000}")
    public void scan() {
        if (schedulingEnabled) for (int i = 0; i < 20 && publishOne(); i++) { }
    }

    public boolean publishOne() {
        var lease = leases.claim();
        if (lease == null) return false;
        try {
            // 网络等待不持有数据库事务；逐条领取，避免批量租约在排队期间过期。
            kafka.send(topic, Long.toString(lease.ticketId()), lease.payload()).get(5, TimeUnit.SECONDS);
            if (!leases.sent(lease)) log.warn("Outbox发送成功但租约已失效，将由消费者去重：{}", lease.eventId());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            leases.failed(lease, ex);
            return false;
        } catch (Exception ex) {
            leases.failed(lease, ex);
            log.warn("Outbox投递失败，已安排退避重试：{}，{}", lease.eventId(), ex.getClass().getSimpleName());
        }
        return true;
    }
}
