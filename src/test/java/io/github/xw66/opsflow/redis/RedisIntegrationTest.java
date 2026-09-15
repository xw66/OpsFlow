package io.github.xw66.opsflow.redis;

import io.github.xw66.opsflow.MySqlTestBase;
import io.github.xw66.opsflow.auth.*;
import io.github.xw66.opsflow.common.BusinessException;
import io.github.xw66.opsflow.event.OutboxMapper;
import io.github.xw66.opsflow.sla.SlaService;
import io.github.xw66.opsflow.support.*;
import io.github.xw66.opsflow.support.SupportModels.*;
import io.github.xw66.opsflow.ticket.*;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.ObjectMapper;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Timeout(90)
class RedisIntegrationTest extends MySqlTestBase {
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.0").withExposedPorts(6379);
    static { REDIS.start(); }
    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("opsflow.cache.enabled", () -> "true");
        registry.add("opsflow.idempotency.redis-enabled", () -> "true");
        registry.add("opsflow.rate-limit.enabled", () -> "true");
    }
    @Autowired StringRedisTemplate redis;
    @Autowired RedisGuard guard;
    @Autowired AuthService auth;
    @Autowired UserMapper users;
    @Autowired SupportService support;
    @Autowired TicketService tickets;
    @Autowired TicketCreationService creation;
    @Autowired TicketDetailCache cache;
    @Autowired WorkflowService workflow;
    @Autowired SlaService sla;
    @MockitoSpyBean TicketMapper data;
    @MockitoSpyBean OutboxMapper outbox;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void cleanRedis() {
        try (var connection = redis.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
    }
    private String key() { return UUID.randomUUID().toString(); }
    private long user(Role role) {
        long id = auth.register(new AuthController.RegisterRequest("u" + key().replace("-", "").substring(0, 20), "Strong_pass_123", "测试人员")).id();
        if (role != Role.USER) users.addRole(id, role);
        return id;
    }
    private TicketActor actor(long id) {
        var roles = users.roles(id);
        return new TicketActor(id, roles.contains(Role.ADMIN), roles.contains(Role.LEADER), roles.contains(Role.AGENT));
    }
    private String bearer(long id) {
        return "Bearer " + auth.login(new AuthController.LoginRequest(users.findById(id).username(), "Strong_pass_123")).accessToken();
    }
    private record Fixture(long admin, long leader, long owner, long group, long category) { }
    private Fixture fixture() {
        long admin = user(Role.ADMIN), leader = user(Role.LEADER), owner = user(Role.USER);
        long group = support.saveGroup(null, new GroupInput(key(), leader, true, 0), admin).id();
        long category = support.saveCategory(null, new CategoryInput("C" + key().replace("-", "").substring(0, 20), "Redis测试", group, true, 0), admin).id();
        support.savePolicy(null, new PolicyInput(category, Priority.HIGH, 30, 240, true, true, 0), admin);
        return new Fixture(admin, leader, owner, group, category);
    }
    private TicketInput input(Fixture f) { return new TicketInput("网络问题", "请处理", f.category(), Priority.HIGH, Set.of("vpn", "network")); }
    private int created(long owner, String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE user_id=? AND create_request_key=?", Integer.class, owner, key);
    }

    @Test
    void requiredKeyReplaysOriginalResponseAndRejectsDifferentBody() throws Exception {
        Fixture f = fixture();
        String key = key(), token = bearer(f.owner()), body = json.writeValueAsString(input(f));
        mvc.perform(post("/api/tickets").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tickets").header("Authorization", token).header("Idempotency-Key", "bad")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        var first = creation.create(key, input(f), actor(f.owner()));
        tickets.edit(first.ticket().id(), new EditInput(new TicketInput("更新标题", "请处理", f.category(), Priority.HIGH, Set.of()), 0), actor(f.owner()));
        assertEquals(first, creation.create(key, input(f), actor(f.owner())));
        mvc.perform(post("/api/tickets").header("Authorization", token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ticket.id").value(first.ticket().id())).andExpect(jsonPath("$.data.ticket.title").value("网络问题"));
        mvc.perform(post("/api/tickets").header("Authorization", token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("请处理", "其他问题"))).andExpect(status().isConflict());
        long other = user(Role.USER);
        assertNotEquals(first.ticket().id(), creation.create(key, input(f), actor(other)).ticket().id());
        assertEquals(1, created(f.owner(), key));
    }

    @Test
    void databaseUniqueKeyPreventsConcurrentCreationWithoutRedisLease() throws Exception {
        Fixture f = fixture();
        var databaseOnly = new TicketCreationService(tickets, data, guard, json, false);
        String key = key();
        var owner = actor(f.owner());
        List<Detail> results = parallel(8, () -> databaseOnly.create(key, input(f), owner));
        assertEquals(1, results.stream().map(d -> d.ticket().id()).distinct().count());
        assertEquals(1, created(f.owner(), key));
        long id = results.getFirst().ticket().id();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", Integer.class, id));
        assertEquals(1, data.histories(id, 0, 100).size());
    }

    @Test
    void expiredLeaseAndStaleReleaseCannotLoseNewOwnersLock() {
        String key = "test:lease:" + key(), hash = "a".repeat(64);
        assertEquals(1, guard.claim(key, hash, "old"));
        assertEquals(0, guard.claim(key, hash, "same"));
        assertEquals(-1, guard.claim(key, "b".repeat(64), "different"));
        redis.expire(key, Duration.ofMillis(50));
        await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        assertEquals(1, guard.claim(key, hash, "new"));
        guard.release(key, hash, "old");
        assertEquals(hash + ":new", redis.opsForValue().get(key));
        guard.release(key, hash, "new");
        assertFalse(redis.hasKey(key));
    }

    @Test
    void transactionFailureReleasesLeaseAndAllowsSameRequestRetry() {
        Fixture f = fixture();
        String key = key();
        doThrow(new IllegalStateException("模拟事件写入失败")).when(outbox).insert(anyString(), anyLong(), anyLong(), eq("TicketCreatedEvent"), anyString(), any());
        assertThrows(IllegalStateException.class, () -> creation.create(key, input(f), actor(f.owner())));
        assertEquals(0, created(f.owner(), key));
        assertFalse(redis.hasKey("opsflow:create:" + f.owner() + ":" + key));
        reset(outbox);
        var first = creation.create(key, input(f), actor(f.owner()));
        redis.delete("opsflow:create:" + f.owner() + ":" + key);
        assertEquals(first, creation.create(key, input(f), actor(f.owner())));
    }

    @Test
    void cacheHitChecksCurrentPermissionsAndEvictsOnlyAfterCommit() {
        Fixture f = fixture();
        long id = tickets.create(input(f), actor(f.owner())).ticket().id();
        clearInvocations(data);
        cache.detail(id, actor(f.owner()));
        cache.detail(id, actor(f.owner()));
        verify(data, times(1)).tags(id);
        assertTrue(redis.getExpire("opsflow:ticket:" + id) > 0);
        assertThrows(BusinessException.class, () -> cache.detail(id, actor(user(Role.USER))));
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            tickets.edit(id, new EditInput(new TicketInput("回滚修改", "请处理", f.category(), Priority.HIGH, Set.of()), 0), actor(f.owner()));
            assertTrue(redis.hasKey("opsflow:ticket:" + id));
            status.setRollbackOnly();
        });
        assertEquals("网络问题", cache.detail(id, actor(f.owner())).ticket().title());
        tx.executeWithoutResult(status -> {
            tickets.edit(id, new EditInput(new TicketInput("提交修改", "请处理", f.category(), Priority.HIGH, Set.of()), 0), actor(f.owner()));
            assertTrue(redis.hasKey("opsflow:ticket:" + id));
        });
        assertFalse(redis.hasKey("opsflow:ticket:" + id));
        assertEquals("提交修改", cache.detail(id, actor(f.owner())).ticket().title());
        jdbc.update("UPDATE ticket SET response_deadline='2000-01-01' WHERE id=?", id);
        sla.inspect(id, true);
        assertFalse(redis.hasKey("opsflow:ticket:" + id));
    }

    @Test
    void cachedOldAssigneeCannotReadAfterTransferEvenIfStaleValueIsReinserted() {
        Fixture f = fixture();
        long first = user(Role.AGENT), second = user(Role.AGENT);
        for (long id : List.of(first, second)) {
            support.saveAgent(null, new AgentInput(id, f.group(), true, 0), f.admin());
            support.setOnline(id, new OnlineInput(true, 0));
        }
        long id = tickets.create(input(f), actor(f.owner())).ticket().id();
        workflow.assign(id, new AssignInput(first, 0, "分配"), actor(f.leader()));
        cache.detail(id, actor(first));
        String stale = redis.opsForValue().get("opsflow:ticket:" + id);
        workflow.transfer(id, new TransferInput(second, 1, "转派"), actor(f.leader()));
        assertFalse(redis.hasKey("opsflow:ticket:" + id));
        redis.opsForValue().set("opsflow:ticket:" + id, stale, Duration.ofSeconds(30));
        assertThrows(BusinessException.class, () -> cache.detail(id, actor(first)));
        assertNotNull(cache.detail(id, actor(second)));
    }

    @Test
    void atomicLimiterAllowsExactQuotaAndHttpIncludesRetryAfter() throws Exception {
        String key = "test:rate:" + key();
        assertEquals(5, parallel(20, () -> guard.limit(key, 5, 60000)).stream().filter(value -> value == 0).count());
        redis.expire(key, Duration.ofMillis(50));
        await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        assertEquals(0, guard.limit(key, 5, 60000));
        for (int i = 0; i < 10; i++) mvc.perform(post("/api/auth/login").with(req -> { req.setRemoteAddr("192.0.2.10"); return req; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"missing\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(req -> { req.setRemoteAddr("192.0.2.10"); return req; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"missing\",\"password\":\"bad\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void redisOutageFallsBackForCacheAndCreationButClosesLogin() throws Exception {
        Fixture f = fixture();
        var owner = actor(f.owner());
        long id = tickets.create(input(f), owner).ticket().id();
        cache.detail(id, owner);
        String key = key();
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertEquals(id, cache.detail(id, owner).ticket().id());
            var first = creation.create(key, input(f), owner);
            assertEquals(first, creation.create(key, input(f), owner));
            assertEquals(1, created(f.owner(), key));
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"missing\",\"password\":\"bad\"}"))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().exists("Retry-After"));
        } finally { REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            redis.opsForValue().set("test:recovered", "ok");
            assertEquals("ok", redis.opsForValue().get("test:recovered"));
        });
        assertEquals(id, cache.detail(id, owner).ticket().id());
    }

    private <T> List<T> parallel(int count, Callable<T> task) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(count)) {
            List<Future<T>> work = new ArrayList<>();
            for (int i = 0; i < count; i++) work.add(executor.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return task.call(); }));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var result : work) results.add(result.get(20, TimeUnit.SECONDS));
            return results;
        }
    }
}
