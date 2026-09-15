package io.github.xw66.opsflow.auth;

import io.github.xw66.opsflow.auth.AuthController.*;
import io.github.xw66.opsflow.common.AuditMapper;
import io.github.xw66.opsflow.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final JwtService tokens;
    private final AuditMapper audit;
    private final String dummyHash;

    public AuthService(UserMapper users, PasswordEncoder passwords, JwtService tokens, AuditMapper audit) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.audit = audit;
        this.dummyHash = passwords.encode(java.util.UUID.randomUUID().toString());
    }

    @Transactional
    public UserView register(RegisterRequest request) {
        validatePassword(request.password());
        String username = request.username().toLowerCase(Locale.ROOT);
        try {
            users.insert(username, passwords.encode(request.password()), request.displayName().strip());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }
        UserAccount user = users.findByUsername(username);
        if (users.addRole(user.id(), Role.USER) != 1) throw new IllegalStateException("系统缺少普通用户角色");
        return userView(user.id());
    }

    public TokenView login(LoginRequest request) {
        validatePassword(request.password());
        UserAccount user = users.findByUsername(request.username().toLowerCase(Locale.ROOT));
        boolean matches = passwords.matches(request.password(), user == null ? dummyHash : user.passwordHash());
        if (user == null || !matches || !user.enabled()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误，或账号已停用");
        }
        return tokens.issue(user.id());
    }

    public UserView userView(long id) {
        UserAccount user = users.findById(id);
        if (user == null) throw new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        return new UserView(id, user.username(), user.displayName(), user.enabled(), users.roles(id));
    }

    @Transactional
    public void bootstrapAdmin(RegisterRequest request) {
        // 所有管理员变更先锁同一行，防止并发初始化或互相停用导致管理员丢失。
        users.lockAdminRole();
        if (users.countAdmins() != 0) return;
        UserView user = register(request);
        users.addRole(user.id(), Role.ADMIN);
        audit.insert(null, "ADMIN_BOOTSTRAP", "USER", user.id(), null, "{\"role\":\"ADMIN\"}", "显式初始化首个管理员");
    }

    private void validatePassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "密码的 UTF-8 编码长度不能超过72字节");
        }
    }
}
