package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.support.SupportModels.Policy;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TicketMapper {
    String READ_SCOPE = """
            (#{actor.admin}=TRUE OR t.user_id=#{actor.id}
                OR (#{actor.agent}=TRUE AND t.assignee_id=#{actor.id})
                OR (#{actor.leader}=TRUE AND g.leader_id=#{actor.id}))
            """;
    String COLUMNS = "t.id,t.user_id,t.category_id,t.group_id,t.assignee_id,t.title,t.description,t.priority,t.status,t.version,"
            + "t.sla_policy_id,t.response_minutes,t.resolve_minutes,t.auto_escalate,t.response_deadline,t.resolve_deadline,"
            + "t.first_response_at,t.resolved_at,t.closed_at,t.cancelled_at,t.created_at,t.updated_at,t.sla_cycle,t.cycle_started_at,"
            + "t.response_breached,t.resolve_breached,t.escalation_level";

    @Select("SELECT " + COLUMNS + " FROM ticket t WHERE t.id=#{id}")
    Ticket find(long id);

    @Select("SELECT COUNT(*) FROM ticket t JOIN support_group g ON g.id=t.group_id WHERE t.id=#{id} AND " + READ_SCOPE)
    int canRead(long id, TicketActor actor);

    @Select("""
            SELECT
            """ + COLUMNS + """
             FROM ticket t JOIN support_group g ON g.id=t.group_id
            WHERE
            """ + READ_SCOPE + """
            AND (#{status} IS NULL OR t.status=#{status})
            AND (#{categoryId} IS NULL OR t.category_id=#{categoryId})
            ORDER BY t.created_at DESC,t.id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<Ticket> list(TicketActor actor, TicketStatus status, Long categoryId, int offset, int limit);

    @Select("SELECT * FROM sla_policy WHERE category_id=#{categoryId} AND priority=#{priority} AND enabled=TRUE")
    Policy activePolicy(long categoryId, Priority priority);

    @Insert("""
            INSERT INTO ticket(user_id,category_id,group_id,title,description,priority,sla_policy_id,
                response_minutes,resolve_minutes,auto_escalate,response_deadline,resolve_deadline,created_at,updated_at,cycle_started_at,
                create_request_key,create_request_hash)
            VALUES(#{userId},#{input.categoryId},#{groupId},#{input.title},#{input.description},#{input.priority},#{policy.id},
                #{policy.responseMinutes},#{policy.resolveMinutes},#{policy.autoEscalate},#{responseDeadline},#{resolveDeadline},#{now},#{now},#{now},#{requestKey},#{requestHash})
            """)
    void insert(long userId, TicketInput input, long groupId, Policy policy, Instant responseDeadline, Instant resolveDeadline, Instant now,
            String requestKey, String requestHash);

    @Select("SELECT create_request_hash,create_response FROM ticket WHERE user_id=#{userId} AND create_request_key=#{key}")
    Creation creation(long userId, String key);
    @Update("UPDATE ticket SET create_response=#{response} WHERE id=#{id}")
    void creationResponse(long id, String response);
    public record Creation(String createRequestHash, String createResponse) { }

    @Select("SELECT LAST_INSERT_ID()")
    long insertedId();

    @Update("""
            UPDATE ticket SET title=#{input.title},description=#{input.description},category_id=#{input.categoryId},
                group_id=#{groupId},priority=#{input.priority},sla_policy_id=#{policy.id},response_minutes=#{policy.responseMinutes},
                resolve_minutes=#{policy.resolveMinutes},auto_escalate=#{policy.autoEscalate},response_deadline=#{responseDeadline},
                resolve_deadline=#{resolveDeadline},version=version+1,updated_at=#{now}
            WHERE id=#{id} AND version=#{version} AND status='CREATED'
            """)
    int edit(long id, long version, TicketInput input, long groupId, Policy policy,
            Instant responseDeadline, Instant resolveDeadline, Instant now);

    @Update("""
            UPDATE ticket SET status='CANCELLED',cancelled_at=#{now},updated_at=#{now},version=version+1
            WHERE id=#{id} AND version=#{version} AND status='CREATED'
            """)
    int cancel(long id, long version, Instant now);

    @Update("""
            UPDATE ticket SET version=version+1,updated_at=#{now},
                first_response_at=COALESCE(first_response_at,#{responseAt})
            WHERE id=#{id} AND version=#{version} AND status NOT IN ('CLOSED','CANCELLED')
            """)
    int touch(long id, long version, Instant now, Instant responseAt);

    @Insert("""
            INSERT INTO ticket_status_history(ticket_id,from_status,to_status,operator_id,remark,ticket_version,created_at)
            VALUES(#{ticketId},#{from},#{to},#{operatorId},#{remark},#{version},#{now})
            """)
    void history(long ticketId, TicketStatus from, TicketStatus to, Long operatorId, String remark, long version, Instant now);

    @Select("SELECT h.*,u.display_name AS operator_name FROM ticket_status_history h LEFT JOIN app_user u ON u.id=h.operator_id WHERE h.ticket_id=#{ticketId} ORDER BY h.id LIMIT #{limit} OFFSET #{offset}")
    List<History> histories(long ticketId, int offset, int limit);

    @Insert("INSERT INTO ticket_tag(name) VALUES(#{name}) ON DUPLICATE KEY UPDATE name=name")
    void tag(String name);
    @Insert("INSERT INTO ticket_tag_relation(ticket_id,tag_id) SELECT #{ticketId},id FROM ticket_tag WHERE name=#{name} ON DUPLICATE KEY UPDATE ticket_id=ticket_id")
    void linkTag(long ticketId, String name);
    @Delete("DELETE FROM ticket_tag_relation WHERE ticket_id=#{ticketId}")
    void unlinkTags(long ticketId);
    @Select("SELECT t.name FROM ticket_tag t JOIN ticket_tag_relation r ON r.tag_id=t.id WHERE r.ticket_id=#{ticketId} ORDER BY t.name")
    List<String> tags(long ticketId);

    @Insert("INSERT INTO ticket_comment(ticket_id,author_id,content,internal,created_at) VALUES(#{ticketId},#{authorId},#{content},#{internal},#{now})")
    void comment(long ticketId, long authorId, String content, boolean internal, Instant now);
    @Select("SELECT c.*,u.display_name AS author_name FROM ticket_comment c JOIN app_user u ON u.id=c.author_id WHERE c.ticket_id=#{ticketId} AND (#{staff}=TRUE OR c.internal=FALSE) ORDER BY c.id LIMIT #{limit} OFFSET #{offset}")
    List<Comment> comments(long ticketId, boolean staff, int offset, int limit);
    @Select("SELECT c.*,u.display_name AS author_name FROM ticket_comment c JOIN app_user u ON u.id=c.author_id WHERE c.id=#{id}")
    Comment commentById(long id);

    @Insert("""
            INSERT INTO ticket_attachment(ticket_id,uploader_id,original_name,storage_key,content_type,size_bytes,created_at)
            VALUES(#{ticketId},#{uploaderId},#{originalName},#{storageKey},#{contentType},#{sizeBytes},#{now})
            """)
    void attachment(long ticketId, long uploaderId, String originalName, String storageKey, String contentType, long sizeBytes, Instant now);
    @Select("SELECT * FROM ticket_attachment WHERE id=#{id} AND ticket_id=#{ticketId}")
    Attachment attachmentById(long ticketId, long id);
    @Select("SELECT * FROM ticket_attachment WHERE ticket_id=#{ticketId} ORDER BY id")
    List<Attachment> attachments(long ticketId);
    @Delete("DELETE FROM ticket_attachment WHERE id=#{id} AND ticket_id=#{ticketId}")
    void deleteAttachment(long ticketId, long id);
}
