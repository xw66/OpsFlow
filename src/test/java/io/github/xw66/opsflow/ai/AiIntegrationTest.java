package io.github.xw66.opsflow.ai;

import io.github.xw66.opsflow.MySqlTestBase;
import io.github.xw66.opsflow.ai.AiModels.*;
import io.github.xw66.opsflow.auth.*;
import io.github.xw66.opsflow.common.BusinessException;
import io.github.xw66.opsflow.event.OutboxMapper;
import io.github.xw66.opsflow.support.*;
import io.github.xw66.opsflow.support.SupportModels.*;
import io.github.xw66.opsflow.ticket.*;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Timeout(90)
class AiIntegrationTest extends MySqlTestBase {
    @Autowired AuthService auth;
    @Autowired UserMapper users;
    @Autowired SupportService support;
    @Autowired TicketService tickets;
    @Autowired TicketMapper ticketData;
    @Autowired AiAnalysisService service;
    @MockitoSpyBean AiMapper data;
    @Autowired AiWorker worker;
    @Autowired AiWorkService work;
    @MockitoBean AiModelService model;
    @MockitoSpyBean OutboxMapper outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    private String key() { return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(); }
    private long user(Role role) {
        long id = auth.register(new AuthController.RegisterRequest(key(), "Strong_pass_123", "测试人员")).id();
        if (role != Role.USER) users.addRole(id, role);
        return id;
    }
    private TicketActor actor(long id) {
        var roles = users.roles(id);
        return new TicketActor(id, roles.contains(Role.ADMIN), roles.contains(Role.LEADER), roles.contains(Role.AGENT));
    }
    private record Fixture(long owner, long leader, long admin, long category, String code, long ticket) { }
    private Fixture fixture() {
        long admin = user(Role.ADMIN), owner = user(Role.USER), leader = user(Role.LEADER);
        long group = support.saveGroup(null, new GroupInput(key(), leader, true, 0), admin).id();
        String code = key();
        long category = support.saveCategory(null, new CategoryInput(code, "AI测试", group, true, 0), admin).id();
        support.savePolicy(null, new PolicyInput(category, Priority.HIGH, 30, 240, true, true, 0), admin);
        long ticket = tickets.create(new TicketInput("网络报错", "无法访问", category, Priority.HIGH, Set.of()), actor(owner)).ticket().id();
        return new Fixture(owner, leader, admin, category, code, ticket);
    }
    private String payload(String eventId) { return jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_id=?", String.class, eventId); }
    private AiMapper.Analysis request(Fixture f, Kind kind) {
        var analysis = service.request(f.ticket(), kind, ticketData.find(f.ticket()).version(), actor(kind == Kind.CLASSIFICATION ? f.owner() : f.leader()));
        service.enqueue(payload(data.eventId(analysis.id())));
        return analysis;
    }
    private CallResult success(String result) { return new CallResult("SUCCEEDED", result, "test-model", 10, 5, 12, 1, null); }
    private String classification(Fixture f) { return json.writeValueAsString(Map.of("category", f.code(), "priority", "HIGH", "tags", List.of("vpn"), "confidence", 0.9, "reason", "网络连接问题")); }
    private void complete(long id, CallResult result) {
        when(model.call(any(), anyString(), anySet())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return result;
        });
        for (int i = 0; i < 200 && Set.of("PENDING", "PROCESSING").contains(data.find(id).status()); i++) worker.processOne();
        assertEquals(result.outcome(), data.find(id).status());
    }
    private String bearer(long id) { return "Bearer " + auth.login(new AuthController.LoginRequest(users.findById(id).username(), "Strong_pass_123")).accessToken(); }

