package com.documind.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import com.documind.auth.dto.Credentials;
import com.documind.auth.dto.TokenDto;
import com.documind.auth.dto.UserDto;
import com.documind.auth.security.AccessTokens;
import com.documind.auth.security.TokenSecrets;
import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
    private static final Duration FAMILY_LIFETIME = Duration.ofDays(7);
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final AccessTokens access;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwords, AccessTokens access, Clock clock,
                       PlatformTransactionManager transactionManager) {
        this.users = users; this.refreshTokens = refreshTokens; this.passwords = passwords;
        this.access = access; this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        dummyHash = passwords.encode(TokenSecrets.randomToken());
    }

    public record Session(TokenDto response, String refreshToken, Instant expiresAt) {
        @Override public String toString() { return "Session[REDACTED]"; }
    }
    public enum RefreshStatus { SUCCESS, INVALID, RACE, REUSED }
    public record RefreshResult(RefreshStatus status, Session session) {}
    private record LoginUser(UUID id, String email, String passwordHash) {
        @Override public String toString() { return "LoginUser[REDACTED]"; }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UserDto register(Credentials credentials) {
        validatePassword(credentials.password());
        boolean exists = Boolean.TRUE.equals(transactions.execute(status -> users.findByEmail(credentials.email()).isPresent()));
        if (exists) throw duplicateEmail();
        String passwordHash = passwords.encode(credentials.password());
        try {
            return transactions.execute(status -> {
                UserEntity user = new UserEntity(UUID.randomUUID());
                user.setEmail(credentials.email()); user.setPasswordHash(passwordHash);
                user.setCreatedAt(now());
                users.save(user);
                return dto(user);
            });
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateEmailConstraint(exception)) throw duplicateEmail();
            throw exception;
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Session login(Credentials credentials) {
        validatePassword(credentials.password());
        Optional<LoginUser> user = transactions.execute(status -> users.findByEmail(credentials.email())
                .map(found -> new LoginUser(found.getId(), found.getEmail(), found.getPasswordHash())));
        boolean valid = passwords.matches(credentials.password(), user.map(LoginUser::passwordHash).orElse(dummyHash));
        if (user.isEmpty() || !valid) throw new AppException(ErrorCode.LOGIN_INVALID);
        UUID family = UUID.randomUUID();
        String raw = TokenSecrets.randomToken();
        Instant expires = now().plus(FAMILY_LIFETIME);
        UserDto identity = new UserDto(user.get().id(), user.get().email());
        return transactions.execute(status -> {
            refreshTokens.save(token(family, family, identity.id(), raw, expires));
            return session(identity, raw, expires);
        });
    }

    @Transactional
    public RefreshResult refresh(String raw) {
        if (!wellFormed(raw)) return result(RefreshStatus.INVALID);
        String hash = TokenSecrets.hash(raw);
        var familyId = refreshTokens.findFamilyIdByTokenHash(hash);
        if (familyId.isEmpty()) return result(RefreshStatus.INVALID);
        var root = refreshTokens.lockFamily(familyId.get());
        Instant now = now(); // Read after acquiring the shared refresh/logout lock.
        if (root.isEmpty() || root.get().getRevokedAt() != null || !root.get().getExpiresAt().isAfter(now))
            return result(RefreshStatus.INVALID);
        var current = refreshTokens.findByTokenHash(hash);
        if (current.isEmpty() || current.get().getRevokedAt() != null || !current.get().getExpiresAt().isAfter(now))
            return result(RefreshStatus.INVALID);
        if (current.get().getConsumedAt() != null) {
            if (!now.isAfter(current.get().getConsumedAt().plusSeconds(5))) return result(RefreshStatus.RACE);
            refreshTokens.revokeFamily(familyId.get(), now);
            // Return a result so the revocation commits before the controller returns 401.
            return result(RefreshStatus.REUSED);
        }
        String childRaw = TokenSecrets.randomToken();
        UUID childId = UUID.randomUUID();
        refreshTokens.save(token(childId, familyId.get(), current.get().getUserId(), childRaw, root.get().getExpiresAt()));
        current.get().setConsumedAt(now); current.get().setReplacedBy(childId);
        return new RefreshResult(RefreshStatus.SUCCESS,
                session(dto(users.findById(current.get().getUserId()).orElseThrow()), childRaw, root.get().getExpiresAt()));
    }

    @Transactional
    public void logout(String raw) {
        if (!wellFormed(raw)) return;
        refreshTokens.findFamilyIdByTokenHash(TokenSecrets.hash(raw)).ifPresent(family -> {
            if (refreshTokens.lockFamily(family).isPresent()) refreshTokens.revokeFamily(family, now());
        });
    }

    public static AppException duplicateEmail() {
        return new AppException(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }

    private static boolean isDuplicateEmailConstraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return "23505".equals(violation.getSQLState())
                        && ("users_email_key".equals(name) || "uq_users_normalized_email".equals(name));
            }
        }
        return false;
    }

    private Session session(UserDto user, String raw, Instant expires) {
        return new Session(new TokenDto(access.issue(user.id()), "Bearer", AccessTokens.LIFETIME_SECONDS, user), raw, expires);
    }
    private RefreshTokenEntity token(UUID id, UUID family, UUID user, String raw, Instant expires) {
        RefreshTokenEntity token = new RefreshTokenEntity(id);
        token.setFamilyId(family); token.setUserId(user); token.setTokenHash(TokenSecrets.hash(raw));
        token.setExpiresAt(expires); token.setCreatedAt(now());
        return token;
    }
    private static UserDto dto(UserEntity user) { return new UserDto(user.getId(), user.getEmail()); }
    private static RefreshResult result(RefreshStatus status) { return new RefreshResult(status, null); }
    // PostgreSQL timestamptz has microsecond precision; never round a deadline up during persistence.
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static boolean wellFormed(String raw) { return raw != null && raw.matches("[A-Za-z0-9_-]{43}"); }
    private static void validatePassword(String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new AppException(ErrorCode.PASSWORD_TOO_LONG);
    }
}
