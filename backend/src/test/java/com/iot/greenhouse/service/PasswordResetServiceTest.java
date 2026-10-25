package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.MailSendException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {

    private UserRepository userRepository;
    private PasswordResetRateLimitService rateLimitService;
    private PasswordResetTokenService tokenService;
    private ObjectProvider<PasswordResetEmailSender> emailSenderProvider;
    private PasswordResetEmailSender emailSender;
    private PasswordResetService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userRepository = mock(UserRepository.class);
        rateLimitService = mock(PasswordResetRateLimitService.class);
        tokenService = mock(PasswordResetTokenService.class);
        emailSenderProvider = mock(ObjectProvider.class);
        emailSender = mock(PasswordResetEmailSender.class);
        when(emailSenderProvider.getIfAvailable()).thenReturn(emailSender);
        service = new PasswordResetService(
                userRepository, rateLimitService, tokenService, emailSenderProvider);
    }

    @Test
    void sendsTokenOnlyToAnActiveAccountAndNormalizesEmail() {
        User user = User.builder().id(17L).email("Admin@example.com").status("ACTIVE").build();
        when(rateLimitService.allowRequest("admin@example.com", "127.0.0.1")).thenReturn(true);
        when(userRepository.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(user));
        when(tokenService.issueFor(17L)).thenReturn("one-time-token");

        service.requestReset(" Admin@Example.com ", "127.0.0.1");

        verify(emailSender).sendResetLink("Admin@example.com", "one-time-token");
    }

    @Test
    void respondsWithoutLookingUpAnAccountWhenRateLimited() {
        when(rateLimitService.allowRequest("admin@example.com", "127.0.0.1")).thenReturn(false);

        service.requestReset("admin@example.com", "127.0.0.1");

        verifyNoInteractions(userRepository, tokenService, emailSenderProvider);
    }

    @Test
    void doesNotSendForUnknownInactiveOrMalformedAddresses() {
        when(rateLimitService.allowRequest(anyString(), anyString())).thenReturn(true);
        User inactive = User.builder().id(18L).email("inactive@example.com").status("LOCKED").build();
        when(userRepository.findByEmailIgnoreCase("inactive@example.com")).thenReturn(Optional.of(inactive));

        service.requestReset("unknown@example.com", "127.0.0.1");
        service.requestReset("inactive@example.com", "127.0.0.1");
        service.requestReset("not-an-email", "127.0.0.1");

        verify(userRepository).findByEmailIgnoreCase("unknown@example.com");
        verify(userRepository).findByEmailIgnoreCase("inactive@example.com");
        verifyNoInteractions(tokenService, emailSenderProvider);
    }

    @Test
    void emailQueueFailureDoesNotRevealAccountExistenceToCaller() {
        User user = User.builder().id(19L).email("operator@example.com").status("ACTIVE").build();
        when(rateLimitService.allowRequest("operator@example.com", "127.0.0.1")).thenReturn(true);
        when(userRepository.findByEmailIgnoreCase("operator@example.com")).thenReturn(Optional.of(user));
        when(tokenService.issueFor(19L)).thenReturn("one-time-token");
        doThrow(new TaskRejectedException("queue full"))
                .when(emailSender).sendResetLink("operator@example.com", "one-time-token");

        assertThatCode(() -> service.requestReset("operator@example.com", "127.0.0.1"))
                .doesNotThrowAnyException();
    }

    @Test
    void smtpFailureDoesNotChangeGenericRequestOutcome() {
        User user = User.builder().id(20L).email("viewer@example.com").status("ACTIVE").build();
        when(rateLimitService.allowRequest("viewer@example.com", "127.0.0.1")).thenReturn(true);
        when(userRepository.findByEmailIgnoreCase("viewer@example.com")).thenReturn(Optional.of(user));
        when(tokenService.issueFor(20L)).thenReturn("one-time-token");
        doThrow(new MailSendException("SMTP unavailable"))
                .when(emailSender).sendResetLink("viewer@example.com", "one-time-token");

        assertThatCode(() -> service.requestReset("viewer@example.com", "127.0.0.1"))
                .doesNotThrowAnyException();
    }
}
