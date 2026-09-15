package io.github.xw66.opsflow.sla;

import io.github.xw66.opsflow.MySqlTestBase;
import io.github.xw66.opsflow.auth.*;
import io.github.xw66.opsflow.common.BusinessException;
import io.github.xw66.opsflow.event.*;
import io.github.xw66.opsflow.support.*;
import io.github.xw66.opsflow.support.SupportModels.*;
import io.github.xw66.opsflow.ticket.*;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Timeout(90)
class SlaIntegrationTest extends MySqlTestBase {
    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    @MockitoBean Clock clock;
    @MockitoSpyBean OutboxMapper outbox;
    @Autowired AuthService auth;
    @Autowired UserMapper users;
    @Autowired SupportService support;
    @Autowired TicketService tickets;
    @Autowired TicketMapper data;
    @Autowired WorkflowService workflow;
    @Autowired SlaService sla;
    @Autowired SlaMapper records;
    @Autowired EventProcessor processor;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void time() { when(clock.instant()).thenReturn(START); when(clock.getZone()).thenReturn(ZoneOffset.UTC); }
    private void at(Instant instant) { when(clock.instant()).thenReturn(instant); }
    private String unique() { return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(); }
    private long user(Role role) {
        long id = auth.register(new AuthController.RegisterRequest(unique(), "Strong_pass_123", "测试人员")).id();
        if (role != Role.USER) users.addRole(id, role);
        return id;
    }
    private TicketActor actor(long id) {
        var roles = users.roles(id);
        return new TicketActor(id, roles.contains(Role.ADMIN), roles.contains(Role.LEADER), roles.contains(Role.AGENT));
    }
    private record Fixture(long admin, long leader, long owner, long agent, long category) { }
    private Fixture fixture(boolean escalate) {
        long admin = user(Role.ADMIN), leader = user(Role.LEADER), owner = user(Role.USER), agent = user(Role.AGENT);
        long group = support.saveGroup(null, new GroupInput(unique(), leader, true, 0), admin).id();
        long category = support.saveCategory(null, new CategoryInput(unique(), "SLA测试", group, true, 0), admin).id();
        support.savePolicy(null, new PolicyInput(category, Priority.HIGH, 30, 60, escalate, true, 0), admin);
        support.saveAgent(null, new AgentInput(agent, group, true, 0), admin);
        support.setOnline(agent, new OnlineInput(true, 0));
        return new Fixture(admin, leader, owner, agent, category);
    }
    private long ticket(Fixture f) {
        return tickets.create(new TicketInput("时限测试", "请求处理", f.category(), Priority.HIGH, Set.of()), actor(f.owner())).ticket().id();
    }
    private void processing(long id, Fixture f) {
        workflow.assign(id, new AssignInput(f.agent(), data.find(id).version(), "分配"), actor(f.admin()));
        workflow.transition(id, TicketStatus.ASSIGNED, TicketStatus.PROCESSING,
                new ActionInput(data.find(id).version(), "接单"), actor(f.agent()));
    }
    private Ticket resolve(long id, Fixture f) {
        return workflow.transition(id, TicketStatus.PROCESSING, TicketStatus.RESOLVED,
                new ActionInput(data.find(id).version(), "处理完成"), actor(f.agent()));
    }
    private int count(long id, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sla_event WHERE ticket_id=? AND type=?", Integer.class, id, type);
    }
    private String bearer(long id) {
        return "Bearer " + auth.login(new AuthController.LoginRequest(users.findById(id).username(), "Strong_pass_123")).accessToken();
    }

