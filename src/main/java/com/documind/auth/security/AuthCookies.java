package com.documind.auth.security;

import java.time.Duration;
import java.net.URI;
import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookies {
    public static final String REFRESH = "refresh_token";
    public static final String CSRF = "documind_csrf";
    public static final String PATH = "/api/v1/auth";
    private final boolean secure;

    public AuthCookies(Environment environment, AuthSettings settings) {
        String[] profiles = environment.getActiveProfiles();
        boolean devOnly = profiles.length == 1 && "dev".equals(profiles[0]);
        secure = !(devOnly && settings.allowedOrigins().stream().allMatch(AuthCookies::isLocalHttp));
    }

    public ResponseCookie create(String name, String value, Duration lifetime) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secure)
                .sameSite("Lax").path(PATH).maxAge(lifetime).build();
    }

    public static String read(HttpServletRequest request, String name) {
        String value = null;
        boolean found = false;
        if (request.getCookies() != null) for (var cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                if (found) return null;
                found = true;
                value = cookie.getValue();
            }
        }
        return value;
    }

    /** An ambiguous refresh cookie must never be treated as a successful logout. */
    public static String readRefresh(HttpServletRequest request) {
        String value = null;
        boolean found = false;
        if (request.getCookies() != null) for (var cookie : request.getCookies()) {
            if (REFRESH.equals(cookie.getName())) {
                if (found) throw new AppException(ErrorCode.AMBIGUOUS_REFRESH_COOKIE);
                found = true;
                value = cookie.getValue();
            }
        }
        return value;
    }

    private static boolean isLocalHttp(String origin) {
        URI uri = URI.create(origin);
        String host = uri.getHost();
        return "http".equals(uri.getScheme())
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host));
    }
}
