package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.MySqlTestBase;
import io.github.xw66.opsflow.auth.*;
import io.github.xw66.opsflow.event.OutboxMapper;
import io.github.xw66.opsflow.event.OutboxService;
import io.github.xw66.opsflow.support.*;
import io.github.xw66.opsflow.support.SupportModels.*;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
class TicketIntegrationTest extends MySqlTestBase {
    private static final Path STORAGE = Path.of("target", "ticket-test-attachments", UUID.randomUUID().toString()).toAbsolutePath();
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) { registry.add("opsflow.attachments.directory", STORAGE::toString); }

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired UserMapper users;
    @Autowired SupportService support;
    @Autowired SupportMapper configuration;
    @Autowired TicketService tickets;
    @Autowired AttachmentService attachments;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoSpyBean TicketMapper data;
    @MockitoSpyBean OutboxMapper outbox;
    @Autowired OutboxService events;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String unique() { return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(); }
    private long user(Role role) {
        long id = auth.register(new AuthController.RegisterRequest(unique(), "Strong_pass_123", "测试用户")).id();
        if (role != Role.USER) users.addRole(id, role);
        return id;
    }
    private String bearer(long id) {
        return "Bearer " + auth.login(new AuthController.LoginRequest(users.findById(id).username(), "Strong_pass_123")).accessToken();
    }
    private Fixture fixture() {
        long admin = user(Role.ADMIN), owner = user(Role.USER), leader = user(Role.LEADER);
        Group group = support.saveGroup(null, new GroupInput(unique(), leader, true, 0), admin);
        Category category = support.saveCategory(null, new CategoryInput(unique(), "网络故障", group.id(), true, 0), admin);
        Policy policy = support.savePolicy(null, new PolicyInput(category.id(), Priority.HIGH, 30, 240, true, true, 0), admin);
        return new Fixture(admin, owner, leader, category.id(), policy.id());
    }
    private record Fixture(long admin, long owner, long leader, long category, long policy) { }
    private TicketInput input(Fixture f) { return new TicketInput("无法访问内部系统", "浏览器提示服务不可用", f.category(), Priority.HIGH, Set.of("Network", " vpn ")); }
    private TicketActor owner(Fixture f) { return new TicketActor(f.owner(), false, false, false); }
    private long create(Fixture f) { return tickets.create(input(f), owner(f)).ticket().id(); }
    private ResultActions postJson(String path, Object input, long actor) throws Exception {
        return mvc.perform(post(path).header("Authorization", bearer(actor)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)));
    }
    private ResultActions edit(long id, EditInput input, long actor) throws Exception {
        return mvc.perform(put("/api/tickets/" + id).header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)));
    }

    @Test
    void concurrentCreateAndEditWithSharedTagsPreserveEveryTransaction() throws Exception {
        Fixture f = fixture();
        String shared = unique().toLowerCase(Locale.ROOT);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    List<Long> ids = new ArrayList<>();
                    for (int n = 0; n < 10; n++) {
                        var request = new TicketInput("共享标签并发", "验证关联间隙锁与标签锁顺序", f.category(), Priority.HIGH,
                                new LinkedHashSet<>(n % 2 == 0 ? List.of(shared, "shared") : List.of("shared", shared)));
                        var created = tickets.create(request, owner(f));
                        var edited = tickets.edit(created.ticket().id(), new EditInput(request, 0), owner(f));
                        assertEquals(1, edited.ticket().version());
                        assertEquals(new TreeSet<>(request.tags()), new TreeSet<>(edited.tags()));
                        ids.add(created.ticket().id());
                    }
                    return ids;
                }));
            }
            start.countDown();
            Set<Long> ids = new HashSet<>();
            for (var future : futures) ids.addAll(future.get(30, TimeUnit.SECONDS));
            assertEquals(80, ids.size());
            for (long id : ids) {
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='TicketCreatedEvent'", Integer.class, id));
                assertEquals(1, data.histories(id, 0, 20).size());
            }
        }
    }

    @Test
    void creationPersistsDeadlinesTagsHistoryAndOutboxInOneTransaction() throws Exception {
        Fixture f = fixture();
        String body = postJson("/api/tickets", input(f), f.owner()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ticket.status").value("CREATED"))
                .andExpect(jsonPath("$.data.tags[0]").value("network"))
                .andExpect(jsonPath("$.data.tags[1]").value("vpn"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).path("data").path("ticket").path("id").asLong();
        Ticket ticket = data.find(id);
        assertEquals(ticket.createdAt().plusSeconds(1800), ticket.responseDeadline());
        assertEquals(ticket.createdAt().plusSeconds(14400), ticket.resolveDeadline());
        assertEquals(0, ticket.version());
        assertEquals(1, data.histories(id, 0, 20).size());
        assertEquals(f.owner(), data.histories(id, 0, 20).getFirst().operatorId());
        assertEquals("测试用户", data.histories(id, 0, 20).getFirst().operatorName());
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_event WHERE aggregate_id=?", String.class, id);
        assertEquals("TicketCreatedEvent", json.readTree(payload).path("eventType").asText());
        assertEquals(id, json.readTree(payload).path("ticketId").asLong());
        assertTrue(json.readTree(payload).path("eventId").asText().matches("[0-9a-f-]{36}"));
    }

    @Test
    void outboxFailureRollsBackTicketTagsHistoryAndAudit() {
        Fixture f = fixture();
        doThrow(new IllegalStateException("模拟 Outbox 写入失败")).when(outbox).insert(anyString(), anyLong(), anyLong(), anyString(), anyString(), any());
        assertThrows(IllegalStateException.class, () -> tickets.create(input(f), owner(f)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE user_id=?", Integer.class, f.owner()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE operator_id=?", Integer.class, f.owner()));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> events.append(1, 0, "TicketCreatedEvent", Map.of(), Instant.now()));
    }

    @Test
    void listDetailAndChildrenEnforceOwnershipAndLeaderScope() throws Exception {
        Fixture f = fixture();
        long id = create(f);
        long other = user(Role.USER);
        for (String suffix : List.of("", "/comments", "/history", "/attachments")) {
            mvc.perform(get("/api/tickets/" + id + suffix).header("Authorization", bearer(other))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/tickets").header("Authorization", bearer(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/tickets?categoryId=" + f.category()).header("Authorization", bearer(f.leader())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(id));
        long unrelatedLeader = user(Role.LEADER);
        mvc.perform(get("/api/tickets/" + id).header("Authorization", bearer(unrelatedLeader))).andExpect(status().isForbidden());
        edit(id, new EditInput(input(f), 0), f.leader()).andExpect(status().isForbidden());
        mvc.perform(get("/api/tickets?limit=101").header("Authorization", bearer(f.owner()))).andExpect(status().isBadRequest());
    }

    @Test
    void editsUseVersionAndKeepOriginalSlaSnapshot() throws Exception {
        Fixture f = fixture();
        long id = create(f);
        Ticket original = data.find(id);
        edit(id, new EditInput(input(f), 1), f.owner()).andExpect(status().isConflict());
        assertEquals(0, data.find(id).version());
        support.savePolicy(f.policy(), new PolicyInput(f.category(), Priority.HIGH, 120, 600, true, true, 0), f.admin());
        EditInput edit = new EditInput(new TicketInput("更新标题", "补充问题描述", f.category(), Priority.HIGH, Set.of("updated")), 0);
        edit(id, edit, f.owner()).andExpect(status().isOk());
        Ticket after = data.find(id);
        assertEquals(1, after.version());
        assertEquals(original.responseDeadline(), after.responseDeadline());
        assertEquals(30, after.responseMinutes());
        assertEquals(List.of("updated"), data.tags(id));
        edit(id, edit, f.owner()).andExpect(status().isConflict());
        assertEquals("更新标题", data.find(id).title());
    }

    @Test
    void changingPriorityRecalculatesFromOriginalCreationTime() throws Exception {
        Fixture f = fixture();
        long id = create(f);
        Instant created = data.find(id).createdAt();
        support.savePolicy(null, new PolicyInput(f.category(), Priority.URGENT, 5, 60, true, true, 0), f.admin());
        edit(id, new EditInput(new TicketInput("紧急网络故障", "影响所有人员", f.category(), Priority.URGENT, Set.of()), 0), f.owner())
                .andExpect(status().isOk());
        assertEquals(created.plusSeconds(300), data.find(id).responseDeadline());
        assertEquals(created.plusSeconds(3600), data.find(id).resolveDeadline());
    }

    @Test
    void commentsHideInternalNotesAndOnlyPublicStaffReplyCountsAsResponse() throws Exception {
        Fixture f = fixture();
        long id = create(f);
        postJson("/api/tickets/" + id + "/comments", new CommentInput("请协助处理", true, 0), f.owner()).andExpect(status().isForbidden());
        postJson("/api/tickets/" + id + "/comments", new CommentInput("内部排查记录", true, 0), f.leader()).andExpect(status().isCreated());
        assertNull(data.find(id).firstResponseAt());
        postJson("/api/tickets/" + id + "/comments", new CommentInput("正在排查，请稍候", false, 1), f.leader()).andExpect(status().isCreated());
        Instant response = data.find(id).firstResponseAt();
        assertNotNull(response);
        postJson("/api/tickets/" + id + "/comments", new CommentInput("收到", false, 2), f.owner()).andExpect(status().isCreated());
        assertEquals(response, data.find(id).firstResponseAt());
        mvc.perform(get("/api/tickets/" + id + "/comments").header("Authorization", bearer(f.owner())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].authorName").value("测试用户"));
        mvc.perform(get("/api/tickets/" + id + "/comments").header("Authorization", bearer(f.leader())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void concurrentCancellationHasSingleWinnerAndSingleEvent() throws Exception {
        Fixture f = fixture();
        long id = create(f);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = java.util.stream.IntStream.range(0, 4).mapToObj(i -> executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try { tickets.cancel(id, new ActionInput(0, "取消重复提交"), owner(f)); return true; }
                catch (io.github.xw66.opsflow.common.BusinessException ex) { assertEquals(409, ex.getStatus().value()); return false; }
            })).toList();
            start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        assertEquals(TicketStatus.CANCELLED, data.find(id).status());
        assertEquals(2, data.histories(id, 0, 20).size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='TicketStatusChangedEvent'", Integer.class, id));
        postJson("/api/tickets/" + id + "/comments", new CommentInput("取消后回复", false, 1), f.owner()).andExpect(status().isConflict());
    }

    @Test
    void rejectsMissingPolicyInactiveCategoryAndInvalidFields() throws Exception {
        Fixture f = fixture();
        postJson("/api/tickets", new TicketInput("有效标题", "描述", f.category(), Priority.LOW, Set.of()), f.owner()).andExpect(status().isConflict());
        postJson("/api/tickets", new TicketInput(" ", "描述", f.category(), Priority.HIGH, Set.of()), f.owner()).andExpect(status().isBadRequest());
        var category = configuration.category(f.category());
        support.saveCategory(category.id(), new CategoryInput(category.code(), category.name(), category.groupId(), false, 0), f.admin());
        postJson("/api/tickets", input(f), f.owner()).andExpect(status().isBadRequest());
    }

    @Test
    void attachmentsArePrivateValidatedAndDeletedAfterCommit() throws Exception {
        Fixture f = fixture();
        long id = create(f);
        byte[] pdf = "%PDF-1.7\ntest attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var file = new MockMultipartFile("file", "报告.pdf", "application/pdf", pdf);
        String body = mvc.perform(multipart("/api/tickets/" + id + "/attachments").file(file).param("version", "0").header("Authorization", bearer(f.owner())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.storageKey").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long attachmentId = json.readTree(body).path("data").path("id").asLong();
        Path stored = STORAGE.resolve(data.attachmentById(id, attachmentId).storageKey());
        assertTrue(Files.exists(stored));
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            attachments.delete(id, attachmentId, 1, owner(f));
            tx.setRollbackOnly();
        });
        assertTrue(Files.exists(stored));
        assertNotNull(data.attachmentById(id, attachmentId));
        assertEquals(1, data.find(id).version());
        mvc.perform(get("/api/tickets/" + id + "/attachments/" + attachmentId).header("Authorization", bearer(user(Role.USER))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/tickets/" + id + "/attachments/" + attachmentId).header("Authorization", bearer(f.owner())))
                .andExpect(status().isOk()).andExpect(content().bytes(pdf)).andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(multipart("/api/tickets/" + id + "/attachments").file(new MockMultipartFile("file", "../escape.pdf", "application/pdf", pdf))
                .param("version", "1").header("Authorization", bearer(f.owner()))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/tickets/" + id + "/attachments").file(new MockMultipartFile("file", "fake.pdf", "application/pdf", "<script>".getBytes()))
                .param("version", "1").header("Authorization", bearer(f.owner()))).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/tickets/" + id + "/attachments/" + attachmentId).param("version", "1").header("Authorization", bearer(f.owner())))
                .andExpect(status().isOk());
        assertFalse(Files.exists(stored));
        assertNull(data.attachmentById(id, attachmentId));
    }

    @Test
    void failedAttachmentInsertRollsBackVersionAndRemovesNewFile() throws Exception {
        Fixture f = fixture();
        long id = create(f);
        Files.createDirectories(STORAGE);
        long before;
        try (var files = Files.list(STORAGE)) { before = files.count(); }
        doThrow(new IllegalStateException("模拟附件数据库写入失败")).when(data).attachment(eq(id), anyLong(), anyString(), anyString(), anyString(), anyLong(), any());
        mvc.perform(multipart("/api/tickets/" + id + "/attachments")
                .file(new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF-1.7\n".getBytes()))
                .param("version", "0").header("Authorization", bearer(f.owner()))).andExpect(status().isInternalServerError());
        assertEquals(0, data.find(id).version());
        try (var files = Files.list(STORAGE)) { assertEquals(before, files.count()); }
    }

    @Test
    void attachmentSizeAndCountLimitsLeaveVersionUnchangedOnRejection() throws Exception {
        Fixture f = fixture();
        long id = create(f);
        var small = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF-1.7\n".getBytes());
        for (int version = 0; version < 10; version++) attachments.upload(id, version, small, owner(f));
        var countFailure = assertThrows(io.github.xw66.opsflow.common.BusinessException.class, () -> attachments.upload(id, 10, small, owner(f)));
        assertEquals("ATTACHMENT_LIMIT", countFailure.getCode());
        var oversized = new MockMultipartFile("file", "big.pdf", "application/pdf", new byte[5 * 1024 * 1024 + 1]);
        var sizeFailure = assertThrows(io.github.xw66.opsflow.common.BusinessException.class, () -> attachments.upload(id, 10, oversized, owner(f)));
        assertEquals("INVALID_FILE_SIZE", sizeFailure.getCode());
        assertEquals(10, data.attachments(id).size());
        assertEquals(10, data.find(id).version());
    }
}
