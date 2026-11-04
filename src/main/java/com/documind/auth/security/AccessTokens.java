package com.documind.auth.security;

import java.time.Clock;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
public class AccessTokens {
    public static final long LIFETIME_SECONDS = 900;
    private final JwtEncoder encoder;
    private final AuthSettings settings;
    private final Clock clock;

    public AccessTokens(JwtEncoder encoder, AuthSettings settings, Clock clock) {
        this.encoder = encoder; this.settings = settings; this.clock = clock;
    }

    public String issue(UUID userId) {
        var now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(settings.issuer()).subject(userId.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(LIFETIME_SECONDS)).id(UUID.randomUUID().toString()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
