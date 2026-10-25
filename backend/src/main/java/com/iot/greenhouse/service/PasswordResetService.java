package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final UserRepository userRepository;
    private final PasswordResetRateLimitService rateLimitService;
    private final PasswordResetTokenService tokenService;
    private final ObjectProvider<PasswordResetEmailSender> emailSenderProvider;

    public void requestReset(String email, String remoteAddress) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        boolean allowed = rateLimitService.allowRequest(normalizedEmail, remoteAddress);
        if (!allowed || normalizedEmail.length() > 254 || !EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
            return;
        }

        userRepository.findByEmailIgnoreCase(normalizedEmail)
                .filter(user -> "ACTIVE".equalsIgnoreCase(user.getStatus()))
                .ifPresent(user -> sendResetEmail(user));
    }

    public boolean allowConfirmation(String remoteAddress) {
        return rateLimitService.allowConfirmation(remoteAddress);
    }

    private void sendResetEmail(User user) {
        PasswordResetEmailSender emailSender = emailSenderProvider.getIfAvailable();
        if (emailSender == null) {
            log.error("Password reset email is not configured; no email was sent for user id {}", user.getId());
            return;
        }

        String rawToken = tokenService.issueFor(user.getId());
        try {
            emailSender.sendResetLink(user.getEmail(), rawToken);
        } catch (TaskRejectedException e) {
            log.error("Password reset email queue is full for user id {}", user.getId(), e);
        } catch (MailException e) {
            log.error("Failed to send password reset email for user id {}", user.getId(), e);
        }
    }
}
