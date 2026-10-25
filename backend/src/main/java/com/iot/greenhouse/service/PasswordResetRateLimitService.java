package com.iot.greenhouse.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PasswordResetRateLimitService {

    private static final String UPSERT_SQL = """
            insert into password_reset_rate_limits (rate_key_hash, window_started_at, request_count)
            values (?, ?, 1)
            on conflict (rate_key_hash) do update set
                request_count = case
                    when password_reset_rate_limits.window_started_at <= ? then 1
                    else password_reset_rate_limits.request_count + 1
                end,
                window_started_at = case
                    when password_reset_rate_limits.window_started_at <= ?
                        then excluded.window_started_at
                    else password_reset_rate_limits.window_started_at
                end
            """;

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.auth.password-reset.window-minutes:15}")
    private long windowMinutes;

    @Value("${app.auth.password-reset.request-limit-per-email:3}")
    private int requestsPerEmail;

    @Value("${app.auth.password-reset.request-limit-per-ip:20}")
    private int requestsPerIp;

    @Value("${app.auth.password-reset.confirm-limit-per-ip:20}")
    private int confirmationsPerIp;

    @Transactional
    public boolean allowRequest(String normalizedEmail, String remoteAddress) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime windowStart = now.minus(windowMinutes, ChronoUnit.MINUTES);
        boolean emailAllowed = consume("request-email", normalizedEmail, requestsPerEmail, now, windowStart);
        boolean ipAllowed = consume("request-ip", remoteAddress, requestsPerIp, now, windowStart);
        return emailAllowed && ipAllowed;
    }

    @Transactional
    public boolean allowConfirmation(String remoteAddress) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime windowStart = now.minus(windowMinutes, ChronoUnit.MINUTES);
        return consume("confirm-ip", remoteAddress, confirmationsPerIp, now, windowStart);
    }

    private boolean consume(String scope, String value, int limit, ZonedDateTime now, ZonedDateTime windowStart) {
        String keyHash = hash(scope + ":" + (value == null ? "" : value));
        Timestamp nowTimestamp = Timestamp.from(now.toInstant());
        Timestamp windowStartTimestamp = Timestamp.from(windowStart.toInstant());
        jdbcTemplate.update(UPSERT_SQL, keyHash, nowTimestamp, windowStartTimestamp, windowStartTimestamp);
        Integer count = jdbcTemplate.queryForObject(
                "select request_count from password_reset_rate_limits where rate_key_hash = ?",
                Integer.class,
                keyHash);
        return Objects.requireNonNull(count, "Rate-limit row was not created") <= limit;
    }

    @Scheduled(fixedDelayString = "${app.auth.password-reset.cleanup-interval-ms:3600000}")
    @Transactional
    public void purgeOldWindows() {
        jdbcTemplate.update(
                "delete from password_reset_rate_limits where window_started_at < ?",
                Timestamp.from(ZonedDateTime.now().minus(1, ChronoUnit.DAYS).toInstant()));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
