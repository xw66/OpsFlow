package io.github.xw66.opsflow.event;

import java.time.Instant;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OutboxMapper {
    @Insert("""
            INSERT INTO outbox_event(event_id, aggregate_id, aggregate_version, event_type, schema_version, payload, created_at, available_at)
            VALUES(#{eventId}, #{ticketId}, #{version}, #{type}, 1, #{payload}, #{now}, #{now})
            """)
    void insert(String eventId, long ticketId, long version, String type, String payload, Instant now);

    @Select("""
            SELECT event_id,aggregate_id,payload,attempts FROM outbox_event
            WHERE (status='PENDING' AND available_at<=#{now}) OR (status='PROCESSING' AND lease_until<=#{now})
            ORDER BY available_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    Pending next(Instant now);

    @Update("UPDATE outbox_event SET status='PROCESSING',lease_owner=#{owner},lease_until=#{until},attempts=attempts+1 WHERE event_id=#{id}")
    void claim(String id, String owner, Instant until);

    @Update("""
            UPDATE outbox_event SET status='SENT',sent_at=#{now},lease_owner=NULL,lease_until=NULL,last_error=NULL
            WHERE event_id=#{id} AND status='PROCESSING' AND lease_owner=#{owner} AND lease_until>#{now}
            """)
    int sent(String id, String owner, Instant now);

    @Update("""
            UPDATE outbox_event SET status='PENDING',available_at=#{availableAt},last_error=#{error},lease_owner=NULL,lease_until=NULL
            WHERE event_id=#{id} AND status='PROCESSING' AND lease_owner=#{owner}
            """)
    int failed(String id, String owner, String error, Instant availableAt);

    @Update("""
            UPDATE outbox_event SET status='PENDING',available_at=#{now},last_error=NULL,sent_at=NULL
            WHERE event_id=#{id} AND status='SENT'
            """)
    int requeue(String id, Instant now);

    public record Pending(String eventId, long aggregateId, String payload, int attempts) { }
}
