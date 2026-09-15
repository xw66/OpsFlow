package io.github.xw66.opsflow.support;

import io.github.xw66.opsflow.MySqlTestBase;
import io.github.xw66.opsflow.auth.*;
import io.github.xw66.opsflow.support.SupportModels.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SupportIntegrationTest extends MySqlTestBase {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired UserMapper users;
    @Autowired SupportMapper data;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String unique() { return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(); }
    private long user(Role role) {
        long id = auth.register(new AuthController.RegisterRequest(unique(), "Strong_pass_123", "测试人员")).id();
        if (role != Role.USER) users.addRole(id, role);
        return id;
    }
    private String bearer(long id) {
        return "Bearer " + auth.login(new AuthController.LoginRequest(users.findById(id).username(), "Strong_pass_123")).accessToken();
    }
    private ResultActions create(String path, Object input, String token) throws Exception {
        return mvc.perform(post(path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)));
    }
    private ResultActions update(String path, Object input, String token) throws Exception {
        return mvc.perform(put(path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)));
    }
    private long createdId(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();
    }
    private long group(String admin, long leader) throws Exception {
        return createdId(create("/api/admin/support-groups", new GroupInput(unique(), leader, true, 0), admin));
    }
    private long category(String admin, long group) throws Exception {
        return createdId(create("/api/admin/ticket-categories", new CategoryInput(unique(), "网络问题", group, true, 0), admin));
    }

    @Test
    void createsRoutedCategoryAndUniqueValidatedSlaPolicy() throws Exception {
        String admin = bearer(user(Role.ADMIN));
        long group = group(admin, user(Role.LEADER));
        long category = category(admin, group);
        var input = new PolicyInput(category, io.github.xw66.opsflow.ticket.Priority.HIGH, 30, 240, true, true, 0);
        long policy = createdId(create("/api/admin/sla-policies", input, admin));
        assertEquals(group, data.category(data.policy(policy).categoryId()).groupId());
        create("/api/admin/sla-policies", input, admin).andExpect(status().isConflict());
        create("/api/admin/sla-policies", new PolicyInput(category, io.github.xw66.opsflow.ticket.Priority.LOW, 60, 30, true, true, 0), admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SLA_LIMIT"));
        create("/api/admin/sla-policies", new PolicyInput(category, io.github.xw66.opsflow.ticket.Priority.LOW, 0, 30, true, true, 0), admin)
                .andExpect(status().isBadRequest());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type='SLA_POLICY' AND resource_id=?", Integer.class, policy));
        var constraint = assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "INSERT INTO sla_policy(category_id,priority,response_minutes,resolve_minutes) VALUES(?,'LOW',60,30)", category));
        assertEquals(3819, ((java.sql.SQLException) constraint.getRootCause()).getErrorCode());
    }

    @Test
    void optimisticConfigurationUpdateRejectsStaleVersion() throws Exception {
        String admin = bearer(user(Role.ADMIN));
        long leader = user(Role.LEADER);
        long group = group(admin, leader);
        GroupInput input = new GroupInput(unique(), leader, true, 0);
        update("/api/admin/support-groups/" + group, input, admin).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        update("/api/admin/support-groups/" + group, input, admin).andExpect(status().isConflict());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type='SUPPORT_GROUP' AND resource_id=?", Integer.class, group));
    }

    @Test
    void leaderCanReadOnlyOwnedGroupsAndOrdinaryUserCannotConfigure() throws Exception {
        String admin = bearer(user(Role.ADMIN));
        long leader = user(Role.LEADER);
        String token = bearer(leader);
        long own = group(admin, leader);
        long other = group(admin, user(Role.LEADER));
        mvc.perform(get("/api/support/groups").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(own));
        mvc.perform(get("/api/support/groups/" + own + "/agents").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get("/api/support/groups/" + other + "/agents").header("Authorization", token)).andExpect(status().isForbidden());
        create("/api/admin/support-groups", new GroupInput(unique(), leader, true, 0), bearer(user(Role.USER))).andExpect(status().isForbidden());
        create("/api/admin/support-groups", new GroupInput(unique(), leader, true, 0), token).andExpect(status().isForbidden());
    }

    @Test
    void agentCanSetOnlyOwnOnlineStateAndCannotBypassDisabledMembership() throws Exception {
        String admin = bearer(user(Role.ADMIN));
        long group = group(admin, user(Role.LEADER));
        long agent = user(Role.AGENT);
        long membership = createdId(create("/api/admin/support-agents", new AgentInput(agent, group, true, 0), admin));
        String token = bearer(agent);
        mvc.perform(get("/api/support/agents/me").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(membership)).andExpect(jsonPath("$.data.version").value(0));
        update("/api/support/agents/me/online", new OnlineInput(true, 0), token).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.online").value(true));
        update("/api/support/agents/me/online", new OnlineInput(false, 0), token).andExpect(status().isConflict());
        create("/api/admin/support-agents", new AgentInput(agent, group, true, 0), admin).andExpect(status().isConflict());
        update("/api/admin/support-agents/" + membership, new AgentInput(agent, group, false, 1), admin).andExpect(status().isOk());
        update("/api/support/agents/me/online", new OnlineInput(true, 2), token).andExpect(status().isConflict());
        assertFalse(data.agent(membership).online());
        long replacement = user(Role.AGENT);
        update("/api/admin/support-agents/" + membership, new AgentInput(replacement, group, true, 2), admin).andExpect(status().isConflict());
        update("/api/admin/support-agents/" + membership, new AgentInput(agent, group, true, 2), admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(3));
        update("/api/support/agents/me/online", new OnlineInput(true, 3), token).andExpect(status().isOk());
        update("/api/support/agents/me/online", new OnlineInput(true, 0), bearer(user(Role.USER))).andExpect(status().isForbidden());
    }

    @Test
    void memberListPaginatesWithinTheAuthorizedGroup() throws Exception {
        String admin = bearer(user(Role.ADMIN));
        long group = group(admin, user(Role.LEADER));
        long first = createdId(create("/api/admin/support-agents", new AgentInput(user(Role.AGENT), group, true, 0), admin));
        long second = createdId(create("/api/admin/support-agents", new AgentInput(user(Role.AGENT), group, true, 0), admin));
        assertNotEquals(first, second);
        mvc.perform(get("/api/support/groups/" + group + "/agents?offset=1&limit=1").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(second));
    }

    @Test
    void disabledGroupBlocksNewRoutingButAllowsDisablingChildren() throws Exception {
        String admin = bearer(user(Role.ADMIN));
        long leader = user(Role.LEADER);
        long group = group(admin, leader);
        long category = category(admin, group);
        update("/api/admin/support-groups/" + group, new GroupInput(unique(), leader, false, 0), admin).andExpect(status().isOk());
        create("/api/admin/ticket-categories", new CategoryInput(unique(), "网络问题", group, true, 0), admin).andExpect(status().isConflict());
        Category before = data.category(category);
        update("/api/admin/ticket-categories/" + category, new CategoryInput(before.code(), before.name(), group, false, 0), admin).andExpect(status().isOk());
        mvc.perform(get("/api/support/categories?limit=100").header("Authorization", bearer(user(Role.USER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.id == " + category + ")]").isEmpty());
    }

    @Test
    void rejectsInvalidRoleAssignmentsAndUnboundedLists() throws Exception {
        String admin = bearer(user(Role.ADMIN));
        create("/api/admin/support-groups", new GroupInput(unique(), user(Role.USER), true, 0), admin).andExpect(status().isConflict());
        long group = group(admin, user(Role.LEADER));
        create("/api/admin/support-agents", new AgentInput(user(Role.USER), group, true, 0), admin).andExpect(status().isConflict());
        mvc.perform(get("/api/admin/sla-policies?limit=1000").header("Authorization", admin)).andExpect(status().isBadRequest());
    }
}
