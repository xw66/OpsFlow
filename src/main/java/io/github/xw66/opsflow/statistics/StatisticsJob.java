package io.github.xw66.opsflow.statistics;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StatisticsJob {
    private final StatisticsMapper data;
    private final StatisticsRefresh refresh;
    private final Clock clock;
    private final boolean enabled;
    public StatisticsJob(StatisticsMapper data, StatisticsRefresh refresh, Clock clock,
            @Value("${opsflow.statistics.enabled:true}") boolean enabled) {
        this.data = data; this.refresh = refresh; this.clock = clock; this.enabled = enabled;
    }
    @Scheduled(fixedDelayString = "${opsflow.statistics.interval-ms:60000}", initialDelayString = "${opsflow.statistics.interval-ms:60000}")
    public void scan() {
        if (!enabled) return;
        var today = clock.instant().atZone(StatisticsService.ZONE).toLocalDate();
        data.requestDay(today); data.requestDay(today.minusDays(1));
        for (int i = 0; i < 5 && refresh.refreshOne(); i++) { }
    }
}
