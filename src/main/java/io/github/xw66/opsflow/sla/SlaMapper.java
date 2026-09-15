package io.github.xw66.opsflow.sla;

import io.github.xw66.opsflow.ticket.TicketModels.Ticket;
import io.github.xw66.opsflow.ticket.TicketActor;
import io.github.xw66.opsflow.ticket.TicketMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SlaMapper {
    @Select("""
            SELECT t.id,t.response_deadline AS deadline FROM ticket t
            WHERE t.status IN ('CREATED','ASSIGNED','PROCESSING','PENDING') AND t.first_response_at IS NULL
                AND t.response_deadline<=#{horizon}
                AND (t.response_deadline>#{afterDeadline} OR (t.response_deadline=#{afterDeadline} AND t.id>#{afterId}))
                AND NOT EXISTS(SELECT 1 FROM sla_event e WHERE e.ticket_id=t.id AND e.sla_cycle=1
                    AND e.type=CASE WHEN t.response_deadline<#{now} THEN 'RESPONSE_BREACHED' ELSE 'RESPONSE_WARNING' END)
            ORDER BY t.response_deadline,t.id LIMIT #{limit}
            """)
    List<Candidate> responses(Instant now, Instant horizon, Instant afterDeadline, long afterId, int limit);

    @Select("""
            SELECT t.id,t.resolve_deadline AS deadline FROM ticket t
            WHERE t.status IN ('CREATED','ASSIGNED','PROCESSING','PENDING') AND t.resolved_at IS NULL
                AND t.resolve_deadline<=#{horizon}
                AND (t.resolve_deadline>#{afterDeadline} OR (t.resolve_deadline=#{afterDeadline} AND t.id>#{afterId}))
                AND NOT EXISTS(SELECT 1 FROM sla_event e WHERE e.ticket_id=t.id AND e.sla_cycle=t.sla_cycle
                    AND e.type=CASE WHEN t.resolve_deadline<#{now} THEN 'RESOLVE_BREACHED' ELSE 'RESOLVE_WARNING' END)
            ORDER BY t.resolve_deadline,t.id LIMIT #{limit}
            """)
    List<Candidate> resolutions(Instant now, Instant horizon, Instant afterDeadline, long afterId, int limit);

    @Select("SELECT COUNT(*) FROM sla_event WHERE ticket_id=#{ticketId} AND sla_cycle=#{cycle} AND type=#{type}")
    int exists(long ticketId, int cycle, String type);

    @Update("""
            UPDATE ticket SET response_breached=response_breached OR #{responseBreach},
                resolve_breached=resolve_breached OR #{resolveBreach},
                escalation_level=GREATEST(escalation_level,#{escalation}),version=version+1,updated_at=#{now}
            WHERE id=#{before.id} AND version=#{before.version} AND status=#{before.status}
            """)
    int mark(Ticket before, boolean responseBreach, boolean resolveBreach, int escalation, Instant now);

    @Insert("""
            INSERT INTO sla_event(ticket_id,sla_cycle,type,deadline,observed_at,event_id)
            VALUES(#{ticketId},#{cycle},#{type},#{deadline},#{now},#{eventId})
            """)
    void insert(long ticketId, int cycle, String type, Instant deadline, Instant now, String eventId);

    @Select("SELECT * FROM sla_event WHERE ticket_id=#{ticketId} ORDER BY id LIMIT #{limit} OFFSET #{offset}")
    List<SlaEvent> events(long ticketId, int offset, int limit);

    @Select("SELECT " + TicketMapper.COLUMNS + """
             FROM ticket t JOIN support_group g ON g.id=t.group_id
            WHERE t.escalation_level>0 AND t.status IN ('CREATED','ASSIGNED','PROCESSING','PENDING')
                AND (#{actor.admin}=TRUE OR (#{actor.leader}=TRUE AND g.leader_id=#{actor.id}))
            ORDER BY t.resolve_deadline,t.id LIMIT #{limit} OFFSET #{offset}
            """)
    List<Ticket> escalations(TicketActor actor, int offset, int limit);

    public record Candidate(long id, Instant deadline) { }
    public record SlaEvent(long id, long ticketId, int slaCycle, String type, Instant deadline, Instant observedAt, String eventId) { }
}
