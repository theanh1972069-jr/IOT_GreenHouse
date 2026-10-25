package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.PasswordResetToken;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.PasswordResetTokenRepository;
import com.iot.greenhouse.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PasswordResetTokenServiceTest {

    private PasswordResetTokenRepository tokenRepository;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthTokenService authTokenService;
    private PasswordResetTokenService service;

    @BeforeEach
    void setUp() {
        tokenRepository = mock(PasswordResetTokenRepository.class);
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authTokenService = mock(AuthTokenService.class);
        service = new PasswordResetTokenService(
                tokenRepository, userRepository, passwordEncoder, authTokenService);
    }

    @Test
    void storesOnlyTokenHashAndExpiresAfterFifteenMinutes() {
        User user = User.builder().id(24L).build();
        when(userRepository.findById(24L)).thenReturn(Optional.of(user));

        String rawToken = service.issueFor(24L);

        var tokenCaptor = org.mockito.ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        PasswordResetToken saved = tokenCaptor.getValue();
        assertThat(rawToken).hasSize(43);
        assertThat(saved.getTokenHash()).hasSize(64).isNotEqualTo(rawToken);
        assertThat(saved.getExpiresAt()).isBetween(
                ZonedDateTime.now().plusMinutes(14),
                ZonedDateTime.now().plusMinutes(16));
        verify(tokenRepository).invalidateUnusedForUser(eq(24L), any(ZonedDateTime.class));
    }

    @Test
    void validTokenChangesPasswordMarksTokenUsedAndRevokesAllSessions() {
        User user = User.builder().id(25L).status("ACTIVE").build();
        PasswordResetToken token = PasswordResetToken.builder()
                .user(user)
                .tokenHash("stored-hash")
                .expiresAt(ZonedDateTime.now().plusMinutes(5))
                .build();
        when(tokenRepository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("NewPassword!23")).thenReturn("new-hash");
        when(authTokenService.revokeAllForUser(25L)).thenReturn(2L);

        Optional<User> resetUser = service.resetPassword("raw-reset-token", "NewPassword!23");

        assertThat(resetUser).contains(user);
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(token.getUsedAt()).isNotNull();
        verify(passwordEncoder).encode("NewPassword!23");
        verify(authTokenService).revokeAllForUser(25L);
        verify(tokenRepository).findByTokenHashForUpdate(argThat(hash ->
                hash.length() == 64 && !hash.equals("raw-reset-token")));
    }

    @Test
    void expiredAndPreviouslyUsedTokensCannotResetPassword() {
        User user = User.builder().id(26L).status("ACTIVE").build();
        PasswordResetToken expired = PasswordResetToken.builder()
                .user(user)
                .expiresAt(ZonedDateTime.now().minusSeconds(1))
                .build();
        when(tokenRepository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(expired));

        assertThat(service.resetPassword("expired-token", "NewPassword!23")).isEmpty();
        verifyNoInteractions(passwordEncoder, authTokenService);

        expired.setExpiresAt(ZonedDateTime.now().plusMinutes(5));
        expired.setUsedAt(ZonedDateTime.now().minusSeconds(1));
        assertThat(service.resetPassword("used-token", "NewPassword!23")).isEmpty();
        verifyNoInteractions(passwordEncoder, authTokenService);
    }

    @Test
    void inactiveAccountsCannotUseResetTokens() {
        User user = User.builder().id(27L).status("LOCKED").build();
        PasswordResetToken token = PasswordResetToken.builder()
                .user(user)
                .expiresAt(ZonedDateTime.now().plusMinutes(5))
                .build();
        when(tokenRepository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(token));

        assertThat(service.resetPassword("locked-account-token", "NewPassword!23")).isEmpty();
        assertThat(token.getUsedAt()).isNotNull();
        verifyNoInteractions(passwordEncoder, authTokenService);
    }
}
