package io.github.xw66.opsflow.event;

import io.github.xw66.opsflow.MySqlTestBase;
import io.github.xw66.opsflow.ai.AiAnalysisService;
import io.github.xw66.opsflow.ai.AiModels.Kind;
import io.github.xw66.opsflow.ai.AiWorker;
import io.github.xw66.opsflow.auth.*;
import io.github.xw66.opsflow.support.*;
import io.github.xw66.opsflow.support.SupportModels.*;
import io.github.xw66.opsflow.ticket.*;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Timeout(90)
class MessagingIntegrationTest extends MySqlTestBase {
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");
    private static final String TOPIC = "opsflow.test." + UUID.randomUUID();
    static { KAFKA.start(); }
    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("opsflow.messaging.enabled", () -> "true");
        registry.add("opsflow.messaging.topic", () -> TOPIC);
    }

    @Autowired AuthService auth;
    @Autowired UserMapper users;
    @Autowired SupportService support;
    @Autowired TicketService tickets;
    @Autowired OutboxPublisher publisher;
    @Autowired OutboxLeaseService leases;
    @Autowired EventProcessor processor;
    @Autowired ConsumerFailureService failures;
    @Autowired AiAnalysisService ai;
    @Autowired AiWorker aiWorker;
    @MockitoSpyBean MessageMapper messages;
    @MockitoSpyBean(name = "kafkaTemplate") KafkaTemplate<?, ?> kafkaSpy;
    @Autowired KafkaTemplate<Object, Object> kafka;
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlSessionTemplate sqlSession;
    @Autowired MockMvc mvc;

    private record Sample(long ticket, long owner, long admin, String eventId, String payload) { }
    private String unique() { return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(); }
    private long user(Role role) {
        long id = auth.register(new AuthController.RegisterRequest(unique(), "Strong_pass_123", "测试人员")).id();
        if (role != Role.USER) users.addRole(id, role);
        return id;
    }
    private Sample sample() {
        long admin = user(Role.ADMIN), leader = user(Role.LEADER), owner = user(Role.USER);
        long group = support.saveGroup(null, new GroupInput(unique(), leader, true, 0), admin).id();
        long category = support.saveCategory(null, new CategoryInput(unique(), "消息测试", group, true, 0), admin).id();
        support.savePolicy(null, new PolicyInput(category, Priority.HIGH, 30, 240, true, true, 0), admin);
        long ticket = tickets.create(new TicketInput("消息可靠性测试", "测试描述", category, Priority.HIGH, Set.of()),
                new TicketActor(owner, false, false, false)).ticket().id();
        String event = jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE aggregate_id=?", String.class, ticket);
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_id=?", String.class, event);
        return new Sample(ticket, owner, admin, event, payload);
    }
    private int count(String table, String event) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE event_id=?", Integer.class, event); }
    private String bearer(long user) { return "Bearer " + auth.login(new AuthController.LoginRequest(users.findById(user).username(), "Strong_pass_123")).accessToken(); }
    private void publishUntilSent(Sample sample) {
        for (int i = 0; i < 300 && !"SENT".equals(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, sample.eventId())); i++) publisher.publishOne();
        assertEquals("SENT", jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, sample.eventId()));
    }

    @Test
    void manualAiRequestTraversesKafkaWithoutDuplicateCallsOrUserNotifications() throws Exception {
        Sample s = sample();
        var analysis = ai.request(s.ticket(), Kind.SUMMARY, 0, new TicketActor(s.admin(), true, false, false));
        String eventId = jdbc.queryForObject("SELECT source_event_id FROM ticket_ai_analysis WHERE id=?", String.class, analysis.id());
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_id=?", String.class, eventId);
        publishUntilSent(new Sample(s.ticket(), s.owner(), s.admin(), eventId, payload));
        kafka.send(TOPIC, Long.toString(s.ticket()), payload).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals("PENDING",
                jdbc.queryForObject("SELECT status FROM ticket_ai_analysis WHERE id=?", String.class, analysis.id())));
        for (int i = 0; i < 200 && !"DISABLED".equals(jdbc.queryForObject("SELECT status FROM ticket_ai_analysis WHERE id=?", String.class, analysis.id())); i++) aiWorker.processOne();
        assertEquals("DISABLED", jdbc.queryForObject("SELECT status FROM ticket_ai_analysis WHERE id=?", String.class, analysis.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_log WHERE analysis_id=?", Integer.class, analysis.id()));
        assertEquals(0, count("notification", eventId));
        assertEquals(TicketStatus.CREATED, tickets.detail(s.ticket(), new TicketActor(s.owner(), false, false, false)).ticket().status());
    }

    @Test
    void realKafkaRoundTripAndDuplicateDeliveryCreateOneNotification() throws Exception {
        Sample s = sample();
        publishUntilSent(s);
        kafka.send(TOPIC, Long.toString(s.ticket()), s.payload()).get(10, TimeUnit.SECONDS);
        kafka.send(TOPIC, Long.toString(s.ticket()), s.payload()).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(1, count("consumed_event", s.eventId())));
        assertEquals(1, count("notification", s.eventId()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM ticket_ai_analysis WHERE source_event_id=?", Integer.class, s.eventId())));
        processor.process(s.payload());
        assertEquals(1, count("notification", s.eventId()));
        long notification = jdbc.queryForObject("SELECT id FROM notification WHERE event_id=?", Long.class, s.eventId());
        mvc.perform(put("/api/notifications/" + notification + "/read").header("Authorization", bearer(s.admin())))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/notifications/" + notification + "/read").header("Authorization", bearer(s.owner())))
                .andExpect(status().isOk());
    }

    @Test
    void failedSideEffectRollsBackDedupMarkerAndRejectsEventIdReuse() {
        Sample s = sample();
        doThrow(new IllegalStateException("模拟通知写入失败")).when(messages).notify(anyLong(), eq(s.eventId()), anyLong(), anyString(), any());
        assertThrows(IllegalStateException.class, () -> processor.process(s.payload()));
        assertEquals(0, count("consumed_event", s.eventId()));
        assertEquals(0, count("notification", s.eventId()));
        reset(messages);
        processor.process(s.payload());
        assertThrows(IllegalArgumentException.class, () -> processor.process(s.payload().replace("测试描述", "不同内容")));
        assertEquals(1, count("notification", s.eventId()));
    }

    @Test
    void concurrentDuplicateConsumptionCommitsOnce() throws Exception {
        Sample s = sample();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 6; i++) work.add(executor.submit(() -> {
                try { assertTrue(start.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { throw new RuntimeException(ex); }
                processor.process(s.payload());
            }));
            start.countDown();
            for (var result : work) result.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, count("consumed_event", s.eventId()));
        assertEquals(1, count("notification", s.eventId()));
    }

    @Test
    void acknowledgedEventWithExpiredLeaseIsRedeliveredWithoutDuplicateEffects() throws Exception {
        Sample s = sample();
        jdbc.update("UPDATE outbox_event SET available_at='2000-01-01' WHERE event_id=?", s.eventId());
        var old = leases.claim();
        assertNotNull(old);
        assertEquals(s.eventId(), old.eventId());
        kafka.send(TOPIC, Long.toString(old.ticketId()), old.payload()).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(1, count("notification", s.eventId())));
        // 模拟收到 ACK 后进程退出，SENT 尚未落库；恢复实例必须重新发送同一事件。
        jdbc.update("UPDATE outbox_event SET lease_until='2000-01-01' WHERE event_id=?", old.eventId());
        var replacement = leases.claim();
        assertNotNull(replacement);
        assertEquals(old.eventId(), replacement.eventId());
        assertNotEquals(old.owner(), replacement.owner());
        assertFalse(leases.sent(old));
        var metadata = kafka.send(TOPIC, Long.toString(replacement.ticketId()), replacement.payload())
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
        assertTrue(leases.sent(replacement));
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var offset = admin.listConsumerGroupOffsets(EventProcessor.CONSUMER).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS).get(new TopicPartition(TOPIC, metadata.partition()));
                assertNotNull(offset);
                assertTrue(offset.offset() > metadata.offset());
            });
        }
        assertEquals(1, count("notification", s.eventId()));
        assertEquals(1, count("consumed_event", s.eventId()));
    }

    @Test
    void brokerPauseLeavesOutboxRetryableAndRecoversWithoutDuplicateEffects() {
        Sample s;
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            s = sample();
            jdbc.update("UPDATE outbox_event SET available_at='1999-01-01' WHERE event_id=?", s.eventId());
            assertTrue(publisher.publishOne());
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, s.eventId()));
        } finally { KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
        jdbc.update("UPDATE outbox_event SET available_at='1999-01-01' WHERE event_id=?", s.eventId());
        publishUntilSent(s);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals(1, count("notification", s.eventId())));
    }

    @Test
    void transientConsumerFailureRetriesAndIsRecordedAsResolved() throws Exception {
        Sample s = sample();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() <= 2) throw new IllegalStateException("模拟短暂数据库故障");
            sqlSession.getMapper(MessageMapper.class).notify(call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3), call.getArgument(4));
            return null;
        }).when(messages).notify(anyLong(), eq(s.eventId()), anyLong(), anyString(), any());
        kafka.send(TOPIC, Long.toString(s.ticket()), s.payload()).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(1, count("notification", s.eventId())));
        assertEquals(3, attempts.get());
        assertEquals("RESOLVED", jdbc.queryForObject("SELECT status FROM consumer_failure WHERE event_id=?", String.class, s.eventId()));
    }

    @Test
    void exhaustedFailureGoesToDltAndAuthorizedReplayKeepsSameEventId() throws Exception {
        Sample s = sample();
        doThrow(new IllegalStateException("模拟持续消费故障")).when(messages).notify(anyLong(), eq(s.eventId()), anyLong(), anyString(), any());
        publishUntilSent(s);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(1, count("consumer_failure", s.eventId())));
        readDlt(s.payload());
        assertEquals(0, count("consumed_event", s.eventId()));
        long failure = jdbc.queryForObject("SELECT id FROM consumer_failure WHERE event_id=?", Long.class, s.eventId());
        mvc.perform(post("/api/admin/consumer-failures/" + failure + "/replay").header("Authorization", bearer(s.owner()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"重试\"}")).andExpect(status().isForbidden());
        reset(messages);
        mvc.perform(post("/api/admin/consumer-failures/" + failure + "/replay").header("Authorization", bearer(s.admin()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"故障已修复\"}")).andExpect(status().isOk());
        publishUntilSent(s);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals("RESOLVED",
                jdbc.queryForObject("SELECT status FROM consumer_failure WHERE id=?", String.class, failure)));
        assertEquals(1, count("notification", s.eventId()));
    }

    @Test
    void dltSendFailureDoesNotCommitOriginalOffset() throws Exception {
        String malformed = "invalid-json-" + UUID.randomUUID();
        AtomicBoolean blocked = new AtomicBoolean(true);
        AtomicInteger rejected = new AtomicInteger();
        doAnswer(call -> {
            ProducerRecord<String, String> record = call.getArgument(0);
            if (record.topic().equals(TOPIC + ".DLT") && malformed.equals(record.value()) && blocked.get()) {
                rejected.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("模拟死信发送失败"));
            }
            return call.callRealMethod();
        }).when(kafka).send(any(ProducerRecord.class));
        var metadata = kafka.send(TOPIC, 0, "invalid", malformed).get(10, TimeUnit.SECONDS).getRecordMetadata();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(20)).until(() -> rejected.get() > 0);
            var tp = new TopicPartition(TOPIC, 0);
            var offset = admin.listConsumerGroupOffsets(EventProcessor.CONSUMER).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(tp);
            assertTrue(offset == null || offset.offset() <= metadata.offset());
            blocked.set(false);
            readDlt(malformed);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertTrue(
                    admin.listConsumerGroupOffsets(EventProcessor.CONSUMER).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(tp).offset() > metadata.offset()));
        } finally { blocked.set(false); }
    }

    private void readDlt(String expected) {
        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", KAFKA.getBootstrapServers());
        config.put("group.id", "dlt-check-" + UUID.randomUUID());
        config.put("auto.offset.reset", "earliest");
        config.put("enable.auto.commit", false);
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(200))) if (expected.equals(record.value())) return true;
                return false;
            });
        }
    }
}
