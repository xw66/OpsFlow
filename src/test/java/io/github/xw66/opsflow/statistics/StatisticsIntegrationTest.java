package io.github.xw66.opsflow.statistics;

import io.github.xw66.opsflow.MySqlTestBase;
import io.github.xw66.opsflow.auth.*;
import io.github.xw66.opsflow.common.BusinessException;
import io.github.xw66.opsflow.event.EventProcessor;
import io.github.xw66.opsflow.support.*;
import io.github.xw66.opsflow.support.SupportModels.*;
import io.github.xw66.opsflow.ticket.*;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
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
class StatisticsIntegrationTest extends MySqlTestBase {
    private static final LocalDate DAY = LocalDate.of(2033, 9, 10);
    private static final Instant START = DAY.atStartOfDay(StatisticsService.ZONE).toInstant();
    @MockitoBean Clock clock;
    @Autowired AuthService auth;
    @Autowired UserMapper users;
    @Autowired SupportService support;
    @Autowired TicketService tickets;
    @Autowired WorkflowService workflow;
    @Autowired StatisticsService statistics;
    @Autowired StatisticsRefresh refresh;
    @Autowired EventProcessor consumer;
    @MockitoSpyBean StatisticsMapper data;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @BeforeEach
    void time() { when(clock.instant()).thenReturn(START); when(clock.getZone()).thenReturn(ZoneOffset.UTC); }
    private void at(Instant instant) { when(clock.instant()).thenReturn(instant); }
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
    private record Fixture(long admin, long leader, long owner, long agent, long group, long category) { }
    private Fixture fixture() {
        long admin = user(Role.ADMIN), leader = user(Role.LEADER), owner = user(Role.USER), agent = user(Role.AGENT);
        long group = support.saveGroup(null, new GroupInput(key(), leader, true, 0), admin).id();
        long category = support.saveCategory(null, new CategoryInput(key(), "统计测试", group, true, 0), admin).id();
        support.savePolicy(null, new PolicyInput(category, Priority.HIGH, 30, 240, true, true, 0), admin);
        support.saveAgent(null, new AgentInput(agent, group, true, 0), admin);
        support.setOnline(agent, new OnlineInput(true, 0));
        return new Fixture(admin, leader, owner, agent, group, category);
    }
    private long ticket(Fixture f, Instant time) {
        at(time);
        return tickets.create(new TicketInput("统计样本", "问题描述", f.category(), Priority.HIGH, Set.of()), actor(f.owner())).ticket().id();
    }
    private StatisticsService.Overview overview(Fixture f) { return statistics.overview(actor(f.leader()), DAY, DAY.plusDays(1)); }
    private void decimal(String expected, BigDecimal actual) { assertNotNull(actual); assertEquals(0, new BigDecimal(expected).compareTo(actual)); }
    private void drain() { for (int i = 0; i < 100 && refresh.refreshOne(); i++) { } }
    private String event(long id, String type) {
        return jdbc.queryForObject("SELECT payload FROM outbox_event WHERE aggregate_id=? AND event_type=? ORDER BY aggregate_version DESC LIMIT 1", String.class, id, type);
    }
    private String bearer(long id) { return "Bearer " + auth.login(new AuthController.LoginRequest(users.findById(id).username(), "Strong_pass_123")).accessToken(); }

    @Test
    void cohortBoundariesAveragesSlaAndCurrentWorkloadHaveExplicitSamples() {
        Fixture f = fixture();
        long previous = ticket(f, START.minusNanos(1000));
        long first = ticket(f, START), late = ticket(f, START.plusSeconds(3600));
        long cancelled = ticket(f, START.plusSeconds(7200));
        long active = ticket(f, START.plusSeconds(86400).minusNanos(1000));
        ticket(f, START.plusSeconds(86400));
        jdbc.update("UPDATE ticket SET status='RESOLVED',first_response_at=DATE_ADD(created_at,INTERVAL 60 SECOND),resolved_at=DATE_ADD(created_at,INTERVAL 600 SECOND) WHERE id=?", first);
        jdbc.update("UPDATE ticket SET status='CLOSED',first_response_at=DATE_ADD(created_at,INTERVAL 120 SECOND),resolved_at=DATE_ADD(created_at,INTERVAL 1200 SECOND),resolve_deadline=DATE_ADD(created_at,INTERVAL 900 SECOND),resolve_breached=TRUE WHERE id=?", late);
        jdbc.update("UPDATE ticket SET status='CANCELLED',first_response_at=DATE_ADD(created_at,INTERVAL 300 SECOND) WHERE id=?", cancelled);
        for (long id : List.of(previous, active)) jdbc.update("UPDATE ticket SET status='PROCESSING',assignee_id=? WHERE id=?", f.agent(), id);
        at(START.plusSeconds(86400));
        var result = overview(f);
        assertEquals(4, result.totals().createdCount());
        assertEquals(2, result.totals().responseSamples());
        assertEquals(2, result.totals().resolvedSamples());
        decimal("90", result.totals().averageResponseSeconds());
        decimal("900", result.totals().averageResolveSeconds());
        decimal("0.5", result.slaAchievementRate());
        assertEquals(1, result.totals().overdueCount());
        assertEquals(4, result.categories().getFirst().ticketCount());
        assertEquals(2, result.agents().getFirst().processingCount());
        assertEquals(3, result.groups().getFirst().activeCount());
        assertEquals(1, result.groups().getFirst().unassignedCount());
    }

