package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.ticket.TicketWorkspace.*;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TicketWorkspaceMapper {
    String SELECT = """
            SELECT t.id,t.title,t.user_id,u.display_name AS user_name,t.category_id,c.name AS category_name,
                t.group_id,g.name AS group_name,t.assignee_id,a.display_name AS assignee_name,
                t.priority,t.status,t.version,t.created_at,t.response_deadline,t.resolve_deadline,
                t.first_response_at,t.resolved_at,t.response_breached,t.resolve_breached,t.escalation_level
            FROM ticket t JOIN support_group g ON g.id=t.group_id
                JOIN ticket_category c ON c.id=t.category_id JOIN app_user u ON u.id=t.user_id
                LEFT JOIN app_user a ON a.id=t.assignee_id
            """;
    String DEADLINE = """
            CASE WHEN t.status IN ('CREATED','ASSIGNED','PROCESSING','PENDING')
                THEN CASE WHEN t.first_response_at IS NULL THEN LEAST(t.response_deadline,t.resolve_deadline)
                     ELSE t.resolve_deadline END ELSE NULL END
            """;

    @Select(SELECT + " WHERE t.id=#{id}")
    Row row(long id);

    @Select("""
            SELECT a.user_id,u.username,u.display_name,a.group_id,g.name AS group_name,a.online,
                (SELECT COUNT(*) FROM ticket t WHERE t.assignee_id=a.user_id
                    AND t.status IN ('ASSIGNED','PROCESSING','PENDING')) AS active_count,a.last_assigned_at
            FROM support_agent a JOIN app_user u ON u.id=a.user_id JOIN support_group g ON g.id=a.group_id
                JOIN support_group source_group ON source_group.id=#{ticket.groupId}
            WHERE a.online=TRUE AND a.enabled=TRUE AND u.enabled=TRUE AND g.enabled=TRUE
                AND EXISTS(SELECT 1 FROM user_role ur JOIN role r ON r.id=ur.role_id
                    WHERE ur.user_id=a.user_id AND r.code='AGENT')
                AND (#{ticket.assigneeId} IS NULL OR a.user_id!=#{ticket.assigneeId})
                AND (#{groupId} IS NULL OR a.group_id=#{groupId})
                AND (#{keyword}='' OR LOCATE(#{keyword},u.username)>0 OR LOCATE(#{keyword},u.display_name)>0
                    OR LOCATE(#{keyword},g.name)>0)
                AND (a.group_id=#{ticket.groupId} OR (#{ticket.status}!='CREATED'
                    AND (#{actor.admin}=TRUE OR (#{actor.leader}=TRUE AND source_group.leader_id=#{actor.id}
                        AND g.leader_id=#{actor.id}))))
            ORDER BY active_count,a.last_assigned_at,a.user_id LIMIT #{limit} OFFSET #{offset}
            """)
    List<Candidate> candidates(TicketModels.Ticket ticket, TicketActor actor, Long groupId, String keyword, int offset, int limit);

    @Select(SELECT + " WHERE " + TicketMapper.READ_SCOPE + """
            AND (#{view}='ACCESSIBLE'
                OR (#{view}='MINE_CREATED' AND t.user_id=#{actor.id})
                OR (#{view}='MINE_ASSIGNED' AND t.assignee_id=#{actor.id})
                OR (#{view}='MY_GROUP' AND (#{actor.admin}=TRUE OR g.leader_id=#{actor.id})))
            AND (#{groupId} IS NULL OR t.group_id=#{groupId})
            AND (#{queue}='ALL'
                OR (#{queue}='MANUAL' AND t.status='CREATED' AND t.assignee_id IS NULL)
                OR (#{queue}='ESCALATED' AND t.escalation_level>0
                    AND t.status IN ('CREATED','ASSIGNED','PROCESSING','PENDING')))
            AND (#{status} IS NULL OR t.status=#{status})
            AND (#{categoryId} IS NULL OR t.category_id=#{categoryId})
            AND (#{priority} IS NULL OR t.priority=#{priority})
            AND (#{keyword}='' OR LOCATE(#{keyword},t.title)>0 OR CAST(t.id AS CHAR)=#{keyword})
            AND (#{sla}='ALL'
            """ + " OR (#{sla}='BREACHED' AND (" + DEADLINE + ") <= #{now})"
            + " OR (#{sla}='WARNING' AND (" + DEADLINE + ") > #{now} AND (" + DEADLINE + ") <= #{warningAt}))"
            + " ORDER BY CASE WHEN #{view}='MINE_CREATED' AND t.status='PENDING' THEN 0 ELSE 1 END,"
            + " CASE WHEN #{view}!='MINE_CREATED' AND (" + DEADLINE + ") IS NOT NULL THEN 0 ELSE 1 END,"
            + " CASE WHEN #{view}!='MINE_CREATED' THEN (" + DEADLINE + ") ELSE NULL END, t.created_at DESC,t.id DESC"
            + " LIMIT #{limit} OFFSET #{offset}")
    List<Row> list(TicketActor actor, View view, TicketStatus status, Long categoryId, Priority priority,
            String keyword, SlaFilter sla, Long groupId, Queue queue, Instant now, Instant warningAt, int offset, int limit);
}
