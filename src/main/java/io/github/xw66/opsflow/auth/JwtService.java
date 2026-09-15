package io.github.xw66.opsflow.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final long ttl;

    public JwtService(JwtEncoder encoder, Clock clock, @Value("${opsflow.jwt.issuer}") String issuer,
            @Value("${opsflow.jwt.ttl-seconds}") long ttl) {
        if (ttl < 60 || ttl > 3600) throw new IllegalArgumentException("JWT 有效期必须为60至3600秒");
        this.encoder = encoder;
        this.clock = clock;
        this.issuer = issuer;
        this.ttl = ttl;
    }

    public AuthController.TokenView issue(long userId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(Long.toString(userId))
                .audience(List.of("opsflow-api")).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(ttl))
                .id(UUID.randomUUID().toString()).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new AuthController.TokenView(token, "Bearer", ttl);
    }
}
