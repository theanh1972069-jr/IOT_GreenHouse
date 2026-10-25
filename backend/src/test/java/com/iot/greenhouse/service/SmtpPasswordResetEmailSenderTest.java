package com.iot.greenhouse.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SmtpPasswordResetEmailSenderTest {

    @Test
    void sendsPlainTextOneTimeLinkWithoutPuttingTokenInQueryString() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        SmtpPasswordResetEmailSender sender = new SmtpPasswordResetEmailSender(mailSender);
        ReflectionTestUtils.setField(sender, "fromAddress", "no-reply@example.com");
        ReflectionTestUtils.setField(sender, "resetPageUrl", "https://greenhouse.example/reset-password");

        sender.sendResetLink("user@example.com", "opaque-token");

        var messageCaptor = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        MimeMessage sent = messageCaptor.getValue();
        assertThat(sent.getFrom()[0].toString()).contains("no-reply@example.com");
        assertThat(sent.getAllRecipients()[0].toString()).contains("user@example.com");
        assertThat(sent.getSubject()).isEqualTo("Đặt lại mật khẩu FloraCore");
        assertThat(sent.getContent().toString())
                .contains("https://greenhouse.example/reset-password#token=opaque-token")
                .contains("15 phút");
    }
}
