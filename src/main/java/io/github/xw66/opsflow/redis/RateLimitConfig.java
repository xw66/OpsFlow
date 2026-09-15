package io.github.xw66.opsflow.redis;

import io.github.xw66.opsflow.common.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RateLimitConfig implements WebMvcConfigurer, HandlerInterceptor {
    private final RedisGuard redis;
    private final boolean enabled;
    public RateLimitConfig(RedisGuard redis, @Value("${opsflow.rate-limit.enabled:true}") boolean enabled) {
        this.redis = redis; this.enabled = enabled;
    }
    @Override
    public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this).addPathPatterns("/api/**"); }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled) return true;
        String route = String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        if (route.equals("/api/system/info")) return true;
        boolean sensitive = route.equals("/api/auth/login") || route.equals("/api/auth/register");
        String identity = sensitive || request.getUserPrincipal() == null ? "ip:" + request.getRemoteAddr()
                : "user:" + request.getUserPrincipal().getName();
        String key = "opsflow:limit:" + ContentHash.sha256(request.getMethod() + ":" + route + ":" + identity);
        try {
            long retryMillis = redis.limit(key, sensitive ? 10 : 120, 60000);
            if (retryMillis > 0) {
                response.setHeader("Retry-After", Long.toString((retryMillis + 999) / 1000));
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求过于频繁，请稍后重试");
            }
        } catch (DataAccessException ex) {
            if (sensitive) {
                response.setHeader("Retry-After", "1");
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMIT_UNAVAILABLE", "登录保护暂不可用，请稍后重试");
            }
        }
        return true;
    }
}
