package io.github.xw66.opsflow.ticket;

import org.springframework.security.core.Authentication;

public record TicketActor(long id, boolean admin, boolean leader, boolean agent) {
    public static TicketActor from(Authentication authentication) {
        var roles = authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList();
        return new TicketActor(Long.parseLong(authentication.getName()), roles.contains("ROLE_ADMIN"),
                roles.contains("ROLE_LEADER"), roles.contains("ROLE_AGENT"));
    }
}