    @Test
    void creationEventEnqueuesOnceAndModelFailureNeverChangesTicket() {
        Fixture f = fixture();
        verifyNoInteractions(model);
        String event = jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE aggregate_id=?", String.class, f.ticket());
        service.enqueue(payload(event)); service.enqueue(payload(event));
        var analysis = data.snapshot(f.ticket(), 0, Kind.CLASSIFICATION);
        complete(analysis.id(), new CallResult("FAILED", null, "test-model", null, null, 20, 2, "TIMEOUT"));
        assertEquals(1, data.list(f.ticket(), true, 0, 100).size());
        assertEquals(0, ticketData.find(f.ticket()).version());
        assertEquals(f.category(), ticketData.find(f.ticket()).categoryId());
        assertEquals(TicketStatus.CREATED, ticketData.find(f.ticket()).status());
        assertNull(data.logs(analysis.id()).getFirst().inputTokens());
        var retried = request(f, Kind.CLASSIFICATION);
        assertEquals(analysis.id(), retried.id());
        complete(retried.id(), new CallResult("DISABLED", null, "unconfigured", null, null, 0, 0, "AI_DISABLED"));
        assertEquals(2, data.logs(analysis.id()).size());
        tickets.edit(f.ticket(), new EditInput(new TicketInput("手工处理", "已选择分类", f.category(), Priority.HIGH, Set.of()), 0), actor(f.owner()));
        assertEquals("手工处理", ticketData.find(f.ticket()).title());
    }

    @Test
    void classificationNeedsExplicitAcceptanceAndPersistsAdoption() {
        Fixture f = fixture();
        var analysis = request(f, Kind.CLASSIFICATION);
        complete(analysis.id(), success(classification(f)));
        assertTrue(ticketData.tags(f.ticket()).isEmpty());
        var accepted = service.accept(f.ticket(), analysis.id(), 0, actor(f.leader()));
        assertEquals("ACCEPTED", accepted.status());
        assertEquals(f.leader(), accepted.acceptedBy());
        assertEquals(List.of("vpn"), ticketData.tags(f.ticket()));
        assertEquals(TicketStatus.CREATED, ticketData.find(f.ticket()).status());
        assertThrows(BusinessException.class, () -> service.accept(f.ticket(), analysis.id(), 1, actor(f.owner())));
        assertEquals(1, data.logs(analysis.id()).size());
    }

    @Test
    void replyIsPrivateUntilStaffConfirmsAndCannotBeSentTwice() throws Exception {
        Fixture f = fixture();
        var analysis = request(f, Kind.REPLY);
        complete(analysis.id(), success("{\"suggestion\":\"请提供错误码。\"}"));
        assertTrue(ticketData.comments(f.ticket(), true, 0, 100).isEmpty());
        assertTrue(service.list(f.ticket(), actor(f.owner()), 0, 100).isEmpty());
        mvc.perform(post("/api/tickets/" + f.ticket() + "/ai-analyses/" + analysis.id() + "/accept")
                .header("Authorization", bearer(f.owner())).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden());
        service.accept(f.ticket(), analysis.id(), 0, actor(f.leader()));
        assertEquals("请提供错误码。", ticketData.comments(f.ticket(), false, 0, 100).getFirst().content());
        assertThrows(BusinessException.class, () -> service.accept(f.ticket(), analysis.id(), 1, actor(f.leader())));
        assertEquals(1, ticketData.comments(f.ticket(), false, 0, 100).size());
    }

    @Test
    void staleSuggestionAndDisabledCategoryAreRejected() {
        Fixture f = fixture();
        var analysis = request(f, Kind.CLASSIFICATION);
        complete(analysis.id(), success(classification(f)));
        jdbc.update("UPDATE ticket_category SET enabled=FALSE WHERE id=?", f.category());
        assertEquals("AI_RESULT_INVALID", assertThrows(BusinessException.class,
                () -> service.accept(f.ticket(), analysis.id(), 0, actor(f.owner()))).getCode());
        jdbc.update("UPDATE ticket_category SET enabled=TRUE WHERE id=?", f.category());
        tickets.edit(f.ticket(), new EditInput(new TicketInput("新标题", "新信息", f.category(), Priority.HIGH, Set.of()), 0), actor(f.owner()));
        assertEquals("VERSION_CONFLICT", assertThrows(BusinessException.class,
                () -> service.accept(f.ticket(), analysis.id(), 1, actor(f.owner()))).getCode());
        assertEquals("SUCCEEDED", data.find(analysis.id()).status());
    }

