package com.documind.common.security;

import com.documind.auth.security.*;
import com.documind.common.error.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain authFilterChain(HttpSecurity http, AuthSettings settings, SignedCsrfTokens csrf,
                                        AuthRateLimiter limiter, SecurityProblemWriter problems) throws Exception {
        stateless(http, problems);
        http.securityMatcher("/api/v1/auth/**")
                .addFilterBefore(new AuthRequestFilter(settings, csrf, limiter, problems), AuthorizationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .anyRequest().denyAll());
        // Auth uses cookie + CSRF; an expired bearer header does not block refresh/logout.
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain businessFilterChain(HttpSecurity http, Environment environment,
                                            SecurityProblemWriter problems) throws Exception {
        stateless(http, problems);
        http.oauth2ResourceServer(resource -> resource.jwt(jwt -> {})
                        .authenticationEntryPoint((request, response, exception) -> problems.write(request, response,
                                ErrorCode.INVALID_ACCESS_TOKEN)))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll();
                    if (environment.acceptsProfiles(Profiles.of("dev", "test"))) {
                        authorize.requestMatchers("/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll();
                    }
                    authorize.requestMatchers("/api/v1/**").authenticated();
                    authorize.anyRequest().denyAll();
                });
        return http.build();
    }

    private void stateless(HttpSecurity http, SecurityProblemWriter problems) throws Exception {
        http.formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> problems.write(request, response,
                                ErrorCode.AUTHENTICATION_REQUIRED))
                        .accessDeniedHandler((request, response, exception) -> problems.write(request, response,
                                ErrorCode.ACCESS_DENIED)));
    }
}
