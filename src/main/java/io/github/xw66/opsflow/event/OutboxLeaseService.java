package io.github.xw66.opsflow.event;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxLeaseService {
    private final OutboxMapper data;
    private final Clock clock;
    public OutboxLeaseService(OutboxMapper data, Clock clock) { this.data = data; this.clock = clock; }

    @Transactional
    public Lease claim() {
        var row = data.next(clock.instant());
        if (row == null) return null;
        String owner = UUID.randomUUID().toString();
        data.claim(row.eventId(), owner, clock.instant().plusSeconds(30));
        return new Lease(row.eventId(), row.aggregateId(), row.payload(), row.attempts() + 1, owner);
    }

    public boolean sent(Lease lease) { return data.sent(lease.eventId(), lease.owner(), clock.instant()) == 1; }

    public void failed(Lease lease, Exception failure) {
        long delay = Math.min(60, 1L << Math.min(lease.attempts(), 6));
        data.failed(lease.eventId(), lease.owner(), failure.getClass().getSimpleName(), clock.instant().plusSeconds(delay));
    }

    public record Lease(String eventId, long ticketId, String payload, int attempts, String owner) { }
}
