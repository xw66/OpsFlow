package io.github.xw66.opsflow.auth;

import io.github.xw66.opsflow.common.AuditMapper;
import io.github.xw66.opsflow.common.BusinessException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class UserAdminService {
    private final UserMapper users;
    private final AuthService auth;
    private final AuditMapper audit;
    private final ObjectMapper json;

    public UserAdminService(UserMapper users, AuthService auth, AuditMapper audit, ObjectMapper json) {
        this.users = users; this.auth = auth; this.audit = audit; this.json = json;
    }

    @Transactional
    public AuthController.UserView setEnabled(long actor, long id, boolean enabled, String reason) {
        checkAdmin(actor, id);
        var before = auth.userView(id);
        users.updateEnabled(id, enabled);
        var after = auth.userView(id);
        audit.insert(actor, "USER_ENABLED_CHANGED", "USER", id, json.writeValueAsString(before), json.writeValueAsString(after), reason);
        return after;
    }

    @Transactional
    public AuthController.UserView setRoles(long actor, long id, Set<Role> roles, String reason) {
        checkAdmin(actor, id);
        var before = auth.userView(id);
        users.deleteRoles(id);
        for (Role role : roles) users.addRole(id, role);
        var after = auth.userView(id);
        audit.insert(actor, "USER_ROLES_CHANGED", "USER", id, json.writeValueAsString(before), json.writeValueAsString(after), reason);
        return after;
    }

    @Transactional(readOnly = true)
    public AdminUserPage users(String keyword, Boolean enabled, Role role, int offset, int limit) {
        var rows = users.adminUsers(keyword.strip(), enabled, role, offset, limit + 1);
        var page = rows.stream().limit(limit).toList();
        var roles = page.isEmpty() ? java.util.Map.<Long, java.util.List<Role>>of() : users.rolesForUsers(page.stream().map(UserMapper.AdminUserRow::id).toList()).stream().collect(java.util.stream.Collectors.groupingBy(UserMapper.UserRoleRow::userId, java.util.stream.Collectors.mapping(UserMapper.UserRoleRow::code, java.util.stream.Collectors.toList())));
        var items = page.stream().map(row -> new AuthController.UserView(row.id(), row.username(), row.displayName(), row.enabled(), roles.getOrDefault(row.id(), java.util.List.of()))).toList();
        return new AdminUserPage(items, rows.size() > limit, offset, limit);
    }

    public record AdminUserPage(java.util.List<AuthController.UserView> items, boolean hasMore, int offset, int limit) { }

    private void checkAdmin(long actor, long id) {
        users.lockAdminRole();
        var current = auth.userView(actor);
        if (!current.enabled() || !current.roles().contains(Role.ADMIN)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "管理员权限已失效");
        }
        if (actor == id) throw new BusinessException(HttpStatus.CONFLICT, "SELF_ADMIN_CHANGE", "不能通过管理接口更改自己的权限或启停状态");
    }
}
