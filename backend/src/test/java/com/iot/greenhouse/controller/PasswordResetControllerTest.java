package com.iot.greenhouse.controller;

import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.UserRepository;
import com.iot.greenhouse.service.AuditLogService;
import com.iot.greenhouse.service.AuthTokenService;
import com.iot.greenhouse.service.PasswordResetService;
import com.iot.greenhouse.service.PasswordResetTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PasswordResetControllerTest {

    private static final String RESET_TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private MockMvc mockMvc;
    private PasswordResetService passwordResetService;
    private PasswordResetTokenService passwordResetTokenService;
    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AuthTokenService authTokenService = mock(AuthTokenService.class);
        auditLogService = mock(AuditLogService.class);
        passwordResetService = mock(PasswordResetService.class);
        passwordResetTokenService = mock(PasswordResetTokenService.class);

        AuthController controller = new AuthController(
                userRepository,
                passwordEncoder,
                authTokenService,
                auditLogService,
                passwordResetService,
                passwordResetTokenService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void resetRequestAlwaysReturnsSameAcceptedMessage() throws Exception {
        String body = """
                {"email":"person@example.com"}
                """;

        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(
                        "Nếu email thuộc tài khoản đang hoạt động, hướng dẫn đặt lại mật khẩu sẽ được gửi."));

        verify(passwordResetService).requestReset("person@example.com", "127.0.0.1");
    }

    @Test
    void resetConfirmationRejectsInvalidLinksAndShortPasswords() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","newPassword":"short"}
                                """.formatted(RESET_TOKEN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        verifyNoInteractions(passwordResetService, passwordResetTokenService, auditLogService);
    }

    @Test
    void resetConfirmationRejectsBcryptPasswordsOver72Utf8Bytes() throws Exception {
        String password = "é".repeat(37);
        String body = """
                {"token":"%s","newPassword":"%s"}
                """.formatted(RESET_TOKEN, password);

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(passwordResetTokenService, auditLogService);
    }

    @Test
    void validResetUpdatesPasswordAndReturnsSuccess() throws Exception {
        User user = User.builder().id(44L).username("operator").status("ACTIVE").build();
        when(passwordResetService.allowConfirmation(anyString())).thenReturn(true);
        when(passwordResetTokenService.resetPassword(RESET_TOKEN, "NewPassword!23"))
                .thenReturn(Optional.of(user));

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","newPassword":"NewPassword!23"}
                                """.formatted(RESET_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        "Mật khẩu đã được cập nhật. Tất cả phiên đăng nhập cũ đã bị đăng xuất."));

        verify(auditLogService).record(
                eq(user), any(), eq("PASSWORD_RESET"), eq("User"), eq("44"), isNull());
    }

    @Test
    void confirmationRateLimitIsEnforcedBeforeTokenLookup() throws Exception {
        when(passwordResetService.allowConfirmation("127.0.0.1")).thenReturn(false);

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","newPassword":"NewPassword!23"}
                                """.formatted(RESET_TOKEN)))
                .andExpect(status().isTooManyRequests());

        verify(passwordResetTokenService, never()).resetPassword(anyString(), anyString());
    }
}