    @Test
    void warningWindowDeadlineBoundaryAndRepeatedScanAreExact() {
        Fixture f = fixture(true);
        long id = ticket(f);
        Instant deadline = data.find(id).responseDeadline();
        at(deadline.minusSeconds(301));
        assertFalse(sla.inspect(id, true));
        at(deadline.minusSeconds(300));
        assertTrue(sla.inspect(id, true));
        assertFalse(data.find(id).responseBreached());
        assertEquals(1, data.find(id).escalationLevel());
        at(deadline);
        assertFalse(sla.inspect(id, true));
        at(deadline.plusNanos(1000));
        assertTrue(sla.inspect(id, true));
        assertFalse(sla.inspect(id, true));
        assertTrue(data.find(id).responseBreached());
        assertEquals(TicketStatus.CREATED, data.find(id).status());
        assertEquals(1, count(id, "RESPONSE_WARNING"));
        assertEquals(1, count(id, "RESPONSE_BREACHED"));
        assertEquals(1, data.histories(id, 0, 100).size());
        for (var event : records.events(id, 0, 100)) {
            String payload = jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_id=?", String.class, event.eventId());
            processor.process(payload);
            processor.process(payload);
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id=?", Integer.class, event.eventId()));
        }
    }

    @Test
    void disabledEscalationStillRecordsBreachWithoutNotifyingLeader() {
        Fixture f = fixture(false);
        long id = ticket(f);
        at(data.find(id).responseDeadline().plusSeconds(1));
        assertTrue(sla.inspect(id, true));
        assertTrue(data.find(id).responseBreached());
        assertEquals(0, data.find(id).escalationLevel());
        var event = records.events(id, 0, 10).getFirst();
        processor.process(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_id=?", String.class, event.eventId()));
        assertEquals(List.of(f.owner()), jdbc.queryForList("SELECT recipient_id FROM notification WHERE event_id=?", Long.class, event.eventId()));
    }

    @Test
    void lateCompletionBetweenScansIsRecordedAndReopenPreservesCycles() {
        Fixture f = fixture(true);
        long id = ticket(f);
        processing(id, f);
        at(data.find(id).resolveDeadline().plusSeconds(1));
        Ticket resolved = resolve(id, f);
        assertTrue(resolved.responseBreached());
        assertTrue(resolved.resolveBreached());
        assertEquals(1, count(id, "RESPONSE_BREACHED"));
        assertEquals(1, count(id, "RESOLVE_BREACHED"));
        workflow.transition(id, TicketStatus.RESOLVED, TicketStatus.CLOSED,
                new ActionInput(resolved.version(), "用户确认"), actor(f.agent()));
        assertFalse(sla.inspect(id, false));
        var reopened = workflow.reopen(id, new ActionInput(data.find(id).version(), "问题复现"), actor(f.leader()));
        assertEquals(2, reopened.slaCycle());
        assertTrue(reopened.resolveBreached());
        at(reopened.resolveDeadline().plusSeconds(1));
        assertTrue(sla.inspect(id, false));
        resolve(id, f);
        assertEquals(1, count(id, "RESPONSE_BREACHED"));
        assertEquals(2, count(id, "RESOLVE_BREACHED"));
    }

    @Test
    void completionAtDeadlineIsOnTimeAndTerminalTicketsAreSkipped() {
        Fixture f = fixture(true);
        long id = ticket(f), cancelled = ticket(f);
        tickets.cancel(cancelled, new ActionInput(0, "取消"), actor(f.owner()));
        processing(id, f);
        at(data.find(id).responseDeadline());
        tickets.comment(id, new CommentInput("已开始排查", false, data.find(id).version()), actor(f.agent()));
        at(data.find(id).resolveDeadline());
        var resolved = resolve(id, f);
        workflow.transition(id, TicketStatus.RESOLVED, TicketStatus.CLOSED,
                new ActionInput(resolved.version(), "确认"), actor(f.agent()));
        at(START.plusSeconds(10000));
        assertFalse(sla.inspect(id, true));
        assertFalse(sla.inspect(id, false));
        assertFalse(sla.inspect(cancelled, true));
        assertFalse(data.find(id).responseBreached());
        assertFalse(data.find(id).resolveBreached());
        assertTrue(records.events(id, 0, 10).isEmpty());
    }

    @Test
    void boundedCursorScansAllPagesAndPendingDoesNotPauseResolution() {
        Fixture f = fixture(true);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) ids.add(ticket(f));
        long pending = ids.getFirst();
        processing(pending, f);
        workflow.transition(pending, TicketStatus.PROCESSING, TicketStatus.PENDING,
                new ActionInput(data.find(pending).version(), "请补充日志"), actor(f.agent()));
        at(START.plusSeconds(3601));
        var first = new SlaJob(records, sla, clock, 2, true);
        var second = new SlaJob(records, sla, clock, 2, true);
        for (int i = 0; i < 100 && ids.stream().anyMatch(id -> !data.find(id).resolveBreached()); i++) {
            first.scan(); second.scan();
        }
        for (long id : ids) {
            assertEquals(1, count(id, "RESOLVE_BREACHED"));
            assertEquals(id == pending ? 0 : 1, count(id, "RESPONSE_BREACHED"));
        }
        assertEquals(TicketStatus.PENDING, data.find(pending).status());
    }

