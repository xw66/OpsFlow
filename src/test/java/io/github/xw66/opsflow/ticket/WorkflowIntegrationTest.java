package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.MySqlTestBase;
import io.github.xw66.opsflow.auth.*;
import io.github.xw66.opsflow.common.BusinessException;
import io.github.xw66.opsflow.event.OutboxMapper;
import io.github.xw66.opsflow.support.*;
import io.github.xw66.opsflow.support.SupportModels.*;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowIntegrationTest extends MySqlTestBase {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired UserMapper users;
    @Autowired SupportService support;
    @Autowired TicketService tickets;
    @Autowired TicketMapper data;
    @Autowired WorkflowMapper workflow;
    @Autowired WorkflowService service;
    @MockitoSpyBean OutboxMapper outbox;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

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
    private String bearer(long id) { return "Bearer " + auth.login(new AuthController.LoginRequest(users.findById(id).username(), "Strong_pass_123")).accessToken(); }
    private record Fixture(long admin, long leader, long owner, long group, long category) { }
    private Fixture fixture() {
        long admin = user(Role.ADMIN), leader = user(Role.LEADER), owner = user(Role.USER);
        long group = support.saveGroup(null, new GroupInput(unique(), leader, true, 0), admin).id();
        long category = support.saveCategory(null, new CategoryInput(unique(), "服务故障", group, true, 0), admin).id();
        support.savePolicy(null, new PolicyInput(category, Priority.HIGH, 30, 240, true, true, 0), admin);
        return new Fixture(admin, leader, owner, group, category);
    }
    private long agent(Fixture f, boolean online) {
        long id = user(Role.AGENT);
        support.saveAgent(null, new AgentInput(id, f.group(), true, 0), f.admin());
        if (online) support.setOnline(id, new OnlineInput(true, 0));
        return id;
    }
    private long ticket(Fixture f) {
        return tickets.create(new TicketInput("服务故障", "请尽快协助处理", f.category(), Priority.HIGH, Set.of()), actor(f.owner())).ticket().id();
    }
    private ResultActions action(long id, String action, Object input, long actor) throws Exception {
        return mvc.perform(post("/api/tickets/" + id + "/" + action).header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)));
    }

    @Test
    void completeWorkflowPersistsHistoryAndReopensNewResolutionCycle() throws Exception {
        Fixture f = fixture();
        long agent = agent(f, true), id = ticket(f);
        action(id, "assign", new AssignInput(agent, 0, "人工分配"), f.leader()).andExpect(status().isOk());
        assertNull(data.find(id).firstResponseAt());
        action(id, "resume", new ActionInput(1, "尚未挂起"), agent).andExpect(status().isConflict());
        action(id, "accept", new ActionInput(1, "接单"), agent).andExpect(status().isOk());
        assertNull(data.find(id).firstResponseAt());
        action(id, "suspend", new ActionInput(2, "请补充错误截图"), agent).andExpect(status().isOk());
        Instant firstResponse = data.find(id).firstResponseAt();
        assertNotNull(firstResponse);
        action(id, "accept", new ActionInput(3, "已经接过单"), agent).andExpect(status().isConflict());
        action(id, "resume", new ActionInput(3, "已收到截图"), agent).andExpect(status().isOk());
        action(id, "resolve", new ActionInput(4, "重启服务后恢复，请确认"), agent).andExpect(status().isOk());
        assertNotNull(data.find(id).resolvedAt());
        action(id, "close", new ActionInput(5, "确认问题解决"), agent).andExpect(status().isOk());
        assertNotNull(data.find(id).closedAt());
        action(id, "reopen", new ActionInput(6, "问题复现，重新排查"), f.leader()).andExpect(status().isOk());
        Ticket reopened = data.find(id);
        assertEquals(TicketStatus.PROCESSING, reopened.status());
        assertEquals(2, reopened.slaCycle());
        assertEquals(firstResponse, reopened.firstResponseAt());
        assertNull(reopened.closedAt());
        assertNull(reopened.resolvedAt());
        assertEquals(reopened.cycleStartedAt().plusSeconds(14400), reopened.resolveDeadline());
        assertEquals(List.of(TicketStatus.CREATED, TicketStatus.ASSIGNED, TicketStatus.PROCESSING, TicketStatus.PENDING,
                TicketStatus.PROCESSING, TicketStatus.RESOLVED, TicketStatus.CLOSED, TicketStatus.PROCESSING),
                data.histories(id, 0, 100).stream().map(History::toStatus).toList());
        assertEquals(2, data.comments(id, false, 0, 100).size());
        assertEquals(1, workflow.assignments(id, 0, 20).size());
        var assignment = workflow.assignments(id, 0, 20).getFirst();
        assertNull(assignment.fromAssigneeName());
        assertEquals("测试人员", assignment.toAssigneeName());
        assertEquals("测试人员", assignment.operatorName());
        assertNotNull(assignment.fromGroupName());
        assertEquals(assignment.fromGroupName(), assignment.toGroupName());
        mvc.perform(get("/api/tickets/"+id+"/assignments").header("Authorization",bearer(f.owner())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].toAssigneeName").value("测试人员"));
        mvc.perform(get("/api/tickets/"+id+"/history").header("Authorization",bearer(f.owner())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].operatorName").value("测试人员"));
    }

    @Test
    void concurrentAssignmentAndAcceptanceEachHaveOneWinner() throws Exception {
        Fixture f = fixture();
        long agent = agent(f, true), id = ticket(f);
        assertEquals(1, concurrent(8, i -> () -> {
            try { service.assign(id, new AssignInput(agent, 0, "并发分配"), actor(f.admin())); return true; }
            catch (BusinessException ex) { assertEquals(409, ex.getStatus().value()); return false; }
        }));
        assertEquals(1, concurrent(8, i -> () -> {
            try { service.transition(id, TicketStatus.ASSIGNED, TicketStatus.PROCESSING, new ActionInput(1, "并发接单"), actor(agent)); return true; }
            catch (BusinessException ex) { assertEquals(409, ex.getStatus().value()); return false; }
        }));
        assertEquals(2, data.find(id).version());
        assertEquals(1, workflow.assignments(id, 0, 20).size());
        assertEquals(3, data.histories(id, 0, 20).size());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", Integer.class, id));
    }

    @Test
    void parallelAutoAssignmentCountsUnacceptedWorkAndBalancesLoad() throws Exception {
        Fixture f = fixture();
        long first = agent(f, true), second = agent(f, true);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) ids.add(ticket(f));
        assertEquals(12, concurrent(12, i -> () -> service.autoAssign(ids.get(i))));
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE assignee_id=? AND status='ASSIGNED'", Integer.class, first));
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE assignee_id=? AND status='ASSIGNED'", Integer.class, second));
        for (long id : ids) {
            assertFalse(service.autoAssign(id));
            assertNull(workflow.assignments(id, 0, 20).getFirst().operatorId());
        }
    }

    @Test
    void allocatorExcludesUnavailableAgentsAndUsesOldestAssignmentTieBreak() {
        Fixture f = fixture();
        long offline = agent(f, false), disabled = agent(f, true), noRole = agent(f, true), paused = agent(f, true);
        users.updateEnabled(disabled, false);
        users.deleteRoles(noRole);
        jdbc.update("UPDATE support_agent SET enabled=FALSE WHERE user_id=?", paused);
        long first = agent(f, true), second = agent(f, true);
        jdbc.update("UPDATE support_agent SET last_assigned_at='2026-01-02 00:00:00' WHERE user_id=?", first);
        jdbc.update("UPDATE support_agent SET last_assigned_at='2026-01-01 00:00:00' WHERE user_id=?", second);
        long one = ticket(f), two = ticket(f);
        assertTrue(service.autoAssign(one));
        assertEquals(second, data.find(one).assigneeId());
        assertTrue(service.autoAssign(two));
        assertEquals(first, data.find(two).assigneeId());
        assertNotEquals(offline, data.find(one).assigneeId());
    }

    @Test
    void transferPreservesStateAndChangesAccessWithoutForgedHistory() throws Exception {
        Fixture f = fixture();
        long first = agent(f, true), second = agent(f, true), id = ticket(f);
        service.assign(id, new AssignInput(first, 0, "分配"), actor(f.admin()));
        service.transition(id, TicketStatus.ASSIGNED, TicketStatus.PROCESSING, new ActionInput(1, "接单"), actor(first));
        action(id, "transfer", new TransferInput(second, 2, "同组协助"), first).andExpect(status().isOk());
        assertEquals(TicketStatus.PROCESSING, data.find(id).status());
        assertEquals(second, data.find(id).assigneeId());
        assertEquals(3, data.histories(id, 0, 20).size());
        assertEquals(2, workflow.assignments(id, 0, 20).size());
        mvc.perform(get("/api/tickets/" + id).header("Authorization", bearer(first))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tickets/" + id).header("Authorization", bearer(second))).andExpect(status().isOk());
        Fixture other = fixture();
        long target = agent(other, true);
        action(id, "transfer", new TransferInput(target, 3, "跨组转派"), second).andExpect(status().isForbidden());
        action(id, "transfer", new TransferInput(target, 3, "跨组转派"), f.leader()).andExpect(status().isForbidden());
        action(id, "transfer", new TransferInput(target, 3, "管理员跨组转派"), f.admin()).andExpect(status().isOk());
        assertEquals(other.group(), data.find(id).groupId());
        mvc.perform(get("/api/tickets/" + id).header("Authorization", bearer(f.leader()))).andExpect(status().isForbidden());
    }

    @Test
    void failureRollsBackAssignmentHistoryAndLastAssignedTime() {
        Fixture f = fixture();
        long agent = agent(f, true), id = ticket(f);
        doThrow(new IllegalStateException("模拟分配事件写入失败")).when(outbox).insert(anyString(), eq(id), anyLong(), anyString(), anyString(), any());
        assertThrows(IllegalStateException.class, () -> service.assign(id, new AssignInput(agent, 0, "失败重试"), actor(f.admin())));
        assertEquals(TicketStatus.CREATED, data.find(id).status());
        assertNull(data.find(id).assigneeId());
        assertEquals(0, workflow.assignments(id, 0, 20).size());
        assertEquals(1, data.histories(id, 0, 20).size());
        assertNull(jdbc.queryForObject("SELECT last_assigned_at FROM support_agent WHERE user_id=?", java.sql.Timestamp.class, agent));
    }

    @Test
    void emptyCandidateQueueAndCursorScanDoNotStarveLaterTickets() throws Exception {
        Fixture blocked = fixture();
        long blockedId = ticket(blocked);
        assertFalse(service.autoAssign(blockedId));
        action(blockedId, "assign", new AssignInput(null, 0, "尝试自动分配"), blocked.leader()).andExpect(status().isConflict());
        mvc.perform(get("/api/tickets/manual-queue").header("Authorization", bearer(blocked.leader())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(blockedId));
        Fixture available = fixture();
        agent(available, true);
        long availableId = ticket(available);
        AssignmentJob job = new AssignmentJob(workflow, service, 1);
        for (int i = 0; i < 200 && data.find(availableId).status() == TicketStatus.CREATED; i++) job.scan();
        assertEquals(TicketStatus.CREATED, data.find(blockedId).status());
        assertEquals(TicketStatus.ASSIGNED, data.find(availableId).status());
    }

    @Test
    void invalidStatusAndRoleActionsCannotProduceHistory() throws Exception {
        Fixture f = fixture();
        long agent = agent(f, true), id = ticket(f);
        action(id, "assign", new AssignInput(agent, 0, "普通用户分配"), f.owner()).andExpect(status().isForbidden());
        action(id, "close", new ActionInput(0, "跳过处理"), f.admin()).andExpect(status().isConflict());
        service.assign(id, new AssignInput(agent, 0, "分配"), actor(f.admin()));
        action(id, "accept", new ActionInput(1, "管理员代接"), f.admin()).andExpect(status().isForbidden());
        action(id, "reopen", new ActionInput(1, "非法重开"), f.owner()).andExpect(status().isForbidden());
        action(id, "reopen", new ActionInput(1, "非法重开"), f.leader()).andExpect(status().isConflict());
        action(id, "reopen", new ActionInput(1, " "), f.admin()).andExpect(status().isBadRequest());
        assertEquals(2, data.histories(id, 0, 20).size());
    }

    private int concurrent(int count, IntFunction<Callable<Boolean>> action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<Boolean> task = action.apply(i);
                futures.add(executor.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return task.call(); }));
            }
            start.countDown();
            int successes = 0;
            for (var result : futures) if (result.get(30, TimeUnit.SECONDS)) successes++;
            return successes;
        }
    }
}
