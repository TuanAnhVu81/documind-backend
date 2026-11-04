package com.documind.auth.security;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("documind.auth")
public record AuthSettings(String jwtSecret, String issuer, List<String> allowedOrigins,
                           int loginMaxAttempts, int registerMaxAttempts, int loginWindowSeconds) {
    public AuthSettings {
        try {
            if (jwtSecret == null || Base64.getDecoder().decode(jwtSecret).length < 32)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("JWT_SECRET must be Base64 encoding of at least 32 random bytes");
        }
        if (issuer == null || issuer.isBlank() || allowedOrigins == null || allowedOrigins.isEmpty()
                || loginMaxAttempts < 1 || registerMaxAttempts < 1 || loginWindowSeconds < 1)
            throw new IllegalArgumentException("Auth issuer, allowed origins and login limits are required");
        allowedOrigins = List.copyOf(allowedOrigins);
        for (String origin : allowedOrigins) {
            URI uri = URI.create(origin);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !uri.getRawPath().isEmpty())
                throw new IllegalArgumentException("Allowed origins must be exact HTTP(S) origins without paths");
        }
    }

    // Never include the signing secret in logs/configuration diagnostics.
    @Override public String toString() { return "AuthSettings[secret=REDACTED, issuer=" + issuer + "]"; }
}
