package com.documind;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import com.documind.auth.RefreshTokenRepository;
import com.documind.auth.security.AuthCookies;
import com.documind.auth.security.TokenSecrets;
import com.documind.document.DocumentRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(AuthIT.TestBeans.class)
class AuthIT {
    private static PostgreSQLContainer container;
    private static final String ORIGIN = "https://documind.test";
    private static final String PASSWORD = "Demo-Password-123!";
    private static final AtomicInteger PEERS = new AtomicInteger();
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper mapper;
    @Autowired MutableClock clock;
    @Autowired PasswordEncoder passwords;
    @Autowired AuditedPasswordEncoder auditedPasswords;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired PlatformTransactionManager transactionManager;
    private String peer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Always disposable: cleanup in this test can never target a configured local DB.
        container = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.7-pg15")
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("documind_auth_test").withUsername("auth_test")
                .withPassword(UUID.randomUUID().toString());
        container.start();
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
        registry.add("spring.flyway.user", container::getUsername);
        registry.add("spring.flyway.password", container::getPassword);
        registry.add("documind.auth.jwt-secret", () -> key);
        registry.add("documind.auth.allowed-origins", () -> ORIGIN);
    }

    @BeforeEach void resetClock() {
        clock.set(Instant.now());
        auditedPasswords.reset();
        peer = "10.0.0." + PEERS.incrementAndGet();
    }
    @AfterEach void cleanFixtures() { jdbc.sql("TRUNCATE users CASCADE").update(); }
    @AfterAll static void stop() { if (container != null) container.stop(); }

    @Test
    void registrationNormalizationValidationPasswordHashingAndGenericLoginFailure() throws Exception {
        Csrf csrf = csrf();
        MvcResult created = mvc.perform(postAuth("register", csrf).content(credentials("  User@Example.COM  ", PASSWORD)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.accessToken").doesNotExist()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();
        String id = json(created, "id");
        String hash = jdbc.sql("select password_hash from users where id=:id").param("id", UUID.fromString(id)).query(String.class).single();
        assertThat(hash).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        mvc.perform(postAuth("register", csrf).content(credentials("USER@example.com", PASSWORD)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        mvc.perform(postAuth("register", csrf).content(credentials("invalid-email", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors.email").value("Giá trị không hợp lệ."))
                .andExpect(jsonPath("$.errors.password").value("Giá trị không hợp lệ."))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("invalid-email"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("short"))));
        mvc.perform(postAuth("register", csrf).content(credentials("new@example.com", "ệ".repeat(30))))
                .andExpect(status().isBadRequest());
        String wrong = mvc.perform(postAuth("login", csrf).content(credentials("user@example.com", "Wrong-password")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_INVALID"))
                .andExpect(jsonPath("$.requestId").isNotEmpty()).andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(postAuth("login", csrf).content(credentials("missing@example.com", "Wrong-password")))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(wrong).get("detail").asString()).isEqualTo(mapper.readTree(missing).get("detail").asString());
    }

    @Test
    void passwordHashingAndVerificationDoNotHoldDatabaseTransactions() throws Exception {
        Csrf csrf = csrf();
        registerAndLogin(csrf);
        assertThat(auditedPasswords.encodeInTransaction.get()).isFalse();
        assertThat(auditedPasswords.matchInTransaction.get()).isFalse();
    }

    @Test
    void existingV2MixedCaseEmailCanLoginAndCannotBeRegisteredAgain() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,:hash)")
                .param("id", id).param("email", "  Legacy@Example.COM  ").param("hash", passwords.encode(PASSWORD)).update();
        Csrf csrf = csrf();
        mvc.perform(postAuth("login", csrf).content(credentials("legacy@example.com", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(id.toString()));
        mvc.perform(postAuth("register", csrf).content(credentials("LEGACY@example.com", PASSWORD)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void v2UpgradePreservesMixedCaseAndRejectsAmbiguousAccountsWithoutDataLoss() throws Exception {
        String first = createUpgradeDatabase();
        String second = null;
        try {
            second = createUpgradeDatabase();
            String firstUrl = upgradeUrl(first);
            Flyway.configure().dataSource(firstUrl, container.getUsername(), container.getPassword()).target("2").load().migrate();
            UUID legacyId = insertUpgradeUser(firstUrl, " Legacy@Example.COM ");
            Flyway.configure().dataSource(firstUrl, container.getUsername(), container.getPassword()).load().migrate();
            try (Connection connection = DriverManager.getConnection(firstUrl, container.getUsername(), container.getPassword());
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("select id,email,password_hash from users")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getObject(1, UUID.class)).isEqualTo(legacyId);
                assertThat(rows.getString(2)).isEqualTo("legacy@example.com"); assertThat(rows.getString(3)).isEqualTo("fixture");
                assertThat(rows.next()).isFalse();
            }

            String secondUrl = upgradeUrl(second);
            Flyway.configure().dataSource(secondUrl, container.getUsername(), container.getPassword()).target("2").load().migrate();
            insertUpgradeUser(secondUrl, "Duplicate@Example.COM");
            insertUpgradeUser(secondUrl, " duplicate@example.com ");
            assertThatThrownBy(() -> Flyway.configure().dataSource(secondUrl, container.getUsername(), container.getPassword()).load().migrate())
                    .hasMessageContaining("normalized email duplicates require manual resolution");
            try (Connection connection = DriverManager.getConnection(secondUrl, container.getUsername(), container.getPassword());
                 var statement = connection.createStatement(); var rows = statement.executeQuery("select count(*) from users")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(2);
            }
        } finally {
            jdbc.sql("DROP DATABASE IF EXISTS " + first).update();
            if (second != null) jdbc.sql("DROP DATABASE IF EXISTS " + second).update();
        }
    }

    @Test
    void onlyEmailUniquenessViolationIsReportedAsDuplicate() throws Exception {
        jdbc.sql("alter table users add constraint ck_test_registration check (email <> 'blocked@example.com')").update();
        try {
            Csrf csrf = csrf();
            mvc.perform(postAuth("register", csrf).content(credentials("blocked@example.com", PASSWORD)))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ck_test_registration"))));
        } finally {
            jdbc.sql("alter table users drop constraint ck_test_registration").update();
        }
    }

    @Test
    void registrationRateLimitIsSeparateFromLogin() throws Exception {
        Csrf csrf = csrf();
        mvc.perform(postAuth("register", csrf).content(credentials("known@example.com", PASSWORD))).andExpect(status().isCreated());
        for (int i = 1; i < 10; i++) mvc.perform(postAuth("register", csrf).content(credentials("known@example.com", PASSWORD)))
                .andExpect(status().isConflict());
        mvc.perform(postAuth("register", csrf).content(credentials("another@example.com", PASSWORD)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        mvc.perform(postAuth("login", csrf).content(credentials("known@example.com", PASSWORD))).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(60));
        mvc.perform(postAuth("register", csrf).content(credentials("known@example.com", PASSWORD))).andExpect(status().isConflict());
    }

    @Test
    void ambiguousRefreshCookiesNeverProduceFalseLogoutSuccess() throws Exception {
        Csrf csrf = csrf(); Login login = registerAndLogin(csrf);
        Cookie extra = new Cookie(AuthCookies.REFRESH, TokenSecrets.randomToken());
        mvc.perform(postAuth("logout", csrf).cookie(login.refresh(), extra))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mvc.perform(postAuth("refresh", csrf).cookie(login.refresh(), extra))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        assertThat(jdbc.sql("select count(*) from refresh_tokens where revoked_at is null").query(Long.class).single()).isEqualTo(1);
        Cookie child = cookie(mvc.perform(postAuth("refresh", csrf).cookie(login.refresh()))
                .andExpect(status().isOk()).andReturn(), AuthCookies.REFRESH);
        mvc.perform(postAuth("logout", csrf).cookie(child)).andExpect(status().isNoContent());
        assertThat(jdbc.sql("select count(*) from refresh_tokens where revoked_at is null").query(Long.class).single()).isZero();
    }

    @Test
    void csrfReusesValidTokenAndRejectsMissingForgedExpiredAndWrongOrigin() throws Exception {
        Csrf csrf = csrf();
        mvc.perform(get("/api/v1/auth/csrf").cookie(csrf.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").value(csrf.token()))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(postAuth("logout", csrf).header("Origin", "https://evil.test"))
                .andExpect(status().isForbidden());
        mvc.perform(postAuth("logout", csrf).with(request -> {
            request.removeHeader("Origin"); request.addHeader("Origin", "https://evil.test"); return request;
        })).andExpect(status().isForbidden());
        mvc.perform(postAuth("logout", csrf).with(request -> { request.removeHeader("Origin"); return request; }))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").header("Origin", ORIGIN).header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isForbidden());
        String forged = csrf.token().substring(0, csrf.token().length() - 1) + "!";
        mvc.perform(post("/api/v1/auth/logout").header("Origin", ORIGIN).header("X-CSRF-TOKEN", forged)
                        .cookie(new Cookie(AuthCookies.CSRF, forged)))
                .andExpect(status().isForbidden());
        clock.advance(Duration.ofHours(1));
        mvc.perform(postAuth("logout", csrf)).andExpect(status().isForbidden());
    }

    @Test
    void jwtEnforcesSignatureExpiryAndPrincipalWhileCookieAloneCannotAuthenticate() throws Exception {
        Csrf csrf = csrf();
        Login login = registerAndLogin(csrf);
        mvc.perform(get("/api/v1/test/principal").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.access()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(login.userId().toString()));
        mvc.perform(post("/api/v1/test/principal").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.access())
                        .param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(login.userId().toString()));
        mvc.perform(get("/api/v1/test/principal").cookie(login.refresh())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/test/principal").header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        String[] pieces = login.access().split("\\.");
        String signature = (pieces[2].startsWith("A") ? "B" : "A") + pieces[2].substring(1);
        mvc.perform(get("/api/v1/test/principal").header(HttpHeaders.AUTHORIZATION, "Bearer " + pieces[0] + "." + pieces[1] + "." + signature))
                .andExpect(status().isUnauthorized());
        clock.advance(Duration.ofMinutes(15));
        mvc.perform(get("/api/v1/test/principal").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.access()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRaceReuseAndAbsoluteFamilyExpiry() throws Exception {
        Csrf csrf = csrf();
        Login login = registerAndLogin(csrf);
        Instant expires = tokenExpiry(login.refresh());
        MvcResult refreshed = mvc.perform(postAuth("refresh", csrf).cookie(login.refresh())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired-or-invalid"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiresIn").value(900)).andReturn();
        Cookie child = cookie(refreshed, AuthCookies.REFRESH);
        assertThat(child.getValue()).isNotEqualTo(login.refresh().getValue());
        assertThat(tokenExpiry(child)).isEqualTo(expires);
        assertThat(jdbc.sql("select count(*) from refresh_tokens where token_hash=:raw").param("raw", child.getValue()).query(Long.class).single()).isZero();
        mvc.perform(postAuth("refresh", csrf).cookie(login.refresh()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFRESH_RACE"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1")).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        clock.advance(Duration.ofSeconds(5));
        mvc.perform(postAuth("refresh", csrf).cookie(login.refresh()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFRESH_RACE"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        clock.advance(Duration.ofSeconds(1));
        mvc.perform(postAuth("refresh", csrf).cookie(login.refresh()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_REUSED"));
        mvc.perform(postAuth("refresh", csrf).cookie(child)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
        assertThat(jdbc.sql("select count(*) from refresh_tokens where revoked_at is null").query(Long.class).single()).isZero();
    }

    @Test
    void refreshAtFamilyExpiryIsInvalidAndChildNeverExtendsDeadline() throws Exception {
        Csrf csrf = csrf();
        Login login = registerAndLogin(csrf);
        clock.advance(Duration.ofDays(6));
        csrf = csrf();
        Cookie child = cookie(mvc.perform(postAuth("refresh", csrf).cookie(login.refresh()))
                .andExpect(status().isOk()).andReturn(), AuthCookies.REFRESH);
        assertThat(tokenExpiry(child)).isEqualTo(tokenExpiry(login.refresh()));
        clock.advance(Duration.ofDays(1));
        csrf = csrf();
        mvc.perform(postAuth("refresh", csrf).cookie(child)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
    }

    @Test
    void logoutIgnoresExpiredBearerAndIsIdempotentWithExactCookieAttributes() throws Exception {
        Csrf csrf = csrf();
        Login login = registerAndLogin(csrf);
        assertThat(login.refresh().getSecure()).isTrue();
        assertThat(login.refresh().isHttpOnly()).isTrue();
        assertThat(login.refresh().getPath()).isEqualTo(AuthCookies.PATH);
        assertThat(login.refresh().getDomain()).isNull();
        assertThat(login.refresh().getAttribute("SameSite")).isEqualTo("Lax");
        clock.advance(Duration.ofMinutes(16));
        MvcResult logout = mvc.perform(postAuth("logout", csrf).cookie(login.refresh())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.access()))
                .andExpect(status().isNoContent()).andReturn();
        Cookie deleted = cookie(logout, AuthCookies.REFRESH);
        assertThat(deleted.getMaxAge()).isZero(); assertThat(deleted.getPath()).isEqualTo(AuthCookies.PATH);
        assertThat(deleted.getSecure()).isTrue(); assertThat(deleted.isHttpOnly()).isTrue();
        mvc.perform(postAuth("logout", csrf).cookie(login.refresh())).andExpect(status().isNoContent());
        mvc.perform(postAuth("logout", csrf)).andExpect(status().isNoContent());
        mvc.perform(postAuth("refresh", csrf).cookie(login.refresh())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
    }

    @Test
    void simultaneousRefreshProducesExactlyOneChildAndDoesNotRevokeFamily() throws Exception {
        Csrf csrf = csrf(); Login login = registerAndLogin(csrf);
        UUID family = familyId(login.refresh());
        CountDownLatch acquired = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(3);
        try {
            var holder = executor.submit(() -> holdFamilyRoot(family, acquired, release));
            assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
            var one = executor.submit(() -> mvc.perform(postAuth("refresh", csrf).cookie(login.refresh())).andReturn());
            var two = executor.submit(() -> mvc.perform(postAuth("refresh", csrf).cookie(login.refresh())).andReturn());
            awaitWaitingFamilyLocks(2);
            release.countDown(); holder.get(10, TimeUnit.SECONDS);
            List<MvcResult> results = List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
            assertThat(results).extracting(r -> r.getResponse().getStatus()).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.sql("select count(*) from refresh_tokens").query(Long.class).single()).isEqualTo(2);
            assertThat(jdbc.sql("select count(*) from refresh_tokens where revoked_at is not null").query(Long.class).single()).isZero();
            var loser = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertThat(loser.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        } finally {
            release.countDown(); executor.shutdownNow(); executor.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void logoutRevokesRefreshButAlreadyIssuedJwtLastsUntilExpiry() throws Exception {
        Csrf csrf = csrf(); Login login = registerAndLogin(csrf);
        mvc.perform(postAuth("logout", csrf).cookie(login.refresh())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/test/principal").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.access()))
                .andExpect(status().isOk());
        mvc.perform(postAuth("refresh", csrf).cookie(login.refresh())).andExpect(status().isUnauthorized());
    }

    @Test
    void simultaneousRegistrationCreatesOneNormalizedAccount() throws Exception {
        Csrf csrf = csrf();
        CountDownLatch hashing = new CountDownLatch(2), release = new CountDownLatch(1);
        auditedPasswords.blockEncoding(hashing, release);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> mvc.perform(postAuth("register", csrf)
                    .content(credentials("Case@Example.COM", PASSWORD))).andReturn().getResponse().getStatus());
            var two = executor.submit(() -> mvc.perform(postAuth("register", csrf)
                    .content(credentials(" case@example.com ", PASSWORD))).andReturn().getResponse().getStatus());
            // Both existence checks have completed before either transaction can insert.
            assertThat(hashing.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
            assertThat(jdbc.sql("select count(*) from users").query(Long.class).single()).isEqualTo(1);
        } finally {
            release.countDown(); auditedPasswords.reset(); executor.shutdownNow(); executor.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void refreshConcurrentWithLogoutLeavesEntireFamilyRevoked() throws Exception {
        Csrf csrf = csrf(); Login login = registerAndLogin(csrf);
        Cookie child = cookie(mvc.perform(postAuth("refresh", csrf).cookie(login.refresh()))
                .andExpect(status().isOk()).andReturn(), AuthCookies.REFRESH);
        UUID family = familyId(login.refresh());
        CountDownLatch acquired = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(3);
        try {
            var holder = executor.submit(() -> holdFamilyRoot(family, acquired, release));
            assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
            var refresh = executor.submit(() -> mvc.perform(postAuth("refresh", csrf).cookie(child)).andReturn());
            var logout = executor.submit(() -> mvc.perform(postAuth("logout", csrf).cookie(login.refresh())).andReturn());
            awaitWaitingFamilyLocks(2);
            release.countDown(); holder.get(10, TimeUnit.SECONDS);
            MvcResult refreshed = refresh.get(15, TimeUnit.SECONDS);
            assertThat(refreshed.getResponse().getStatus()).isIn(200, 401);
            assertThat(logout.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(204);
            assertThat(jdbc.sql("select count(*) from refresh_tokens where revoked_at is null").query(Long.class).single()).isZero();
            if (refreshed.getResponse().getStatus() == 200) {
                mvc.perform(postAuth("refresh", csrf).cookie(cookie(refreshed, AuthCookies.REFRESH)))
                        .andExpect(status().isUnauthorized());
            }
        } finally {
            release.countDown(); executor.shutdownNow(); executor.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void ownerIsolationUsesVerifiedPrincipalAndBusinessApiNeedsNoCsrf() throws Exception {
        Csrf csrf = csrf(); Login login = registerAndLogin(csrf);
        UUID other = UUID.randomUUID(), doc = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'fixture')")
                .param("id", other).param("email", other + "@example.invalid").update();
        jdbc.sql("insert into documents(id,user_id,filename,storage_key,media_type,size_bytes,status) values (:id,:owner,'fixture',:key,'text/plain',1,'PENDING')")
                .param("id", doc).param("owner", other).param("key", doc.toString()).update();
        mvc.perform(get("/api/v1/test/documents/" + doc).header(HttpHeaders.AUTHORIZATION, "Bearer " + login.access())
                        .param("userId", other.toString())).andExpect(status().isNotFound());
    }

    @Test
    void loginLimitAppliesToKnownAndUnknownAccountsAndResetsAfterWindow() throws Exception {
        Csrf csrf = csrf();
        mvc.perform(postAuth("register", csrf).content(credentials("known@example.com", "Other-password")))
                .andExpect(status().isCreated());
        for (int i = 0; i < 10; i++) mvc.perform(postAuth("login", csrf)
                        .content(credentials(i % 2 == 0 ? "known@example.com" : "missing@example.com", PASSWORD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(postAuth("login", csrf).content(credentials("another@example.com", PASSWORD)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        clock.advance(Duration.ofSeconds(60));
        mvc.perform(postAuth("login", csrf).content(credentials("missing@example.com", PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingOrUnknownRefreshIsInvalidAndOpenApiMarksAuthPublic() throws Exception {
        Csrf csrf = csrf();
        mvc.perform(postAuth("refresh", csrf)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_INVALID"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mvc.perform(postAuth("refresh", csrf).cookie(new Cookie(AuthCookies.REFRESH, TokenSecrets.randomToken())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
        var result = mvc.perform(get("/v3/api-docs")).andDo(response -> {
            if (response.getResolvedException() != null)
                throw new AssertionError("OpenAPI generation failed", response.getResolvedException());
        }).andExpect(status().isOk()).andReturn();
        var paths = mapper.readTree(result.getResponse().getContentAsByteArray()).get("paths");
        var security = paths.get("/api/v1/auth/login").get("post").get("security");
        assertThat(security).isNotNull(); assertThat(security.isArray() && security.isEmpty()).isTrue();
        assertThat(paths.get("/api/v1/auth/refresh").get("post").get("responses").has("409")).isTrue();
    }

    private record Csrf(Cookie cookie, String token) {}
    private record Login(Cookie refresh, String access, UUID userId) {}

    private Csrf csrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn();
        return new Csrf(cookie(result, AuthCookies.CSRF), json(result, "token"));
    }
    private MockHttpServletRequestBuilder postAuth(String path, Csrf csrf) {
        return post("/api/v1/auth/" + path).contentType(MediaType.APPLICATION_JSON)
                .header("Origin", ORIGIN).header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                .with(request -> { request.setRemoteAddr(peer); return request; });
    }
    private Login registerAndLogin(Csrf csrf) throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        mvc.perform(postAuth("register", csrf).content(credentials(email, PASSWORD))).andExpect(status().isCreated());
        MvcResult result = mvc.perform(postAuth("login", csrf).content(credentials(email, PASSWORD)))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn();
        return new Login(cookie(result, AuthCookies.REFRESH), json(result, "accessToken"),
                UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("user").get("id").asString()));
    }
    private String credentials(String email, String password) { return mapper.writeValueAsString(Map.of("email",email,"password",password)); }
    private String json(MvcResult result, String name) { return mapper.readTree(result.getResponse().getContentAsByteArray()).get(name).asString(); }
    private Cookie cookie(MvcResult result, String name) {
        return MockCookie.parse(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(name + "=")).findFirst().orElseThrow());
    }
    private Instant tokenExpiry(Cookie cookie) {
        return jdbc.sql("select expires_at from refresh_tokens where token_hash=:hash")
                .param("hash", TokenSecrets.hash(cookie.getValue())).query((rs, row) -> rs.getTimestamp(1).toInstant()).single();
    }
    private UUID familyId(Cookie cookie) {
        return jdbc.sql("select family_id from refresh_tokens where token_hash=:hash")
                .param("hash", TokenSecrets.hash(cookie.getValue())).query(UUID.class).single();
    }
    private String createUpgradeDatabase() {
        String name = "dm_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        if (!name.matches("dm_upgrade_[a-f0-9]{32}")) throw new IllegalStateException("Invalid test database name");
        // AuthIT always owns its disposable Testcontainer; never runs against the user's local DB.
        jdbc.sql("CREATE DATABASE " + name).update();
        return name;
    }
    private String upgradeUrl(String database) {
        return container.getJdbcUrl().replace("/documind_auth_test", "/" + database);
    }
    private UUID insertUpgradeUser(String url, String email) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(url, container.getUsername(), container.getPassword());
             var statement = connection.prepareStatement("insert into users(id,email,password_hash) values (?,?,?)")) {
            statement.setObject(1, id); statement.setString(2, email); statement.setString(3, "fixture");
            statement.executeUpdate();
        }
        return id;
    }
    private void holdFamilyRoot(UUID family, CountDownLatch acquired, CountDownLatch release) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(refreshTokens.lockFamily(family)).isPresent();
            acquired.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting to release root lock");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        });
    }
    private void awaitWaitingFamilyLocks(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long waiting = jdbc.sql("""
                    select count(*) from pg_stat_activity
                    where datname = current_database() and pid <> pg_backend_pid()
                      and state = 'active' and wait_event_type = 'Lock' and query ilike '%refresh_tokens%'
                    """).query(Long.class).single();
            if (waiting >= expected) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Requests did not both wait for the family lock");
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        void set(Instant instant) { now.set(instant); }
        void advance(Duration duration) { now.updateAndGet(instant -> instant.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    static class AuditedPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = new BCryptPasswordEncoder(12);
        private final AtomicBoolean encodeInTransaction = new AtomicBoolean();
        private final AtomicBoolean matchInTransaction = new AtomicBoolean();
        private volatile CountDownLatch encodingStarted;
        private volatile CountDownLatch releaseEncoding;
        void reset() { encodeInTransaction.set(false); matchInTransaction.set(false); encodingStarted = null; releaseEncoding = null; }
        void blockEncoding(CountDownLatch started, CountDownLatch release) { encodingStarted = started; releaseEncoding = release; }
        @Override public String encode(CharSequence rawPassword) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) encodeInTransaction.set(true);
            CountDownLatch started = encodingStarted, release = releaseEncoding;
            if (started != null && release != null) {
                started.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for concurrent registration");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            return delegate.encode(rawPassword);
        }
        @Override public boolean matches(CharSequence rawPassword, String encodedPassword) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) matchInTransaction.set(true);
            return delegate.matches(rawPassword, encodedPassword);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
        @Bean @Primary AuditedPasswordEncoder auditedPasswordEncoder() { return new AuditedPasswordEncoder(); }
        @Bean ProbeController probe(DocumentRepository documents) { return new ProbeController(documents); }
    }
    @RestController
    static class ProbeController {
        private final DocumentRepository documents;
        ProbeController(DocumentRepository documents) { this.documents = documents; }
        @RequestMapping(value = "/api/v1/test/principal", method = {RequestMethod.GET, RequestMethod.POST})
        Map<String, String> principal(@AuthenticationPrincipal Jwt jwt) { return Map.of("id", jwt.getSubject()); }
        @GetMapping("/api/v1/test/documents/{id}")
        ResponseEntity<?> document(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
            return documents.findByIdAndUserId(id, UUID.fromString(jwt.getSubject()))
                    .map(document -> ResponseEntity.ok(Map.of("id", document.getId())))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }
    }
}
