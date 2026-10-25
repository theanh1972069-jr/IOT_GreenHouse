package com.iot.greenhouse.service;

import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "true")
public class SmtpPasswordResetEmailSender implements PasswordResetEmailSender {

    private final JavaMailSender mailSender;

    @Value("${app.email.from:}")
    private String fromAddress;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${app.auth.password-reset-url:http://localhost:5173/reset-password}")
    private String resetPageUrl;

    @PostConstruct
    void validateConfiguration() {
        if (mailHost == null || mailHost.isBlank()) {
            throw new IllegalStateException("Set MAIL_HOST when EMAIL_ENABLED=true.");
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalStateException("Set MAIL_FROM or MAIL_USERNAME when EMAIL_ENABLED=true.");
        }
        URI uri = resetPageUrl == null ? null : URI.create(resetPageUrl);
        String host = uri == null ? null : uri.getHost();
        boolean secureUrl = uri != null && "https".equalsIgnoreCase(uri.getScheme()) && host != null;
        boolean localDevelopmentUrl = uri != null
                && "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host));
        if ((!secureUrl && !localDevelopmentUrl) || uri.getRawFragment() != null) {
            throw new IllegalStateException("PASSWORD_RESET_URL must use HTTPS, except for localhost development.");
        }
    }

    @Override
    @Async("passwordResetMailExecutor")
    public void sendResetLink(String email, String rawToken) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(email);
            helper.setSubject("Đặt lại mật khẩu FloraCore");
            helper.setText("""
                    Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản FloraCore của bạn.

                    Mở liên kết sau trong vòng 15 phút để chọn mật khẩu mới:
                    %s#token=%s

                    Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này. Không chia sẻ liên kết này với bất kỳ ai.
                    """.formatted(resetPageUrl.replaceAll("#+$", ""), rawToken), false);
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new MailPreparationException("Could not prepare the password reset email.", e);
        }
    }
}
