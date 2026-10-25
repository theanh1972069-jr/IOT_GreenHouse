package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.PasswordResetToken;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.PasswordResetTokenRepository;
import com.iot.greenhouse.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PasswordResetTokenService {

    private static final long TOKEN_TTL_MINUTES = 15;

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public String issueFor(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Password reset account disappeared before token creation."));
        ZonedDateTime now = ZonedDateTime.now();
        tokenRepository.invalidateUnusedForUser(userId, now);
        String rawToken = generateToken();
        tokenRepository.save(PasswordResetToken.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .expiresAt(now.plus(TOKEN_TTL_MINUTES, ChronoUnit.MINUTES))
                .build());
        return rawToken;
    }

    @Transactional
    public Optional<User> resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        PasswordResetToken token = tokenRepository.findByTokenHashForUpdate(hash(rawToken)).orElse(null);
        ZonedDateTime now = ZonedDateTime.now();
        if (token == null || token.getUsedAt() != null || !token.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }

        User user = token.getUser();
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            token.setUsedAt(now);
            return Optional.empty();
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        token.setUsedAt(now);
        authTokenService.revokeAllForUser(user.getId());
        return Optional.of(user);
    }

    @Scheduled(fixedDelayString = "${app.auth.password-reset.cleanup-interval-ms:3600000}")
    @Transactional
    public void purgeExpiredTokens() {
        tokenRepository.deleteExpiredBefore(ZonedDateTime.now());
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
