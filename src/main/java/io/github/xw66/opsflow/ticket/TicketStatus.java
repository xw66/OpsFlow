package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.auth.Role;
import io.github.xw66.opsflow.common.BusinessException;
import java.util.Set;
import org.springframework.http.HttpStatus;

public enum TicketStatus {
    CREATED, ASSIGNED, PROCESSING, PENDING, RESOLVED, CLOSED, CANCELLED;

    public void requireTransitionTo(TicketStatus target) {
        boolean allowed = switch (this) {
            case CREATED -> target == ASSIGNED || target == CANCELLED;
            case ASSIGNED, PENDING -> target == PROCESSING;
            case PROCESSING -> target == PENDING || target == RESOLVED;
            case RESOLVED -> target == CLOSED;
            case CLOSED, CANCELLED -> false;
        };
        if (!allowed) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_TICKET_TRANSITION", "不允许该工单状态转换");
        }
    }

    // 角色必须来自认证上下文；组长的组内权限由业务服务校验，不能信任请求中的角色。
    public void requireReopen(Set<Role> roles, String reason) {
        if (roles == null || (!roles.contains(Role.ADMIN) && !roles.contains(Role.LEADER))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅管理员或组长可以重新打开工单");
        }
        if (this != RESOLVED && this != CLOSED) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_TICKET_TRANSITION", "仅已解决或已关闭工单可以重新打开");
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REOPEN_REASON", "重新打开原因不能为空且不能超过500个字符");
        }
    }
}
