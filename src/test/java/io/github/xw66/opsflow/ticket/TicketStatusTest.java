package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.auth.Role;
import io.github.xw66.opsflow.common.BusinessException;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class TicketStatusTest {
    private static final Set<String> ALLOWED = Set.of(
            "CREATED:ASSIGNED", "ASSIGNED:PROCESSING", "PROCESSING:PENDING",
            "PENDING:PROCESSING", "PROCESSING:RESOLVED", "RESOLVED:CLOSED", "CREATED:CANCELLED");

    static Stream<Arguments> transitions() {
        return Stream.of(TicketStatus.values()).flatMap(from ->
                Stream.of(TicketStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest
    @MethodSource("transitions")
    void enforcesCompleteTransitionMatrix(TicketStatus from, TicketStatus to) {
        if (ALLOWED.contains(from + ":" + to)) {
            assertDoesNotThrow(() -> from.requireTransitionTo(to));
        } else {
            BusinessException ex = assertThrows(BusinessException.class, () -> from.requireTransitionTo(to));
            assertEquals(409, ex.getStatus().value());
        }
    }

    static Stream<Arguments> reopenCases() {
        return Stream.of(TicketStatus.values()).flatMap(status ->
                Stream.of(Role.values()).map(role -> Arguments.of(status, role)));
    }

    @ParameterizedTest
    @MethodSource("reopenCases")
    void restrictsReopeningByStateAndRole(TicketStatus status, Role role) {
        boolean privileged = role == Role.ADMIN || role == Role.LEADER;
        boolean finished = status == TicketStatus.RESOLVED || status == TicketStatus.CLOSED;
        if (privileged && finished) {
            assertDoesNotThrow(() -> status.requireReopen(Set.of(role), "问题复现"));
        } else {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> status.requireReopen(Set.of(role), "问题复现"));
            assertEquals(privileged ? 409 : 403, ex.getStatus().value());
        }
    }

    @Test
    void rejectsMissingTargetAndUntrustedReopenInput() {
        for (TicketStatus status : TicketStatus.values()) {
            assertThrows(BusinessException.class, () -> status.requireTransitionTo(null));
        }
        for (String reason : new String[]{null, "", " \n ", "a".repeat(501)}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> TicketStatus.CLOSED.requireReopen(Set.of(Role.ADMIN), reason));
            assertEquals(400, ex.getStatus().value());
        }
        assertDoesNotThrow(() -> TicketStatus.CLOSED.requireReopen(Set.of(Role.ADMIN), "a".repeat(500)));
        assertThrows(BusinessException.class, () -> TicketStatus.CLOSED.requireReopen(null, "问题复现"));
        assertThrows(BusinessException.class, () -> TicketStatus.CLOSED.requireReopen(Set.of(), "问题复现"));
    }
}