    @Test
    void concurrentScannersCreateOneEventAndResolveRaceDoesNotLoseBreach() throws Exception {
        Fixture f = fixture(true);
        long id = ticket(f);
        processing(id, f);
        at(START.plusSeconds(3601));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 7; i++) work.add(executor.submit(() -> { waitFor(start); sla.inspect(id, false); }));
            work.add(executor.submit(() -> {
                waitFor(start);
                try { resolve(id, f); }
                catch (BusinessException ex) { assertEquals("VERSION_CONFLICT", ex.getCode()); }
            }));
            start.countDown();
            for (var result : work) result.get(20, TimeUnit.SECONDS);
        }
        if (data.find(id).status() == TicketStatus.PROCESSING) resolve(id, f);
        assertEquals(TicketStatus.RESOLVED, data.find(id).status());
        assertEquals(1, count(id, "RESOLVE_BREACHED"));
        assertEquals(1, count(id, "RESPONSE_BREACHED"));
    }

    private void waitFor(CountDownLatch start) {
        try { assertTrue(start.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
    }

    @Test
    void outboxFailureRollsBackSlaFlagsAndCompletionTogether() {
        Fixture f = fixture(true);
        long id = ticket(f);
        processing(id, f);
        long version = data.find(id).version();
        at(START.plusSeconds(3601));
        doThrow(new IllegalStateException("模拟Outbox故障")).when(outbox)
                .insert(anyString(), eq(id), anyLong(), eq("TicketSlaBreachedEvent"), anyString(), any());
        assertThrows(IllegalStateException.class, () -> sla.inspect(id, false));
        assertThrows(IllegalStateException.class, () -> resolve(id, f));
        assertEquals(version, data.find(id).version());
        assertEquals(TicketStatus.PROCESSING, data.find(id).status());
        assertFalse(data.find(id).resolveBreached());
        assertNull(data.find(id).resolvedAt());
        assertTrue(records.events(id, 0, 100).isEmpty());
        assertTrue(data.comments(id, true, 0, 100).isEmpty());
        reset(outbox);
        resolve(id, f);
        assertTrue(data.find(id).resolveBreached());
    }

    @Test
    void slaHistoryUsesTicketOwnershipAndValidatesPagination() throws Exception {
        Fixture f = fixture(true);
        long id = ticket(f), other = user(Role.USER);
        at(START.plusSeconds(1801));
        sla.inspect(id, true);
        mvc.perform(get("/api/tickets/" + id + "/sla-events").header("Authorization", bearer(f.owner())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].type").value("RESPONSE_BREACHED"));
        mvc.perform(get("/api/tickets/" + id + "/sla-events").header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/tickets/" + id + "/sla-events?limit=1001").header("Authorization", bearer(f.leader())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tickets/escalations").header("Authorization", bearer(f.owner())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/tickets/escalations").header("Authorization", bearer(f.leader())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(id));
        long otherLeader = user(Role.LEADER);
        mvc.perform(get("/api/tickets/escalations").header("Authorization", bearer(otherLeader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }
}
