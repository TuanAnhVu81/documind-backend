package com.documind.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class SignedCsrfTokens {
    public static final String HEADER = "X-CSRF-TOKEN";
    public static final long LIFETIME_SECONDS = 3600;
    private final byte[] key;
    private final Clock clock;

    public SignedCsrfTokens(AuthSettings settings, Clock clock) {
        key = TokenSecrets.hmac(Base64.getDecoder().decode(settings.jwtSecret()), "documind/csrf/v1");
        this.clock = clock;
    }

    public String issue() {
        String payload = TokenSecrets.randomToken() + "." + clock.instant().plusSeconds(LIFETIME_SECONDS).getEpochSecond();
        return payload + "." + signature(payload);
    }

    public boolean valid(String token) {
        if (token == null || token.length() > 256) return false;
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !parts[0].matches("[A-Za-z0-9_-]{43}")) return false;
        try {
            long expires = Long.parseLong(parts[1]);
            if (expires <= clock.instant().getEpochSecond()) return false;
            return equal(signature(parts[0] + "." + parts[1]), parts[2]);
        } catch (NumberFormatException ex) { return false; }
    }

    public boolean matches(String cookie, String header) { return valid(cookie) && equal(cookie, header); }

    private String signature(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(TokenSecrets.hmac(key, payload));
    }

    private static boolean equal(String left, String right) {
        return right != null && right.length() <= 256 && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
}
