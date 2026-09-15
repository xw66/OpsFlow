package io.github.xw66.opsflow.redis;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisGuard {
    private static final DefaultRedisScript<Long> CLAIM = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current then
                if string.sub(current, 1, 64) ~= ARGV[1] then return -1 end
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[1] .. ':' .. ARGV[2], 'EX', 15)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count >= tonumber(ARGV[1]) then return math.max(1, redis.call('PTTL', KEYS[1])) end
            count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    public RedisGuard(StringRedisTemplate redis) { this.redis = redis; }
    public long claim(String key, String hash, String owner) { return redis.execute(CLAIM, List.of(key), hash, owner); }
    public void release(String key, String hash, String owner) { redis.execute(RELEASE, List.of(key), hash + ":" + owner); }
    public long limit(String key, int maximum, long windowMillis) {
        if (maximum < 1 || windowMillis < 1) throw new IllegalArgumentException("限流参数必须为正数");
        return redis.execute(LIMIT, List.of(key), Integer.toString(maximum), Long.toString(windowMillis));
    }
}
