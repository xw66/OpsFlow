package io.github.xw66.opsflow.event;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MessageMapper {
    @Insert("INSERT INTO consumed_event(consumer_name,event_id,payload_hash,processed_at) VALUES(#{consumer},#{eventId},#{hash},#{now})")
    void consumed(String consumer, String eventId, String hash, Instant now);

    @Select("SELECT payload_hash FROM consumed_event WHERE consumer_name=#{consumer} AND event_id=#{eventId}")
    String hash(String consumer, String eventId);

    @Insert("INSERT INTO notification(recipient_id,event_id,ticket_id,content,created_at) VALUES(#{recipient},#{eventId},#{ticketId},#{content},#{now})")
    void notify(long recipient, String eventId, long ticketId, String content, Instant now);

    @Select("SELECT * FROM notification WHERE recipient_id=#{recipient} ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Notification> notifications(long recipient, int offset, int limit);
    @Select("SELECT COUNT(*) FROM notification WHERE recipient_id=#{recipient} AND read_at IS NULL")
    int unread(long recipient);

    @Update("UPDATE notification SET read_at=COALESCE(read_at,#{now}) WHERE id=#{id} AND recipient_id=#{recipient}")
    int markRead(long id, long recipient, Instant now);

    @Insert("""
            INSERT INTO consumer_failure(consumer_name,event_id,topic,partition_no,offset_no,attempts,error_type,payload,created_at)
            VALUES(#{consumer},#{eventId},#{topic},#{partition},#{offset},#{attempt},#{errorType},#{payload},#{now})
            ON DUPLICATE KEY UPDATE attempts=GREATEST(attempts,#{attempt}),error_type=#{errorType},status='OPEN'
            """)
    void failure(String consumer, String eventId, String topic, int partition, long offset, int attempt, String errorType, String payload, Instant now);

    @Select("SELECT * FROM consumer_failure ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Failure> failures(int offset, int limit);

    @Select("SELECT * FROM consumer_failure WHERE id=#{id} FOR UPDATE")
    Failure failureById(long id);

    @Update("UPDATE consumer_failure SET status='REQUEUED' WHERE id=#{id}")
    void requeued(long id);

    @Update("UPDATE consumer_failure SET status='RESOLVED',resolved_at=#{now} WHERE consumer_name=#{consumer} AND event_id=#{eventId} AND status<>'RESOLVED'")
    void resolved(String consumer, String eventId, Instant now);

    public record Notification(long id, long recipientId, String eventId, long ticketId, String content, Instant readAt, Instant createdAt) { }
    public record Failure(long id, String consumerName, String eventId, String topic, int partitionNo, long offsetNo,
            int attempts, String errorType, String payload, String status, Instant createdAt, Instant resolvedAt) { }
}
