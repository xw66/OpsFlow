package io.github.xw66.opsflow.sla;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SlaJob {
    private static final Logger log = LoggerFactory.getLogger(SlaJob.class);
    private final SlaMapper data;
    private final SlaService service;
    private final Clock clock;
    private final int batchSize;
    private final boolean enabled;
    private final SlaMapper.Candidate[] cursors = {new SlaMapper.Candidate(0, Instant.EPOCH), new SlaMapper.Candidate(0, Instant.EPOCH)};

    public SlaJob(SlaMapper data, SlaService service, Clock clock,
            @Value("${opsflow.sla.batch-size:100}") int batchSize, @Value("${opsflow.sla.enabled:true}") boolean enabled) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("SLA扫描批量必须为1至1000");
        this.data = data; this.service = service; this.clock = clock; this.batchSize = batchSize; this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${opsflow.sla.interval-ms:10000}", initialDelayString = "${opsflow.sla.interval-ms:10000}")
    public void scan() {
        if (!enabled) return;
        long started = System.nanoTime();
        int inspected = 0;
        Instant now = clock.instant(), horizon = now.plusSeconds(service.warningSeconds());
        for (int dimension = 0; dimension < 2; dimension++) {
            var cursor = cursors[dimension];
            var rows = dimension == 0 ? data.responses(now, horizon, cursor.deadline(), cursor.id(), batchSize)
                    : data.resolutions(now, horizon, cursor.deadline(), cursor.id(), batchSize);
            for (var row : rows) {
                try { service.inspect(row.id(), dimension == 0); }
                catch (RuntimeException ex) { log.warn("SLA检查失败，将在下一轮重试：{}，{}", row.id(), ex.getClass().getSimpleName()); }
                inspected++;
            }
            // 每轮有界扫描，游标走完后回绕，失败或刚进入窗口的前序工单不会永久遗漏。
            cursors[dimension] = rows.size() < batchSize ? new SlaMapper.Candidate(0, Instant.EPOCH) : rows.getLast();
        }
        log.info("SLA扫描完成，检查{}条，耗时{}毫秒", inspected, (System.nanoTime() - started) / 1_000_000);
    }
}
