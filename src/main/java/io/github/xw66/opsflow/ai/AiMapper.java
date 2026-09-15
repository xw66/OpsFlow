package io.github.xw66.opsflow.ai;

import io.github.xw66.opsflow.ai.AiModels.*;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AiMapper {
    String VIEW = "id,ticket_id,ticket_version,kind,status,result_json,requested_by,accepted_by,accepted_at,created_at,finished_at";
    @Select("SELECT " + VIEW + " FROM ticket_ai_analysis WHERE id=#{id}")
    Analysis find(long id);
    @Select("SELECT " + VIEW + " FROM ticket_ai_analysis WHERE id=#{id} FOR UPDATE")
    Analysis lock(long id);
    @Select("SELECT " + VIEW + " FROM ticket_ai_analysis WHERE ticket_id=#{ticketId} AND ticket_version=#{version} AND kind=#{kind}")
    Analysis snapshot(long ticketId, long version, Kind kind);
    @Select("SELECT " + VIEW + " FROM ticket_ai_analysis WHERE ticket_id=#{ticketId} AND (#{staff}=TRUE OR kind='CLASSIFICATION') ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Analysis> list(long ticketId, boolean staff, int offset, int limit);
    @Insert("""
            INSERT INTO ticket_ai_analysis(ticket_id,ticket_version,kind,status,source_event_id,source_event_hash,input_text,categories_json,requested_by,created_at)
            VALUES(#{ticketId},#{version},#{kind},#{status},#{eventId},#{hash},#{input},#{categories},#{actor},#{now})
            """)
    void insert(long ticketId, long version, Kind kind, String status, String eventId, String hash, String input, String categories, Long actor, Instant now);
    @Select("SELECT LAST_INSERT_ID()")
    long insertedId();
    @Update("UPDATE ticket_ai_analysis SET source_event_id=#{eventId} WHERE id=#{id}")
    void source(long id, String eventId);
    @Select("SELECT source_event_id FROM ticket_ai_analysis WHERE id=#{id}")
    String eventId(long id);
    @Update("UPDATE ticket_ai_analysis SET status='WAITING',source_event_id=NULL,source_event_hash=NULL,result_json=NULL,finished_at=NULL WHERE id=#{id} AND status IN ('FAILED','DISABLED')")
    int retry(long id);
    @Select("SELECT id,ticket_id,ticket_version,source_event_hash FROM ticket_ai_analysis WHERE source_event_id=#{eventId} FOR UPDATE")
    Source sourceByEvent(String eventId);
    @Update("UPDATE ticket_ai_analysis SET status='PENDING',source_event_hash=#{hash} WHERE id=#{id} AND status='WAITING'")
    void ready(long id, String hash);

    @Select("""
            SELECT id,kind,input_text,categories_json FROM ticket_ai_analysis
            WHERE status='PENDING' OR (status='PROCESSING' AND lease_until<=#{now})
            ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    Pending next(Instant now);
    @Update("UPDATE ticket_ai_analysis SET status='PROCESSING',lease_owner=#{owner},lease_until=#{until} WHERE id=#{id}")
    void claim(long id, String owner, Instant until);
    @Update("""
            UPDATE ticket_ai_analysis SET status=#{result.outcome},result_json=#{result.resultJson},finished_at=#{now},lease_owner=NULL,lease_until=NULL
            WHERE id=#{id} AND status='PROCESSING' AND lease_owner=#{owner} AND lease_until>#{now}
            """)
    int finish(long id, String owner, CallResult result, Instant now);
    @Insert("""
            INSERT INTO ai_call_log(analysis_id,invocation_id,model,input_tokens,output_tokens,duration_ms,attempts,outcome,error_code,applied,created_at)
            VALUES(#{id},#{owner},#{result.model},#{result.inputTokens},#{result.outputTokens},#{result.durationMs},#{result.attempts},
                #{result.outcome},#{result.errorCode},#{applied},#{now})
            """)
    void log(long id, String owner, CallResult result, boolean applied, Instant now);
    @Update("UPDATE ticket_ai_analysis SET status='ACCEPTED',accepted_by=#{actor},accepted_at=#{now} WHERE id=#{id} AND status='SUCCEEDED'")
    int accept(long id, long actor, Instant now);
    @Select("SELECT code FROM ticket_category c JOIN support_group g ON g.id=c.group_id WHERE c.enabled=TRUE AND g.enabled=TRUE ORDER BY c.id LIMIT 100")
    List<String> categories();
    @Select("SELECT id FROM ticket_category WHERE code=#{code} AND enabled=TRUE")
    Long categoryId(String code);
    @Select("SELECT content FROM ticket_comment WHERE ticket_id=#{ticketId} AND internal=FALSE ORDER BY id DESC LIMIT 50")
    List<String> recentComments(long ticketId);
    @Select("SELECT id,analysis_id,model,input_tokens,output_tokens,duration_ms,attempts,outcome,error_code,applied,created_at FROM ai_call_log WHERE analysis_id=#{id} ORDER BY id DESC LIMIT 100")
    List<CallLog> logs(long id);
    public record Analysis(long id, long ticketId, long ticketVersion, Kind kind, String status, String resultJson,
            Long requestedBy, Long acceptedBy, Instant acceptedAt, Instant createdAt, Instant finishedAt) { }
    public record Pending(long id, Kind kind, String inputText, String categoriesJson) { }
    public record Source(long id, long ticketId, long ticketVersion, String sourceEventHash) { }
    public record CallLog(long id, long analysisId, String model, Integer inputTokens, Integer outputTokens,
            long durationMs, int attempts, String outcome, String errorCode, boolean applied, Instant createdAt) { }
}
