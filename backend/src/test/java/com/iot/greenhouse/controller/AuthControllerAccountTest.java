package com.iot.greenhouse.controller;

import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.UserRepository;
import com.iot.greenhouse.service.AuditLogService;
import com.iot.greenhouse.service.AuthTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerAccountTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthTokenService authTokenService;
    private AuditLogService auditLogService;
    private AuthController authController;
    private User user;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authTokenService = mock(AuthTokenService.class);
        auditLogService = mock(AuditLogService.class);
        authController = new AuthController(userRepository, passwordEncoder, authTokenService, auditLogService);
        user = User.builder().id(42L).passwordHash("old-hash").build();
        request = mock(HttpServletRequest.class);
    }

    @Test
    void changingPasswordRequiresCurrentPasswordAndRevokesAllSessions() {
        when(passwordEncoder.matches("old-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("a-new-password-123")).thenReturn("new-hash");
        when(userRepository.save(user)).thenReturn(user);

        ResponseEntity<?> response = authController.changePassword(
                new AuthController.PasswordChangeRequest("old-password", "a-new-password-123"),
                user, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(authTokenService).revokeAllForUser(42L);
        verify(auditLogService).record(user, request, "PASSWORD_CHANGE", "User", "42", null);
    }

    @Test
    void incorrectCurrentPasswordDoesNotInvalidateTheSession() {
        when(passwordEncoder.matches("wrong-password", "old-hash")).thenReturn(false);

        ResponseEntity<?> response = authController.changePassword(
                new AuthController.PasswordChangeRequest("wrong-password", "a-new-password-123"),
                user, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(authTokenService, never()).revokeAllForUser(42L);
        verify(userRepository, never()).save(user);
    }

    @Test
    void rejectsWeakNewPasswords() {
        ResponseEntity<?> response = authController.changePassword(
                new AuthController.PasswordChangeRequest("old-password", "short"),
                user, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(passwordEncoder, never()).matches("old-password", "old-hash");
    }
}
