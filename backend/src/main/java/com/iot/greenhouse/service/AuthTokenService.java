package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.entity.UserSession;
import com.iot.greenhouse.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Issues and validates bearer tokens.
 *
 * <p>Sessions are stored in {@code user_sessions} rather than in a map, so they
 * survive a restart and are shared across instances. Only the SHA-256 hash of the
 * token is persisted; the raw token is returned to the client once and never stored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthTokenService {

    private final UserSessionRepository sessionRepository;

    @Value("${app.auth.session-ttl-hours:8}")
    private long sessionTtlHours;

    private final SecureRandom secureRandom = new SecureRandom();

    /** @return the raw token; it cannot be recovered from the database afterwards. */
    @Transactional
    public String issue(User user, String ipAddress) {
        return issue(user, ipAddress, ZonedDateTime.now().plus(sessionTtlHours, ChronoUnit.HOURS));
    }

    @Transactional
    public String issue(User user, String ipAddress, ZonedDateTime expiresAt) {
        String token = generateToken();
        sessionRepository.save(UserSession.builder()
                .user(user)
                .tokenHash(hash(token))
                .expiresAt(expiresAt)
                .ipAddress(ipAddress)
                .build());
        return token;
    }

    /**
     * @return the owning user id when the token exists, has not been revoked and has
     *         not expired.
     */
    @Transactional(readOnly = true)
    public Optional<Long> findUserId(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return sessionRepository.findActiveByTokenHash(hash(token), ZonedDateTime.now())
                .map(session -> session.getUser().getId());
    }

    /** Revokes a single session (logout). Unknown tokens are ignored. */
    @Transactional
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        sessionRepository.findByTokenHash(hash(token))
                .filter(session -> session.getRevokedAt() == null)
                .ifPresent(session -> session.setRevokedAt(ZonedDateTime.now()));
    }

    /** Revokes every live session of a user, e.g. when the account is locked. */
    @Transactional
    public long revokeAllForUser(Long userId) {
        List<UserSession> live = sessionRepository.findAllByUserIdAndRevokedAtIsNull(userId);
        ZonedDateTime now = ZonedDateTime.now();
        live.forEach(session -> session.setRevokedAt(now));
        return live.size();
    }

    /** Drops revoked and long-expired rows so the table does not grow forever. */
    @Scheduled(fixedDelayString = "${app.auth.session-cleanup-interval-ms:3600000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purgeExpired() {
        int removed = sessionRepository.deleteStale(ZonedDateTime.now().minus(7, ChronoUnit.DAYS));
        if (removed > 0) {
            log.info("Purged {} expired or revoked session(s)", removed);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /** Exposed for tests and for the login response's client-side expiry hint. */
    public long sessionTtlSeconds() {
        return Math.multiplyExact(sessionTtlHours, 3600L);
    }
}
