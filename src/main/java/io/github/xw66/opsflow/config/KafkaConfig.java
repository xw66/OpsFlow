package io.github.xw66.opsflow.config;

import io.github.xw66.opsflow.event.ConsumerFailureService;
import io.github.xw66.opsflow.event.EventProcessor;
import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "opsflow.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfig {
    @Bean
    KafkaAdmin.NewTopics topics(@Value("${opsflow.messaging.topic}") String topic) {
        return new KafkaAdmin.NewTopics(TopicBuilder.name(topic).partitions(3).replicas(1).build(),
                TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build());
    }

    @Bean
    @Primary
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template, ConsumerFailureService failures,
            @Value("${opsflow.messaging.topic}") String topic) {
        return errorHandler(template, failures, topic, EventProcessor.CONSUMER);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> aiKafkaFactory(ConsumerFactory<Object, Object> consumers,
            KafkaTemplate<Object, Object> template, ConsumerFailureService failures, @Value("${opsflow.messaging.topic}") String topic) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        factory.setConsumerFactory(consumers);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(errorHandler(template, failures, topic, "opsflow-ai"));
        return factory;
    }

    private DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> template, ConsumerFailureService failures, String topic, String consumer) {
        var deadLetter = new DeadLetterPublishingRecoverer(template, (record, error) -> new TopicPartition(topic + ".DLT", record.partition()));
        deadLetter.setFailIfSendResultIsError(true);
        deadLetter.setWaitForSendResultTimeout(Duration.ofSeconds(5));
        var handler = new DefaultErrorHandler((record, error) -> {
            failures.record(consumer, record, error, 1);
            // 若失败记录或死信投递失败，继续抛错，原消息位点不会被当作已处理提交。
            deadLetter.accept(record, error);
        }, new FixedBackOff(500, 2));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        handler.setRetryListeners((record, error, attempt) -> failures.record(consumer, record, error, attempt));
        return handler;
    }
}
