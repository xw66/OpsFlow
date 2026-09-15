package io.github.xw66.opsflow.ai;

import io.github.xw66.opsflow.ai.AiModels.CallResult;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiWorkService {
    private final AiMapper data;
    private final Clock clock;
    public AiWorkService(AiMapper data, Clock clock) { this.data = data; this.clock = clock; }
    @Transactional
    public Lease claim() {
        var pending = data.next(clock.instant());
        if (pending == null) return null;
        String owner = UUID.randomUUID().toString();
        data.claim(pending.id(), owner, clock.instant().plusSeconds(90));
        return new Lease(pending, owner);
    }
    @Transactional
    public void finish(Lease lease, CallResult result) {
        boolean applied = data.finish(lease.pending().id(), lease.owner(), result, clock.instant()) == 1;
        data.log(lease.pending().id(), lease.owner(), result, applied, clock.instant());
    }
    public record Lease(AiMapper.Pending pending, String owner) { }
}
