package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.common.*;
import io.github.xw66.opsflow.redis.RedisGuard;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class TicketCreationService {
    private final TicketService tickets;
    private final TicketMapper data;
    private final RedisGuard redis;
    private final ObjectMapper json;
    private final boolean redisEnabled;
    public TicketCreationService(TicketService tickets, TicketMapper data, RedisGuard redis, ObjectMapper json,
            @Value("${opsflow.idempotency.redis-enabled:true}") boolean redisEnabled) {
        this.tickets = tickets; this.data = data; this.redis = redis; this.json = json; this.redisEnabled = redisEnabled;
    }

    public Detail create(String key, TicketInput input, TicketActor actor) {
        if (key == null || !key.matches("[a-zA-Z0-9_-]{8,64}")) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "幂等键须为8至64位字母、数字、下划线或连字符");
        String hash = ContentHash.sha256(json.writeValueAsString(List.of(input.title(), input.description(),
                input.categoryId(), input.priority(), new TreeSet<>(input.tags()))));
        var existing = data.creation(actor.id(), key);
        if (existing != null) return replay(existing, hash);
        String redisKey = "opsflow:create:" + actor.id() + ":" + key, owner = UUID.randomUUID().toString();
        boolean claimed = false;
        if (redisEnabled) {
            try {
                long result = redis.claim(redisKey, hash, owner);
                if (result < 0) conflict();
                if (result == 0) {
                    existing = data.creation(actor.id(), key);
                    if (existing != null) return replay(existing, hash);
                    throw new BusinessException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "相同请求正在处理中，请使用原幂等键重试");
                }
                claimed = true;
            } catch (DataAccessException ignored) { }
        }
        try {
            // 数据库唯一请求键与工单、响应快照、Outbox同事务，租约过期或Redis不可用仍不能重复建单。
            try { return tickets.create(input, actor, key, hash); }
            catch (DuplicateKeyException ex) {
                existing = data.creation(actor.id(), key);
                if (existing == null) throw ex;
                return replay(existing, hash);
            }
        } finally {
            if (claimed) try { redis.release(redisKey, hash, owner); } catch (DataAccessException ignored) { }
        }
    }

    private Detail replay(TicketMapper.Creation existing, String hash) {
        if (!hash.equals(existing.createRequestHash())) conflict();
        return json.readValue(existing.createResponse(), Detail.class);
    }
    private void conflict() { throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "相同幂等键不能用于不同请求内容"); }
}
