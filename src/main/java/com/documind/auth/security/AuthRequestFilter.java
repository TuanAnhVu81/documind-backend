package com.documind.auth.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.documind.common.security.SecurityProblemWriter;
import com.documind.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/** Installed only inside the auth security chain, ahead of controller invocation. */
public class AuthRequestFilter extends OncePerRequestFilter {
    private final AuthSettings settings;
    private final SignedCsrfTokens csrf;
    private final AuthRateLimiter limiter;
    private final SecurityProblemWriter problems;

    public AuthRequestFilter(AuthSettings settings, SignedCsrfTokens csrf,
                             AuthRateLimiter limiter, SecurityProblemWriter problems) {
        this.settings = settings; this.csrf = csrf; this.limiter = limiter; this.problems = problems;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if ("POST".equals(request.getMethod())) {
            if (java.util.Collections.list(request.getHeaders("Origin")).size() != 1
                    || request.getHeader("Origin") == null || !settings.allowedOrigins().contains(request.getHeader("Origin"))
                    || !csrf.matches(AuthCookies.read(request, AuthCookies.CSRF), request.getHeader(SignedCsrfTokens.HEADER))) {
                problems.write(request, response, ErrorCode.CSRF_INVALID);
                return;
            }
            String path = request.getServletPath();
            if (path.isEmpty()) path = UriUtils.decode(request.getRequestURI()
                    .substring(request.getContextPath().length()), StandardCharsets.UTF_8);
            boolean login = (AuthCookies.PATH + "/login").equals(path);
            boolean register = (AuthCookies.PATH + "/register").equals(path);
            if (login || register) {
                int max = login ? settings.loginMaxAttempts() : settings.registerMaxAttempts();
                long retry = limiter.retryAfter(path + "|" + request.getRemoteAddr(), max);
                if (retry > 0) {
                    response.setHeader("Retry-After", Long.toString(retry));
                    problems.write(request, response, ErrorCode.RATE_LIMITED);
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
