package io.github.xw66.opsflow.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.xw66.opsflow.MySqlTestBase;
import io.github.xw66.opsflow.common.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest extends MySqlTestBase {
    private static final String PASSWORD = "Strong_pass_123";
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired UserAdminService admin;
    @MockitoSpyBean UserMapper users;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private AuthController.RegisterRequest request(String username) {
        return new AuthController.RegisterRequest(username, PASSWORD, "测试用户");
    }

    private String name() { return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); }
    private String token(long id) { return auth.login(new AuthController.LoginRequest(users.findById(id).username(), PASSWORD)).accessToken(); }
    private long create() { return auth.register(request(name())).id(); }
    private long createAdmin() { long id = create(); users.addRole(id, Role.ADMIN); return id; }

    @Test
    void adminUserSearchPaginatesFiltersAndNeverExposesPassword() throws Exception {
        long owner = createAdmin();
        long agent = create(); users.addRole(agent, Role.AGENT);
        long disabled = create(); admin.setEnabled(owner, disabled, false, "离职");
        mvc.perform(get("/api/admin/users").param("keyword", users.findById(agent).username().substring(2, 8))
                .param("enabled", "true").param("limit", "1").header("Authorization", "Bearer " + token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(agent))
                .andExpect(jsonPath("$.data.items[0].roles").value(org.hamcrest.Matchers.containsInAnyOrder("USER", "AGENT")))
                .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mvc.perform(get("/api/admin/users").param("enabled", "false").header("Authorization", "Bearer " + token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[*].id").value(org.hamcrest.Matchers.hasItem((int) disabled)));
        mvc.perform(get("/api/admin/users").param("keyword", "x".repeat(101)).header("Authorization", "Bearer " + token(owner)))
                .andExpect(status().isBadRequest());
        assertThrows(BusinessException.class, () -> auth.login(new AuthController.LoginRequest(users.findById(disabled).username(), PASSWORD)));
    }

    @Test
    void registersLogsInAndNeverReturnsPassword() throws Exception {
        String username = name();
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request(username))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.roles").value(org.hamcrest.Matchers.containsInAnyOrder("USER")))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).path("data").path("id").asLong();
        assertTrue(passwords.matches(PASSWORD, users.findById(id).passwordHash()));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token(id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AuthController.LoginRequest(username, "wrong_password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidRegistrationAndClientChosenRoles() throws Exception {
        for (String body : List.of("{}", "{\"username\":\"abc\",\"password\":\"short\",\"displayName\":\"测试\"}",
                "{\"username\":\"abc\",\"password\":\"Strong_pass_123\",\"displayName\":\"测试\",\"roles\":[\"ADMIN\"]}")) {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AuthController.RegisterRequest(name(), "汉".repeat(25), "测试"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentDuplicateRegistrationCommitsOneUserAndOneRole() throws Exception {
        String username = name();
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
            List<Future<Boolean>> results = java.util.stream.IntStream.range(0, 6).mapToObj(i -> executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try { auth.register(request(username)); return true; }
                catch (BusinessException ex) { assertEquals("USERNAME_EXISTS", ex.getCode()); return false; }
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) if (result.get(20, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        assertEquals(List.of(Role.USER), users.roles(users.findByUsername(username).id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?", Integer.class, username));
    }

    @Test
    void rollsBackUserWhenRoleInsertFails() {
        String username = name();
        doThrow(new IllegalStateException("模拟角色写入失败")).when(users).addRole(anyLong(), eq(Role.USER));
        assertThrows(IllegalStateException.class, () -> auth.register(request(username)));
        assertNull(users.findByUsername(username));
    }

    @Test
    void bootstrapCreatesFirstAdministratorOnceWithoutResettingPassword() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("DELETE ur FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE r.code='ADMIN'");
            String username = name();
            auth.bootstrapAdmin(request(username));
            UserAccount created = users.findByUsername(username);
            assertTrue(users.roles(created.id()).contains(Role.ADMIN));
            auth.bootstrapAdmin(new AuthController.RegisterRequest(username, "different_password", "管理员"));
            assertEquals(created.passwordHash(), users.findById(created.id()).passwordHash());
            assertEquals(1, users.countAdmins());
            tx.setRollbackOnly();
        });
    }

    @Test
    void disabledUserAndRemovedRoleTakeEffectOnExistingToken() throws Exception {
        long owner = createAdmin();
        long target = createAdmin();
        long ordinary = create();
        String originalToken = token(target);
        admin.setRoles(owner, target, Set.of(Role.USER), "岗位调整");
        mvc.perform(put("/api/admin/users/" + ordinary + "/enabled").header("Authorization", "Bearer " + originalToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"reason\":\"测试\"}"))
                .andExpect(status().isForbidden());
        admin.setEnabled(owner, target, false, "账号停用");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + originalToken)).andExpect(status().isUnauthorized());
        assertThrows(BusinessException.class, () -> auth.login(new AuthController.LoginRequest(users.findById(target).username(), PASSWORD)));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND resource_type='USER'", Integer.class, target));
    }

    @Test
    void administratorCanChangeAnotherUserButCannotChangeSelf() throws Exception {
        long owner = createAdmin();
        long target = create();
        String bearer = "Bearer " + token(owner);
        mvc.perform(put("/api/admin/users/" + target + "/roles").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"AGENT\"],\"reason\":\"调入客服\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.roles").value(org.hamcrest.Matchers.containsInAnyOrder("AGENT")));
        mvc.perform(put("/api/admin/users/" + owner + "/enabled").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"reason\":\"停用\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/admin/users/" + target + "/roles").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[],\"reason\":\"测试\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ordinaryUserCannotGrantRoles() throws Exception {
        long target = create();
        mvc.perform(put("/api/admin/users/" + target + "/roles").header("Authorization", "Bearer " + token(target))
                .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"ADMIN\"],\"reason\":\"提权\"}"))
                .andExpect(status().isForbidden());
        assertEquals(List.of(Role.USER), users.roles(target));
    }

    @Test
    void rejectsExpiredForgedWrongIssuerAndMissingExpiryTokens() throws Exception {
        long id = create();
        Instant now = Instant.now();
        var valid = JwtClaimsSet.builder().issuer("opsflow").subject(Long.toString(id)).audience(List.of("opsflow-api"))
                .issuedAt(now).expiresAt(now.plusSeconds(600)).build();
        var wrongEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(new byte[32]));
        for (String invalid : List.of(sign(JwtClaimsSet.from(valid).issuedAt(now.minusSeconds(600)).expiresAt(now.minusSeconds(1)).build(), encoder),
                sign(valid, wrongEncoder), sign(JwtClaimsSet.from(valid).issuer("attacker").build(), encoder),
                sign(JwtClaimsSet.from(valid).audience(List.of("different-api")).build(), encoder),
                sign(JwtClaimsSet.from(valid).claims(claims -> claims.remove("exp")).build(), encoder), "not.a.jwt")) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    private String sign(JwtClaimsSet claims, JwtEncoder signer) {
        return signer.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