    @Test
    void reopenedTicketLeavesResolvedDenominatorAndUsesLatestCycleAfterResolution() {
        Fixture f = fixture();
        long id = ticket(f, START);
        workflow.assign(id, new AssignInput(f.agent(), 0, "分配"), actor(f.admin()));
        workflow.transition(id, TicketStatus.ASSIGNED, TicketStatus.PROCESSING, new ActionInput(1, "接单"), actor(f.agent()));
        at(START.plusSeconds(60));
        workflow.transition(id, TicketStatus.PROCESSING, TicketStatus.RESOLVED, new ActionInput(2, "解决"), actor(f.agent()));
        decimal("60", overview(f).totals().averageResolveSeconds());
        at(START.plusSeconds(120));
        workflow.reopen(id, new ActionInput(3, "复现"), actor(f.leader()));
        assertEquals(0, overview(f).totals().resolvedSamples());
        assertNull(overview(f).slaAchievementRate());
        at(START.plusSeconds(150));
        workflow.transition(id, TicketStatus.PROCESSING, TicketStatus.RESOLVED, new ActionInput(4, "再次解决"), actor(f.agent()));
        decimal("30", overview(f).totals().averageResolveSeconds());
        decimal("60", overview(f).totals().averageResponseSeconds());
    }

    @Test
    void emptySamplesStayNullAndAiRateExcludesFailures() {
        Fixture f = fixture();
        var empty = overview(f);
        assertEquals(0, empty.totals().createdCount());
        assertNull(empty.totals().averageResponseSeconds());
        assertNull(empty.slaAchievementRate());
        assertNull(empty.aiClassificationAcceptanceRate());
        for (String status : List.of("ACCEPTED", "SUCCEEDED", "FAILED", "DISABLED")) {
            long id = ticket(f, START);
            jdbc.update("INSERT INTO ticket_ai_analysis(ticket_id,ticket_version,kind,status,input_text,categories_json,accepted_by,accepted_at,created_at) VALUES(?,0,'CLASSIFICATION',?,'测试输入','[]',?,?,?)",
                    id, status, status.equals("ACCEPTED") ? f.leader() : null, status.equals("ACCEPTED") ? START : null, START);
        }
        var result = overview(f);
        assertEquals(2, result.ai().successfulCount());
        assertEquals(1, result.ai().acceptedCount());
        decimal("0.5", result.aiClassificationAcceptanceRate());
        assertThrows(BusinessException.class, () -> statistics.overview(actor(f.owner()), DAY, DAY.plusDays(1)));
    }

    @Test
    void dailyCountsUseShanghaiDatesAndDistinguishPendingFromZero() {
        Fixture f = fixture();
        ticket(f, START.minusNanos(1000));
        ticket(f, START); ticket(f, START.plusSeconds(86400));
        var pending = statistics.daily(actor(f.leader()), DAY.minusDays(1), DAY.plusDays(3));
        var last = pending.getLast();
        if (last.refreshedAt() == null) assertNull(last.createdCount());
        for (int i = -1; i < 3; i++) data.requestDay(DAY.plusDays(i));
        drain();
        var rows = statistics.daily(actor(f.leader()), DAY.minusDays(1), DAY.plusDays(3));
        assertEquals(List.of(1L, 1L, 1L, 0L), rows.stream().map(StatisticsMapper.DailyCount::createdCount).toList());
        assertTrue(rows.stream().allMatch(row -> row.refreshedAt() != null && !row.requested()));
    }

