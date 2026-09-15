package io.github.xw66.opsflow.config;

import io.github.xw66.opsflow.common.ApiResponse;
import io.github.xw66.opsflow.auth.UserMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper, UserMapper users) {
        return http
                // 只接受 Authorization Bearer 令牌，不使用 Cookie 或 Session 认证。
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/system/info", "/actuator/health",
                                "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/me").authenticated()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/support/**").authenticated()
                        .requestMatchers("/api/tickets/**").authenticated()
                        .requestMatchers("/api/workspace/tickets/**").authenticated()
                        .requestMatchers("/api/notifications/**").authenticated()
                        .requestMatchers("/api/statistics/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> authenticate(token, users)))
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            writeError(response, mapper, 401, "UNAUTHORIZED", "令牌无效或已过期");
                        }))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, mapper, 401, "UNAUTHORIZED", "请先登录"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, mapper, 403, "FORBIDDEN", "无权执行该操作")))
                .build();
    }

    private JwtAuthenticationToken authenticate(Jwt token, UserMapper users) {
        long id;
        try { id = Long.parseLong(token.getSubject()); }
        catch (NumberFormatException ex) { throw new OAuth2AuthenticationException("invalid_token"); }
        var user = users.findById(id);
        if (user == null || !user.enabled()) throw new OAuth2AuthenticationException("invalid_token");
        var authorities = users.roles(id).stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
        return new JwtAuthenticationToken(token, authorities, Long.toString(id));
    }

    private void writeError(HttpServletResponse response, ObjectMapper mapper,
            int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
