package io.github.xw66.opsflow.statistics;

import io.github.xw66.opsflow.common.BusinessException;
import io.github.xw66.opsflow.statistics.StatisticsMapper.*;
import io.github.xw66.opsflow.ticket.TicketActor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatisticsService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final StatisticsMapper data;
    private final Clock clock;
    public StatisticsService(StatisticsMapper data, Clock clock) { this.data = data; this.clock = clock; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Overview overview(TicketActor actor, LocalDate from, LocalDate until) {
        authorize(actor); validate(from, until, 366);
        Instant now = clock.instant(), start = from.atStartOfDay(ZONE).toInstant(), end = until.atStartOfDay(ZONE).toInstant();
        var totals = data.totals(actor, start, end, now);
        var ai = data.ai(actor, start, end);
        return new Overview(from, until, now, totals, ratio(totals.slaAchieved(), totals.resolvedSamples()), ai,
                ratio(ai.acceptedCount(), ai.successfulCount()), data.categories(actor, start, end), data.agents(actor), data.groups(actor));
    }

    @Transactional
    public List<DailyCount> daily(TicketActor actor, LocalDate from, LocalDate until) {
        authorize(actor); validate(from, until, 31);
        // 未计算过的日期进入有界刷新队列，返回refreshedAt=null，不能将未统计伪装成真实零值。
        for (LocalDate day = from; day.isBefore(until); day = day.plusDays(1)) data.ensureDay(day);
        return data.daily(actor, from, until);
    }

    private BigDecimal ratio(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
    private void authorize(TicketActor actor) {
        if (!actor.admin() && !actor.leader()) throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "统计仅向组长及管理员开放");
    }
    private void validate(LocalDate from, LocalDate until, int maximumDays) {
        if (from == null || until == null || !from.isBefore(until) || ChronoUnit.DAYS.between(from, until) > maximumDays
                || from.getYear() < 2000 || until.getYear() > 2100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "日期范围无效或超过查询上限");
        }
    }
    public record Overview(LocalDate from, LocalDate until, Instant asOf, Totals totals, BigDecimal slaAchievementRate,
            AiCounts ai, BigDecimal aiClassificationAcceptanceRate, List<CategoryCount> categories,
            List<AgentLoad> agents, List<GroupLoad> groups) { }
}
