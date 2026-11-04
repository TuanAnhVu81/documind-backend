package com.documind.auth.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import static org.assertj.core.api.Assertions.*;

class AuthSecurityTest {
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);

    private AuthSettings settings(String... origins) {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        return new AuthSettings(Base64.getEncoder().encodeToString(key), "documind",
                List.of(origins), 2, 2, 60);
    }

    @Test void csrfBindsMatchingCookieToHeaderAndExpires() {
        AuthSettings settings = settings("https://documind.test");
        SignedCsrfTokens tokens = new SignedCsrfTokens(settings, NOW);
        String one = tokens.issue(), two = tokens.issue();
        assertThat(tokens.matches(one, one)).isTrue();
        assertThat(tokens.matches(one, two)).isFalse();
        assertThat(tokens.matches(one, null)).isFalse();
        assertThat(tokens.valid("unsigned")).isFalse();
        assertThat(new SignedCsrfTokens(settings, Clock.offset(NOW, Duration.ofHours(1))).valid(one)).isFalse();
    }

    @Test void secureCookiesCanOnlyBeDisabledInDevAndRemainSecureWithProd() {
        AuthSettings local = settings("http://localhost:5173", "http://127.0.0.1:8080");
        AuthSettings publicOrigin = settings("https://documind.test");
        assertThat(new AuthCookies(new MockEnvironment(), local)
                .create(AuthCookies.REFRESH, "value", Duration.ZERO).isSecure()).isTrue();
        MockEnvironment dev = new MockEnvironment(); dev.setActiveProfiles("dev");
        assertThat(new AuthCookies(dev, local).create(AuthCookies.REFRESH, "value", Duration.ZERO).isSecure()).isFalse();
        assertThat(new AuthCookies(dev, publicOrigin).create(AuthCookies.REFRESH, "value", Duration.ZERO).isSecure()).isTrue();
        dev.setActiveProfiles("dev", "staging");
        assertThat(new AuthCookies(dev, local).create(AuthCookies.REFRESH, "value", Duration.ZERO).isSecure()).isTrue();
        dev.setActiveProfiles("dev", "prod");
        assertThat(new AuthCookies(dev, local).create(AuthCookies.REFRESH, "value", Duration.ZERO).isSecure()).isTrue();
    }

    @Test void invalidSecretsAndWildcardOriginsFailConfiguration() {
        assertThatThrownBy(() -> new AuthSettings("", "documind", List.of("https://documind.test"), 2, 2, 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthSettings(settings("https://documind.test").jwtSecret(), "documind", List.of("*"), 2, 2, 60))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void jwtRejectsSignedTokenWithoutExpiryOrUuidSubject() {
        AuthSettings settings = settings("https://documind.test");
        var config = new AuthSecurityConfiguration();
        JwtEncoder encoder = config.jwtEncoder(settings);
        JwtDecoder decoder = config.jwtDecoder(settings, NOW);
        var claims = JwtClaimsSet.builder().issuer("documind").subject("not-a-user-id")
                .issuedAt(NOW.instant()).expiresAt(NOW.instant().plusSeconds(900)).build();
        String badSubject = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        assertThatThrownBy(() -> decoder.decode(badSubject)).isInstanceOf(JwtException.class);
        var missingExpiry = JwtClaimsSet.builder().issuer("documind").subject(java.util.UUID.randomUUID().toString()).build();
        String noExpiry = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), missingExpiry)).getTokenValue();
        assertThatThrownBy(() -> decoder.decode(noExpiry)).isInstanceOf(JwtException.class);
    }
}
