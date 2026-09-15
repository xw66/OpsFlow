package io.github.xw66.opsflow.statistics;

import io.github.xw66.opsflow.ticket.TicketActor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface StatisticsMapper {
    String SCOPE = " FROM ticket t JOIN support_group g ON g.id=t.group_id WHERE t.created_at>=#{from} AND t.created_at<#{until} "
            + "AND (#{actor.admin}=TRUE OR (#{actor.leader}=TRUE AND g.leader_id=#{actor.id})) ";
    @Select("""
            SELECT COUNT(*) AS created_count,
                COUNT(CASE WHEN t.status<>'CANCELLED' AND t.first_response_at IS NOT NULL THEN 1 END) AS response_samples,
                AVG(CASE WHEN t.status<>'CANCELLED' THEN TIMESTAMPDIFF(MICROSECOND,t.created_at,t.first_response_at)/1000000.0 END) AS average_response_seconds,
                COUNT(CASE WHEN t.status IN ('RESOLVED','CLOSED') THEN 1 END) AS resolved_samples,
                AVG(CASE WHEN t.status IN ('RESOLVED','CLOSED') THEN TIMESTAMPDIFF(MICROSECOND,t.cycle_started_at,t.resolved_at)/1000000.0 END) AS average_resolve_seconds,
                COUNT(CASE WHEN t.status IN ('RESOLVED','CLOSED') AND t.response_breached=FALSE AND t.resolve_breached=FALSE
                    AND t.first_response_at<=t.response_deadline AND t.resolved_at<=t.resolve_deadline THEN 1 END) AS sla_achieved,
                COUNT(CASE WHEN t.status<>'CANCELLED' AND (t.response_breached=TRUE OR t.resolve_breached=TRUE
                    OR t.first_response_at>t.response_deadline OR t.resolved_at>t.resolve_deadline
                    OR (t.status IN ('CREATED','ASSIGNED','PROCESSING','PENDING') AND
                        ((t.first_response_at IS NULL AND t.response_deadline<#{now}) OR (t.resolved_at IS NULL AND t.resolve_deadline<#{now})))) THEN 1 END) AS overdue_count
            """ + SCOPE)
    Totals totals(TicketActor actor, Instant from, Instant until, Instant now);

    @Select("SELECT t.category_id AS category_id,COUNT(*) AS ticket_count " + SCOPE + " GROUP BY t.category_id ORDER BY t.category_id")
    List<CategoryCount> categories(TicketActor actor, Instant from, Instant until);

    @Select("""
            SELECT COUNT(*) AS successful_count,COUNT(CASE WHEN a.status='ACCEPTED' THEN 1 END) AS accepted_count
            FROM ticket_ai_analysis a JOIN ticket t ON t.id=a.ticket_id JOIN support_group g ON g.id=t.group_id
            WHERE a.kind='CLASSIFICATION' AND a.status IN ('SUCCEEDED','ACCEPTED')
                AND t.created_at>=#{from} AND t.created_at<#{until}
                AND (#{actor.admin}=TRUE OR (#{actor.leader}=TRUE AND g.leader_id=#{actor.id}))
            """)
    AiCounts ai(TicketActor actor, Instant from, Instant until);

    @Select("""
            SELECT t.group_id,t.assignee_id,COUNT(*) AS processing_count
            FROM ticket t JOIN support_group g ON g.id=t.group_id
            WHERE t.status='PROCESSING' AND t.assignee_id IS NOT NULL
                AND (#{actor.admin}=TRUE OR (#{actor.leader}=TRUE AND g.leader_id=#{actor.id}))
            GROUP BY t.group_id,t.assignee_id ORDER BY t.group_id,t.assignee_id
            """)
    List<AgentLoad> agents(@Param("actor") TicketActor actor);

    @Select("""
            SELECT t.group_id,COUNT(*) AS active_count,COUNT(CASE WHEN t.status='PROCESSING' THEN 1 END) AS processing_count,
                COUNT(CASE WHEN t.assignee_id IS NULL THEN 1 END) AS unassigned_count
            FROM ticket t JOIN support_group g ON g.id=t.group_id
            WHERE t.status IN ('CREATED','ASSIGNED','PROCESSING','PENDING')
                AND (#{actor.admin}=TRUE OR (#{actor.leader}=TRUE AND g.leader_id=#{actor.id}))
            GROUP BY t.group_id ORDER BY t.group_id
            """)
    List<GroupLoad> groups(@Param("actor") TicketActor actor);

    @Insert("INSERT INTO statistics_day(business_date,requested) VALUES(#{day},TRUE) ON DUPLICATE KEY UPDATE requested=TRUE")
    void requestDay(LocalDate day);
    @Insert("INSERT IGNORE INTO statistics_day(business_date) VALUES(#{day})")
    void ensureDay(LocalDate day);
    @Select("SELECT business_date FROM statistics_day WHERE requested=TRUE ORDER BY business_date LIMIT 1 FOR UPDATE SKIP LOCKED")
    LocalDate nextDay();
    @Delete("DELETE FROM statistics_daily WHERE business_date=#{day}")
    void clearDay(LocalDate day);
    @Insert("""
            INSERT INTO statistics_daily(business_date,group_id,created_count)
            SELECT #{day},group_id,COUNT(*) FROM ticket WHERE created_at>=#{from} AND created_at<#{until} GROUP BY group_id
            """)
    void rebuild(LocalDate day, Instant from, Instant until);
    @Update("UPDATE statistics_day SET requested=FALSE,refreshed_at=#{now} WHERE business_date=#{day}")
    void refreshed(LocalDate day, Instant now);
    @Select("""
            SELECT d.business_date,d.requested,d.refreshed_at,CASE WHEN d.refreshed_at IS NULL THEN NULL ELSE COALESCE(SUM(s.created_count),0) END AS created_count
            FROM statistics_day d LEFT JOIN statistics_daily s ON s.business_date=d.business_date
                AND (#{actor.admin}=TRUE OR EXISTS(SELECT 1 FROM support_group g WHERE g.id=s.group_id AND #{actor.leader}=TRUE AND g.leader_id=#{actor.id}))
            WHERE d.business_date>=#{from} AND d.business_date<#{until}
            GROUP BY d.business_date,d.requested,d.refreshed_at ORDER BY d.business_date
            """)
    List<DailyCount> daily(TicketActor actor, LocalDate from, LocalDate until);

    public record Totals(long createdCount, long responseSamples, BigDecimal averageResponseSeconds,
            long resolvedSamples, BigDecimal averageResolveSeconds, long slaAchieved, long overdueCount) { }
    public record AiCounts(long successfulCount, long acceptedCount) { }
    public record CategoryCount(long categoryId, long ticketCount) { }
    public record AgentLoad(long groupId, long assigneeId, long processingCount) { }
    public record GroupLoad(long groupId, long activeCount, long processingCount, long unassignedCount) { }
    public record DailyCount(LocalDate businessDate, boolean requested, Instant refreshedAt, Long createdCount) { }
}