    @Test
    void concurrentRequestsShareOneAnalysisAndOneOutboxEvent() throws Exception {
        Fixture f = fixture();
        TicketActor owner = actor(f.owner());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<AiMapper.Analysis>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return service.request(f.ticket(), Kind.CLASSIFICATION, 0, owner); }));
            start.countDown();
            Set<Long> ids = new HashSet<>();
            for (var result : results) ids.add(result.get(20, TimeUnit.SECONDS).id());
            assertEquals(1, ids.size());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='TicketAiAnalysisRequestedEvent'", Integer.class, f.ticket()));
        var analysis = data.snapshot(f.ticket(), 0, Kind.CLASSIFICATION);
        String raw = payload(data.eventId(analysis.id()));
        service.enqueue(raw); service.enqueue(raw);
        assertThrows(IllegalArgumentException.class, () -> service.enqueue(raw.replace("analysisId", "invalidId")));
        complete(analysis.id(), success(classification(f)));
    }

    @Test
    void expiredWorkerLeaseCannotOverwriteReplacementResult() {
        AiWorkService.Lease queued;
        while ((queued = work.claim()) != null) work.finish(queued, new CallResult("DISABLED", null, "unconfigured", null, null, 0, 0, "AI_DISABLED"));
        Fixture f = fixture();
        var analysis = request(f, Kind.SUMMARY);
        var old = work.claim();
        assertEquals(analysis.id(), old.pending().id());
        jdbc.update("UPDATE ticket_ai_analysis SET lease_until='2000-01-01' WHERE id=?", analysis.id());
        var replacement = work.claim();
        assertNotEquals(old.owner(), replacement.owner());
        work.finish(old, success("{\"summary\":\"旧结果\"}"));
        assertEquals("PROCESSING", data.find(analysis.id()).status());
        work.finish(replacement, success("{\"summary\":\"新结果\"}"));
        assertTrue(data.find(analysis.id()).resultJson().contains("新结果"));
        assertEquals(1, data.logs(analysis.id()).stream().filter(AiMapper.CallLog::applied).count());
        assertThrows(BusinessException.class, () -> service.accept(f.ticket(), analysis.id(), 0, actor(f.leader())));
    }

    @Test
    void requestAndAcceptanceFailuresRollbackTheirSideEffects() {
        Fixture f = fixture();
        doThrow(new IllegalStateException("模拟Outbox失败")).when(outbox)
                .insert(anyString(), eq(f.ticket()), anyLong(), eq("TicketAiAnalysisRequestedEvent"), anyString(), any());
        assertThrows(IllegalStateException.class, () -> service.request(f.ticket(), Kind.REPLY, 0, actor(f.leader())));
        assertNull(data.snapshot(f.ticket(), 0, Kind.REPLY));
        reset(outbox);
        var analysis = request(f, Kind.REPLY);
        complete(analysis.id(), success("{\"suggestion\":\"待确认回复\"}"));
        doThrow(new IllegalStateException("模拟采纳记录写入失败")).when(data).accept(eq(analysis.id()), anyLong(), any());
        assertThrows(IllegalStateException.class, () -> service.accept(f.ticket(), analysis.id(), 0, actor(f.leader())));
        assertTrue(ticketData.comments(f.ticket(), true, 0, 100).isEmpty());
        assertEquals(0, ticketData.find(f.ticket()).version());
        assertEquals("SUCCEEDED", data.find(analysis.id()).status());
        reset(data);
        service.accept(f.ticket(), analysis.id(), 0, actor(f.leader()));
        assertEquals(1, ticketData.comments(f.ticket(), true, 0, 100).size());
    }

    @Test
    void summaryUsesBoundedPublicConversationAndApiChecksOwnership() throws Exception {
        Fixture f = fixture();
        tickets.comment(f.ticket(), new CommentInput("内部凭据不能外发", true, 0), actor(f.leader()));
        tickets.comment(f.ticket(), new CommentInput("公开排查进度", false, 1), actor(f.leader()));
        var analysis = request(f, Kind.SUMMARY);
        String input = jdbc.queryForObject("SELECT input_text FROM ticket_ai_analysis WHERE id=?", String.class, analysis.id());
        assertTrue(input.contains("公开排查进度"));
        assertFalse(input.contains("内部凭据"));
        complete(analysis.id(), success("{\"summary\":\"正在排查网络问题\"}"));
        long other = user(Role.USER);
        mvc.perform(get("/api/tickets/" + f.ticket() + "/ai-analyses").header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/tickets/" + f.ticket() + "/ai-analyses/" + analysis.id() + "/calls").header("Authorization", bearer(f.owner())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/" + f.ticket() + "/ai-analyses").header("Authorization", bearer(f.leader()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"AGENT\",\"version\":2}"))
                .andExpect(status().isBadRequest());
    }
}
