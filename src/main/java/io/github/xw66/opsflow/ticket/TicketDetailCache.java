package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.ticket.TicketModels.Detail;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

@Service
public class TicketDetailCache {
    private final StringRedisTemplate redis;
    private final TicketService tickets;
    private final TicketMapper data;
    private final ObjectMapper json;
    private final boolean enabled;
    public TicketDetailCache(StringRedisTemplate redis, TicketService tickets, TicketMapper data, ObjectMapper json,
            @Value("${opsflow.cache.enabled:true}") boolean enabled) {
        this.redis = redis; this.tickets = tickets; this.data = data; this.json = json; this.enabled = enabled;
    }

    public Detail detail(long id, TicketActor actor) {
        if (!enabled) return tickets.detail(id, actor);
        // 每次命中仍查询当前归属权限，转派或撤销组长权限后不能依赖旧缓存继续访问。
        if (data.canRead(id, actor) != 1) return tickets.detail(id, actor);
        try {
            String cached = redis.opsForValue().get(key(id));
            if (cached != null) {
                try {
                    Detail value = json.readValue(cached, Detail.class);
                    if (value != null && value.ticket() != null && value.ticket().id() == id && value.tags() != null) return value;
                } catch (RuntimeException ignored) { }
            }
        } catch (DataAccessException ignored) { }
        Detail value = tickets.detail(id, actor);
        // ponytail: 接受并发回填造成最多30秒陈旧详情，写操作始终使用数据库version；强一致需求再引入版本缓存键。
        try { redis.opsForValue().set(key(id), json.writeValueAsString(value), Duration.ofSeconds(30)); }
        catch (DataAccessException ignored) { }
        return value;
    }

    @TransactionalEventListener
    public void changed(TicketChanged event) {
        if (enabled) try { redis.delete(key(event.ticketId())); } catch (DataAccessException ignored) { }
    }

    private String key(long id) { return "opsflow:ticket:" + id; }
}
