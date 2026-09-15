package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.MySqlTestBase;
import io.github.xw66.opsflow.auth.*;
import io.github.xw66.opsflow.support.*;
import io.github.xw66.opsflow.support.SupportModels.*;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TicketWorkspaceIntegrationTest extends MySqlTestBase {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired UserMapper users;
    @Autowired SupportService support;
    @Autowired TicketService tickets;
    @Autowired JdbcTemplate jdbc;

    private String unique() { return "W" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(); }
    private long user(Role role) {
        long id = auth.register(new AuthController.RegisterRequest(unique(), "Strong_pass_123", "工作台用户")).id();
        if (role != Role.USER) users.addRole(id, role);
        return id;
    }
    private String bearer(long id) {
        return "Bearer " + auth.login(new AuthController.LoginRequest(users.findById(id).username(), "Strong_pass_123")).accessToken();
    }
    private record Fixture(long admin, long leader, long owner, long agent, Group group, Category category, Policy policy) { }
    private Fixture fixture() {
        long admin=user(Role.ADMIN), leader=user(Role.LEADER), owner=user(Role.USER), agent=user(Role.AGENT);
        var group=support.saveGroup(null,new GroupInput(unique(),leader,true,0),admin);
        var category=support.saveCategory(null,new CategoryInput(unique(),"网络与连接",group.id(),true,0),admin);
        var policy=support.savePolicy(null,new PolicyInput(category.id(),Priority.HIGH,30,240,true,true,0),admin);
        return new Fixture(admin,leader,owner,agent,group,category,policy);
    }
    private long create(Fixture f,long owner,String title) {
        return tickets.create(new TicketInput(title,"工作台测试描述",f.category.id(),Priority.HIGH,Set.of()),
                new TicketActor(owner,false,false,false)).ticket().id();
    }
    private String path(Fixture f) { return "/api/workspace/tickets?categoryId="+f.category.id(); }
    private long agent(Fixture f, long groupId) {
        long id=user(Role.AGENT);
        support.saveAgent(null,new AgentInput(id,groupId,true,0),f.admin);
        support.setOnline(id,new OnlineInput(true,0));
        return id;
    }

    @Test
    void memberWorkloadIncludesIdleMembersAndKeepsGroupCountsPrivate() throws Exception {
        var f=fixture(); long busy=agent(f,f.group.id()),idle=agent(f,f.group.id());
        for (String state:List.of("ASSIGNED","PROCESSING","PENDING","RESOLVED")) {
            long id=create(f,f.owner,state);
            jdbc.update("UPDATE ticket SET assignee_id=?,status=? WHERE id=?",busy,state,id);
        }
        var other=fixture(); long foreign=create(other,other.owner,"其他组遗留工单");
        jdbc.update("UPDATE ticket SET assignee_id=?,status='PROCESSING' WHERE id=?",busy,foreign);
        String url="/api/support/groups/"+f.group.id()+"/workload";
        mvc.perform(get(url+"?limit=1").header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.items[0].displayName").value("工作台用户"))
                .andExpect(jsonPath("$.data.items[0].activeCount").value(3))
                .andExpect(jsonPath("$.data.items[0].processingCount").value(1))
                .andExpect(jsonPath("$.data.items[0].available").value(true));
        support.setOnline(idle,new OnlineInput(false,1));
        mvc.perform(get(url+"?offset=1&limit=1").header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.items[0].userId").value(idle))
                .andExpect(jsonPath("$.data.items[0].online").value(false))
                .andExpect(jsonPath("$.data.items[0].activeCount").value(0));
        jdbc.update("UPDATE app_user SET enabled=FALSE WHERE id=?",busy);
        mvc.perform(get(url).header("Authorization",bearer(f.admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].available").value(false));
        mvc.perform(get(url).header("Authorization",bearer(other.leader))).andExpect(status().isForbidden());
        mvc.perform(get(url).header("Authorization",bearer(f.owner))).andExpect(status().isForbidden());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url+"?limit=101").header("Authorization",bearer(f.leader))).andExpect(status().isBadRequest());
    }

    @Test
    void teamQueuesIntersectGroupPermissionAndExcludeCompletedEscalations() throws Exception {
        var f=fixture(); var other=fixture();
        long manual=create(f,f.owner,"待人工分配"),active=create(f,f.owner,"升级处理中");
        long closed=create(f,f.owner,"历史升级"),foreign=create(other,f.leader,"组长在其他组提交");
        jdbc.update("UPDATE ticket SET status='PROCESSING',assignee_id=?,escalation_level=1 WHERE id=?",f.agent,active);
        jdbc.update("UPDATE ticket SET status='CLOSED',escalation_level=1 WHERE id=?",closed);
        String url="/api/workspace/tickets?view=MY_GROUP&groupId="+f.group.id();
        mvc.perform(get(url+"&queue=MANUAL").header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(manual));
        mvc.perform(get(url+"&queue=ESCALATED").header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(active));
        mvc.perform(get(url+"&queue=ESCALATED&status=CLOSED").header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        String foreignUrl="/api/workspace/tickets?view=MY_GROUP&queue=MANUAL&groupId="+other.group.id();
        mvc.perform(get(foreignUrl).header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get(foreignUrl).header("Authorization",bearer(f.admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(foreign));
        mvc.perform(get(url).header("Authorization",bearer(f.owner))).andExpect(status().isForbidden());
        mvc.perform(get("/api/workspace/tickets?queue=MANUAL").header("Authorization",bearer(f.leader)))
                .andExpect(status().isForbidden());
        mvc.perform(get(url+"&queue=UNKNOWN").header("Authorization",bearer(f.leader))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/workspace/tickets?groupId=0").header("Authorization",bearer(f.leader))).andExpect(status().isBadRequest());
    }

    @Test
    void candidatesUseEligibleAgentsAndExplainableLoadOrder() throws Exception {
        var f=fixture(); long first=agent(f,f.group.id()),second=agent(f,f.group.id()),loaded=agent(f,f.group.id());
        long id=create(f,f.owner,"待分配"),active=create(f,f.owner,"占用负载");
        jdbc.update("UPDATE ticket SET assignee_id=?,status='PENDING' WHERE id=?",loaded,active);
        jdbc.update("UPDATE support_agent SET last_assigned_at=? WHERE user_id=?",Timestamp.from(Instant.now()),second);
        String url="/api/workspace/tickets/"+id+"/assignment-candidates?groupId="+f.group.id();
        mvc.perform(get(url+"&limit=1").header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].userId").value(first))
                .andExpect(jsonPath("$.data.items[0].displayName").value("工作台用户"))
                .andExpect(jsonPath("$.data.items[0].activeCount").value(0))
                .andExpect(jsonPath("$.data.items[0].online").value(true))
                .andExpect(jsonPath("$.data.hasMore").value(true)).andExpect(jsonPath("$.data.ticketVersion").value(0));
        mvc.perform(get(url+"&offset=1&limit=1").header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].userId").value(second));
        mvc.perform(get(url+"&offset=2&limit=1").header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].userId").value(loaded))
                .andExpect(jsonPath("$.data.items[0].activeCount").value(1)).andExpect(jsonPath("$.data.hasMore").value(false));
        for (String column:List.of("online","enabled")) {
            jdbc.update("UPDATE support_agent SET "+column+"=FALSE WHERE user_id=?",first);
            mvc.perform(get(url).param("keyword",users.findById(first).username()).header("Authorization",bearer(f.leader)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
            jdbc.update("UPDATE support_agent SET "+column+"=TRUE WHERE user_id=?",first);
        }
        jdbc.update("UPDATE app_user SET enabled=FALSE WHERE id=?",first);
        mvc.perform(get(url).param("keyword",users.findById(first).username()).header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        jdbc.update("UPDATE app_user SET enabled=TRUE WHERE id=?",first);
        jdbc.update("DELETE FROM user_role WHERE user_id=? AND role_id=(SELECT id FROM role WHERE code='AGENT')",first);
        mvc.perform(get(url).param("keyword",users.findById(first).username()).header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get(url).header("Authorization",bearer(f.owner))).andExpect(status().isForbidden());
        mvc.perform(get(url).header("Authorization",bearer(f.agent))).andExpect(status().isForbidden());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url+"&limit=101").header("Authorization",bearer(f.leader))).andExpect(status().isBadRequest());
        jdbc.update("UPDATE ticket SET status='CANCELLED' WHERE id=?",id);
        mvc.perform(get(url).header("Authorization",bearer(f.leader))).andExpect(status().isConflict());
    }

    @Test
    void crossGroupCandidatesRequireManagementOfBothGroupsAndNeverExpandCreatedAssignment() throws Exception {
        var f=fixture();
        var managed=support.saveGroup(null,new GroupInput(unique(),f.leader,true,0),f.admin);
        var unrelated=support.saveGroup(null,new GroupInput(unique(),user(Role.LEADER),true,0),f.admin);
        long source=agent(f,f.group.id()),sameGroup=agent(f,f.group.id()),target=agent(f,managed.id()),other=agent(f,unrelated.id());
        long id=create(f,f.owner,"分组候选");
        String url="/api/workspace/tickets/"+id+"/assignment-candidates";
        mvc.perform(get(url+"?groupId="+managed.id()).header("Authorization",bearer(f.admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        jdbc.update("UPDATE ticket SET assignee_id=?,status='ASSIGNED' WHERE id=?",source,id);
        mvc.perform(get(url+"?groupId="+f.group.id()).header("Authorization",bearer(source)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].userId").value(sameGroup));
        mvc.perform(get(url+"?groupId="+managed.id()).header("Authorization",bearer(source)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get(url+"?groupId="+managed.id()).header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].userId").value(target));
        mvc.perform(get(url+"?groupId="+unrelated.id()).header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get(url+"?groupId="+unrelated.id()).header("Authorization",bearer(f.admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].userId").value(other));
        users.addRole(source,Role.LEADER);
        jdbc.update("UPDATE support_group SET leader_id=? WHERE id=?",source,unrelated.id());
        mvc.perform(get(url+"?groupId="+unrelated.id()).header("Authorization",bearer(source)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        jdbc.update("UPDATE support_group SET enabled=FALSE WHERE id=?",managed.id());
        mvc.perform(get(url+"?groupId="+managed.id()).header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void candidateBecomingOfflineBeforeTransferCannotChangeTicketOrCreateAssignment() throws Exception {
        var f=fixture(); long source=agent(f,f.group.id()),target=agent(f,f.group.id()),id=create(f,f.owner,"候选失效");
        jdbc.update("UPDATE ticket SET assignee_id=?,status='PROCESSING' WHERE id=?",source,id);
        mvc.perform(get("/api/workspace/tickets/"+id+"/assignment-candidates").header("Authorization",bearer(source)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].userId").value(target));
        support.setOnline(target,new OnlineInput(false,1));
        mvc.perform(post("/api/tickets/"+id+"/transfer").header("Authorization",bearer(source))
                .contentType("application/json").content("{\"assigneeId\":"+target+",\"version\":0,\"reason\":\"转交处理\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_ASSIGNEE"));
        org.junit.jupiter.api.Assertions.assertEquals(source,jdbc.queryForObject("SELECT assignee_id FROM ticket WHERE id=?",Long.class,id));
        org.junit.jupiter.api.Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ticket_assignment WHERE ticket_id=?",Integer.class,id));
        org.junit.jupiter.api.Assertions.assertEquals(0,jdbc.queryForObject("SELECT version FROM ticket WHERE id=?",Long.class,id));
    }

    @Test
    void explicitViewsPreserveOwnershipAndGroupBoundaries() throws Exception {
        var f=fixture(); long own=create(f,f.agent,"我提交的"), assigned=create(f,f.owner,"分配给我"), other=create(f,f.owner,"别人的");
        jdbc.update("UPDATE ticket SET assignee_id=?,status='ASSIGNED' WHERE id=?",f.agent,assigned);
        mvc.perform(get(path(f)).header("Authorization",bearer(f.agent)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(own));
        mvc.perform(get(path(f)+"&view=MINE_ASSIGNED").header("Authorization",bearer(f.agent)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(assigned))
                .andExpect(jsonPath("$.data.items[0].assigneeName").value("工作台用户"))
                .andExpect(jsonPath("$.data.items[0].categoryName").value("网络与连接"));
        mvc.perform(get(path(f)+"&view=MY_GROUP").header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(3));
        mvc.perform(get(path(f)+"&view=MY_GROUP").header("Authorization",bearer(user(Role.LEADER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get(path(f)+"&view=ACCESSIBLE").header("Authorization",bearer(f.agent)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
        mvc.perform(get(path(f)+"&view=MINE_ASSIGNED").header("Authorization",bearer(f.owner))).andExpect(status().isForbidden());
        mvc.perform(get(path(f)+"&view=MY_GROUP").header("Authorization",bearer(f.owner))).andExpect(status().isForbidden());
        mvc.perform(get("/api/workspace/tickets/"+other).header("Authorization",bearer(f.agent))).andExpect(status().isForbidden());
    }

    @Test
    void paginationAndLiteralSearchUseFullAuthorizedDataset() throws Exception {
        var f=fixture(); long first=create(f,f.owner,"待补充%网络"), second=create(f,f.owner,"新建网络"), third=create(f,f.owner,"最新工单");
        jdbc.update("UPDATE ticket SET status='PENDING' WHERE id=?",first);
        mvc.perform(get(path(f)+"&limit=2").header("Authorization",bearer(f.owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(first))
                .andExpect(jsonPath("$.data.items[1].id").value(third));
        mvc.perform(get(path(f)+"&limit=2&offset=2").header("Authorization",bearer(f.owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.items[0].id").value(second));
        mvc.perform(get(path(f)).param("keyword","%").header("Authorization",bearer(f.owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get(path(f)).param("keyword",Long.toString(second)).header("Authorization",bearer(f.owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(second));
        mvc.perform(get(path(f)+"&status=CREATED&priority=LOW").header("Authorization",bearer(f.owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void slaQueueUsesOutstandingDeadlinesNotHistoricalBreachFlags() throws Exception {
        var f=fixture(); long warning=create(f,f.owner,"临近响应"), breached=create(f,f.owner,"解决超时");
        long completed=create(f,f.owner,"已回复"), resolved=create(f,f.owner,"已解决");
        var now=Instant.now();
        jdbc.update("UPDATE ticket SET response_deadline=? WHERE id=?",Timestamp.from(now.plusSeconds(120)),warning);
        jdbc.update("UPDATE ticket SET resolve_deadline=? WHERE id=?",Timestamp.from(now.minusSeconds(60)),breached);
        jdbc.update("UPDATE ticket SET response_deadline=?,first_response_at=?,response_breached=TRUE WHERE id=?",
                Timestamp.from(now.minusSeconds(120)),Timestamp.from(now.minusSeconds(60)),completed);
        jdbc.update("UPDATE ticket SET status='RESOLVED',response_deadline=?,resolve_deadline=?,resolved_at=? WHERE id=?",
                Timestamp.from(now.minusSeconds(120)),Timestamp.from(now.minusSeconds(60)),Timestamp.from(now),resolved);
        mvc.perform(get(path(f)+"&sla=WARNING").header("Authorization",bearer(f.owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(warning));
        mvc.perform(get(path(f)+"&sla=BREACHED").header("Authorization",bearer(f.owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(breached));
    }

    @Test
    void detailReadsLatestVersionAndHidesDataFromUnrelatedUsers() throws Exception {
        var f=fixture(); long id=create(f,f.owner,"更新前");
        tickets.comment(id,new CommentInput("补充信息",false,0),new TicketActor(f.owner,false,false,false));
        mvc.perform(get("/api/workspace/tickets/"+id).header("Authorization",bearer(f.owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.detail.ticket.version").value(1))
                .andExpect(jsonPath("$.data.display.version").value(1))
                .andExpect(jsonPath("$.data.display.groupName").value(f.group.name()))
                .andExpect(jsonPath("$.data.staff").value(false));
        mvc.perform(get("/api/workspace/tickets/"+id).header("Authorization",bearer(f.leader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.staff").value(true));
        mvc.perform(get("/api/workspace/tickets/"+id).header("Authorization",bearer(user(Role.USER)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/workspace/tickets/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(get(path(f)+"&limit=101").header("Authorization",bearer(f.owner))).andExpect(status().isBadRequest());
        mvc.perform(get(path(f)+"&view=UNKNOWN").header("Authorization",bearer(f.owner))).andExpect(status().isBadRequest());
        mvc.perform(get(path(f)).param("keyword","a".repeat(201)).header("Authorization",bearer(f.owner))).andExpect(status().isBadRequest());
    }

    @Test
    void availablePrioritiesRespectPolicyCategoryAndGroupAvailability() throws Exception {
        var f=fixture(); String url="/api/support/categories/"+f.category.id()+"/priorities";
        mvc.perform(get(url).header("Authorization",bearer(f.owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].priority").value("HIGH"))
                .andExpect(jsonPath("$.data[0].responseMinutes").value(30));
        for(String table:List.of("sla_policy","ticket_category","support_group")) {
            long id=table.equals("sla_policy")?f.policy.id():table.equals("ticket_category")?f.category.id():f.group.id();
            jdbc.update("UPDATE "+table+" SET enabled=FALSE WHERE id=?",id);
            mvc.perform(get(url).header("Authorization",bearer(f.owner)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
            jdbc.update("UPDATE "+table+" SET enabled=TRUE WHERE id=?",id);
        }
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
    }
}
