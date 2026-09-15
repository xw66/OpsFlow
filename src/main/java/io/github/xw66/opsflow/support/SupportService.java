package io.github.xw66.opsflow.support;

import io.github.xw66.opsflow.auth.Role;
import io.github.xw66.opsflow.auth.UserMapper;
import io.github.xw66.opsflow.common.AuditMapper;
import io.github.xw66.opsflow.common.BusinessException;
import io.github.xw66.opsflow.support.SupportModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;

@Service
public class SupportService {
    private final SupportMapper data;
    private final UserMapper users;
    private final AuditMapper audit;
    private final ObjectMapper json;

    public SupportService(SupportMapper data, UserMapper users, AuditMapper audit, ObjectMapper json) {
        this.data = data; this.users = users; this.audit = audit; this.json = json;
    }

    @Transactional
    public Group saveGroup(Long id, GroupInput input, long actor) {
        if (input.enabled()) requireRole(input.leaderId(), Role.LEADER);
        else required(users.findById(input.leaderId()));
        Group before = id == null ? null : required(data.group(id));
        if (before != null) checkVersion(before.version() == input.version() ? 1 : 0);
        if (id == null) { data.insertGroup(input); id = data.insertedId(); }
        else checkVersion(data.updateGroup(id, input));
        Group after = data.group(id);
        log(actor, "SUPPORT_GROUP", id, before, after);
        return after;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Agent saveAgent(Long id, AgentInput input, long actor) {
        Agent previous = id == null ? null : required(data.agent(id));
        if (previous != null) checkVersion(previous.version() == input.version() ? 1 : 0);
        long oldGroup = previous == null ? input.groupId() : previous.groupId();
        data.lockGroup(Math.min(oldGroup, input.groupId()));
        if (oldGroup != input.groupId()) data.lockGroup(Math.max(oldGroup, input.groupId()));
        if (input.enabled()) {
            requireRole(input.userId(), Role.AGENT);
            requireActiveGroup(input.groupId());
        } else {
            required(users.findById(input.userId()));
            required(data.group(input.groupId()));
        }
        Agent before = id == null ? null : required(data.agent(id));
        if (before != null) checkVersion(before.version() == input.version() ? 1 : 0);
        if (id == null) { data.insertAgent(input); id = data.insertedId(); }
        else checkVersion(data.updateAgent(id, input));
        Agent after = data.agent(id);
        log(actor, "SUPPORT_AGENT", id, before, after);
        return after;
    }

    @Transactional
    public Category saveCategory(Long id, CategoryInput input, long actor) {
        if (input.enabled()) requireActiveGroup(input.groupId());
        else required(data.group(input.groupId()));
        Category before = id == null ? null : required(data.category(id));
        if (before != null) checkVersion(before.version() == input.version() ? 1 : 0);
        if (id == null) { data.insertCategory(input); id = data.insertedId(); }
        else checkVersion(data.updateCategory(id, input));
        Category after = data.category(id);
        log(actor, "TICKET_CATEGORY", id, before, after);
        return after;
    }

    @Transactional
    public Policy savePolicy(Long id, PolicyInput input, long actor) {
        Category category = required(data.category(input.categoryId()));
        if (input.enabled()) {
            if (!category.enabled()) throw new BusinessException(HttpStatus.CONFLICT, "CATEGORY_DISABLED", "分类已停用");
            requireActiveGroup(category.groupId());
        }
        if (input.responseMinutes() > input.resolveMinutes()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SLA_LIMIT", "首次响应时限不得超过解决时限");
        }
        Policy before = id == null ? null : required(data.policy(id));
        if (before != null) checkVersion(before.version() == input.version() ? 1 : 0);
        if (id == null) { data.insertPolicy(input); id = data.insertedId(); }
        else checkVersion(data.updatePolicy(id, input));
        Policy after = data.policy(id);
        log(actor, "SLA_POLICY", id, before, after);
        return after;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Agent setOnline(long userId, OnlineInput input) {
        Agent previous = ownAgent(userId);
        checkVersion(previous.version() == input.version() ? 1 : 0);
        data.lockGroup(previous.groupId());
        Agent before = ownAgent(userId);
        checkVersion(previous.groupId() == before.groupId() ? 1 : 0);
        checkVersion(before.version() == input.version() ? 1 : 0);
        requireActiveGroup(before.groupId());
        checkVersion(data.setOnline(userId, input.online(), input.version()));
        Agent after = data.agentForUser(userId);
        log(userId, "SUPPORT_AGENT", after.id(), before, after);
        return after;
    }

    public Agent ownAgent(long userId) {
        return required(data.agentForUser(userId));
    }

    public void requireGroupAccess(long groupId, long actor) {
        Group group = required(data.group(groupId));
        if (!users.roles(actor).contains(Role.ADMIN) && group.leaderId() != actor) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "只能查看自己管理的客服组");
        }
    }

    private void requireRole(long id, Role role) {
        var user = required(users.findById(id));
        if (!user.enabled() || !users.roles(id).contains(role)) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_SUPPORT_USER", "用户已停用或不具备对应客服角色");
        }
    }

    private void requireActiveGroup(long id) {
        if (!required(data.group(id)).enabled()) throw new BusinessException(HttpStatus.CONFLICT, "GROUP_DISABLED", "客服组已停用");
    }

    private <T> T required(T record) {
        if (record == null) throw new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "记录不存在");
        return record;
    }

    private void checkVersion(int rows) {
        if (rows != 1) throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "记录已变化或不可修改，请刷新后重试");
    }

    private void log(long actor, String type, long id, Object before, Object after) {
        audit.insert(actor, "CONFIG_CHANGED", type, id, before == null ? null : json.writeValueAsString(before),
                json.writeValueAsString(after), "配置变更");
    }
}
