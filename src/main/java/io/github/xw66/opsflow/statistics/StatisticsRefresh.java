package io.github.xw66.opsflow.statistics;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatisticsRefresh {
    private final StatisticsMapper data;
    private final Clock clock;
    public StatisticsRefresh(StatisticsMapper data, Clock clock) { this.data = data; this.clock = clock; }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean refreshOne() {
        var day = data.nextDay();
        if (day == null) return false;
        // 持有日期任务行锁完成全量重算；并发刷新请求等待提交后重新置脏，不丢失事件也不重复累加。
        data.clearDay(day);
        data.rebuild(day, day.atStartOfDay(StatisticsService.ZONE).toInstant(), day.plusDays(1).atStartOfDay(StatisticsService.ZONE).toInstant());
        data.refreshed(day, clock.instant());
        return true;
    }
}
