package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.ticket.TicketModels.*;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowMapper {
    @Select("""
            SELECT a.user_id FROM support_agent a JOIN app_user u ON u.id=a.user_id
            WHERE a.group_id=#{groupId} AND a.online=TRUE AND a.enabled=TRUE AND u.enabled=TRUE
                AND EXISTS(SELECT 1 FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.user_id=a.user_id AND r.code='AGENT')
            ORDER BY (SELECT COUNT(*) FROM ticket t WHERE t.assignee_id=a.user_id AND t.status IN ('ASSIGNED','PROCESSING','PENDING')),
                a.last_assigned_at,a.user_id LIMIT 1
            """)
    Long leastLoaded(long groupId);

    @Select("""
            SELECT COUNT(*) FROM support_agent a JOIN app_user u ON u.id=a.user_id
            JOIN support_group g ON g.id=a.group_id
            WHERE a.user_id=#{userId} AND a.group_id=#{groupId} AND a.enabled=TRUE AND a.online=TRUE
                AND g.enabled=TRUE AND u.enabled=TRUE
                AND EXISTS(SELECT 1 FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.user_id=a.user_id AND r.code='AGENT')
            """)
    int eligible(long userId, long groupId);

    @Update("""
            UPDATE ticket SET assignee_id=#{assigneeId},group_id=#{groupId},status=#{target},version=version+1,updated_at=#{now}
            WHERE id=#{before.id} AND version=#{before.version} AND status=#{before.status}
                AND EXISTS(SELECT 1 FROM support_agent a JOIN app_user u ON u.id=a.user_id
                    JOIN support_group g ON g.id=a.group_id WHERE a.user_id=#{assigneeId} AND a.group_id=#{groupId}
                    AND a.online=TRUE AND a.enabled=TRUE AND u.enabled=TRUE AND g.enabled=TRUE
                    AND EXISTS(SELECT 1 FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.user_id=a.user_id AND r.code='AGENT'))
            """)
    int assign(Ticket before, long assigneeId, long groupId, TicketStatus target, Instant now);

    @Update("UPDATE support_agent SET last_assigned_at=#{now} WHERE user_id=#{userId}")
    void assignedAt(long userId, Instant now);

    @Update("""
            UPDATE ticket SET status=#{target},version=version+1,updated_at=#{now},
                resolved_at=CASE WHEN #{target}='RESOLVED' THEN #{now} ELSE resolved_at END,
                closed_at=CASE WHEN #{target}='CLOSED' THEN #{now} ELSE closed_at END,
                first_response_at=CASE WHEN #{target} IN ('PENDING','RESOLVED') THEN COALESCE(first_response_at,#{now}) ELSE first_response_at END
            WHERE id=#{before.id} AND version=#{before.version} AND status=#{before.status}
            """)
    int transition(Ticket before, TicketStatus target, Instant now);

    @Update("""
            UPDATE ticket SET status='PROCESSING',version=version+1,updated_at=#{now},resolved_at=NULL,closed_at=NULL,
                sla_cycle=sla_cycle+1,cycle_started_at=#{now},resolve_deadline=#{deadline}
            WHERE id=#{before.id} AND version=#{before.version} AND status=#{before.status}
            """)
    int reopen(Ticket before, Instant now, Instant deadline);

    @Insert("""
            INSERT INTO ticket_assignment(ticket_id,from_assignee_id,to_assignee_id,from_group_id,to_group_id,operator_id,reason,ticket_version,created_at)
            VALUES(#{before.id},#{before.assigneeId},#{toAssigneeId},#{before.groupId},#{toGroupId},#{operatorId},#{reason},#{version},#{now})
            """)
    void assignment(Ticket before, long toAssigneeId, long toGroupId, Long operatorId, String reason, long version, Instant now);

    @Select("""
            SELECT a.*,f.display_name AS from_assignee_name,t.display_name AS to_assignee_name,
                fg.name AS from_group_name,tg.name AS to_group_name,o.display_name AS operator_name
            FROM ticket_assignment a LEFT JOIN app_user f ON f.id=a.from_assignee_id
                JOIN app_user t ON t.id=a.to_assignee_id JOIN support_group fg ON fg.id=a.from_group_id
                JOIN support_group tg ON tg.id=a.to_group_id LEFT JOIN app_user o ON o.id=a.operator_id
            WHERE a.ticket_id=#{ticketId} ORDER BY a.id LIMIT #{limit} OFFSET #{offset}
            """)
    List<Assignment> assignments(long ticketId, int offset, int limit);

    @Select("SELECT id FROM ticket WHERE status='CREATED' AND assignee_id IS NULL AND id>#{afterId} ORDER BY id LIMIT #{limit}")
    List<Long> unassignedIds(long afterId, int limit);

    @Select("SELECT " + TicketMapper.COLUMNS + """
             FROM ticket t JOIN support_group g ON g.id=t.group_id
            WHERE t.status='CREATED' AND t.assignee_id IS NULL
                AND (#{actor.admin}=TRUE OR (#{actor.leader}=TRUE AND g.leader_id=#{actor.id}))
            ORDER BY t.id LIMIT #{limit} OFFSET #{offset}
            """)
    List<Ticket> manualQueue(TicketActor actor, int offset, int limit);
}