    @Test
    void editingHistoricalCategoryRefreshesOldDayAndRollbackPreservesOwnership() {
        Fixture source = fixture(), target = fixture();
        long id = ticket(source, START);
        data.requestDay(DAY); drain();
        at(START.plusSeconds(86400 * 10));
        var input = new EditInput(new TicketInput("历史工单分类修改", "跨组更新日报", target.category(), Priority.HIGH, Set.of()), 0);
        doThrow(new IllegalStateException("模拟日报置脏失败")).when(data).requestDay(DAY);
        assertThrows(IllegalStateException.class, () -> tickets.edit(id, input, actor(source.owner())));
        assertEquals(source.group(), tickets.read(id, actor(source.owner())).groupId());
        assertEquals(0, tickets.read(id, actor(source.owner())).version());
        reset(data);
        tickets.edit(id, input, actor(source.owner()));
        assertTrue(statistics.daily(actor(source.leader()), DAY, DAY.plusDays(1)).getFirst().requested());
        drain();
        assertEquals(0L, statistics.daily(actor(source.leader()), DAY, DAY.plusDays(1)).getFirst().createdCount());
        assertEquals(1L, statistics.daily(actor(target.leader()), DAY, DAY.plusDays(1)).getFirst().createdCount());
    }

    @Test
    void duplicateKafkaEventDoesNotDirtyOrIncrementCompletedStatisticsAndTransferRefreshesGroups() {
        Fixture source = fixture(), target = fixture();
        long id = ticket(source, START);
        String created = event(id, "TicketCreatedEvent");
        consumer.process(created); drain();
        assertEquals(1L, statistics.daily(actor(source.leader()), DAY, DAY.plusDays(1)).getFirst().createdCount());
        consumer.process(created);
        assertFalse(statistics.daily(actor(source.leader()), DAY, DAY.plusDays(1)).getFirst().requested());
        workflow.assign(id, new AssignInput(source.agent(), 0, "分配"), actor(source.admin()));
        workflow.transfer(id, new TransferInput(target.agent(), 1, "跨组转派"), actor(source.admin()));
        String transferred = event(id, "TicketAssignedEvent");
        consumer.process(transferred); drain();
        assertEquals(0L, statistics.daily(actor(source.leader()), DAY, DAY.plusDays(1)).getFirst().createdCount());
        assertEquals(1L, statistics.daily(actor(target.leader()), DAY, DAY.plusDays(1)).getFirst().createdCount());
        consumer.process(transferred); drain();
        assertEquals(1L, statistics.daily(actor(target.leader()), DAY, DAY.plusDays(1)).getFirst().createdCount());
    }

    @Test
    void failedRefreshPreservesOldSnapshotAndBoundedJobProcessesBacklog() {
        Fixture f = fixture();
        ticket(f, START);
        data.requestDay(DAY); drain();
        ticket(f, START.plusSeconds(1));
        data.requestDay(DAY);
        doThrow(new IllegalStateException("模拟聚合失败")).when(data).rebuild(eq(DAY), any(), any());
        assertThrows(IllegalStateException.class, () -> refresh.refreshOne());
        assertEquals(1L, statistics.daily(actor(f.leader()), DAY, DAY.plusDays(1)).getFirst().createdCount());
        reset(data); drain();
        assertEquals(2L, statistics.daily(actor(f.leader()), DAY, DAY.plusDays(1)).getFirst().createdCount());
        LocalDate old = LocalDate.of(2005, 1, 1);
        for (int i = 0; i < 7; i++) data.requestDay(old.plusDays(i));
        var job = new StatisticsJob(data, refresh, clock, true);
        job.scan();
        assertEquals(5, data.daily(actor(f.leader()), old, old.plusDays(7)).stream().filter(row -> row.refreshedAt() != null).count());
        job.scan();
        assertEquals(7, data.daily(actor(f.leader()), old, old.plusDays(7)).stream().filter(row -> row.refreshedAt() != null).count());
    }

    @Test
    void httpRoleAndRangeChecksPreventLeakingOtherGroups() throws Exception {
        Fixture f = fixture(), other = fixture();
        ticket(f, START); ticket(other, START);
        String route = "/api/statistics/overview?from=2033-09-10&until=2033-09-11";
        mvc.perform(get(route).header("Authorization", bearer(f.owner()))).andExpect(status().isForbidden());
        mvc.perform(get(route).header("Authorization", bearer(f.leader()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totals.createdCount").value(1));
        assertTrue(statistics.overview(actor(f.admin()), DAY, DAY.plusDays(1)).totals().createdCount() >= 2);
        mvc.perform(get("/api/statistics/overview?from=2033-09-11&until=2033-09-10").header("Authorization", bearer(f.leader())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/statistics/daily?from=2033-01-01&until=2034-01-01").header("Authorization", bearer(f.leader())))
                .andExpect(status().isBadRequest());
    }
}
