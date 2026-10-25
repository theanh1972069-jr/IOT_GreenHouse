package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.Role;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.PasswordResetTokenRepository;
import com.iot.greenhouse.repository.RoleRepository;
import com.iot.greenhouse.repository.UserRepository;
import com.iot.greenhouse.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("dockerAvailable")
class PasswordResetDatabaseIntegrationTest extends TestDatabase {

    static boolean dockerAvailable() {
        return TestDatabase.isRunning();
    }

    @Autowired
    private PasswordResetRateLimitService rateLimitService;

    @Autowired
    private PasswordResetTokenService tokenService;

    @Autowired
    private AuthTokenService authTokenService;

    @Autowired
    private PasswordResetTokenRepository resetTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void resetRoutesArePublicWhileExistingAuthenticatedRoutesStayProtected() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"not-registered-%s@example.com"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","newPassword":"NewPassword!23"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requestLimitsPersistAcrossCallsAndDoNotStoreRawIdentifiers() {
        jdbcTemplate.update("delete from password_reset_rate_limits");
        String email = "limit-" + UUID.randomUUID() + "@example.com";
        String ip = "198.51.100.41";

        assertThat(rateLimitService.allowRequest(email, ip)).isTrue();
        assertThat(rateLimitService.allowRequest(email, ip)).isTrue();
        assertThat(rateLimitService.allowRequest(email, ip)).isTrue();
        assertThat(rateLimitService.allowRequest(email, ip)).isFalse();

        Integer matchingKeys = jdbcTemplate.queryForObject(
                "select count(*) from password_reset_rate_limits where rate_key_hash = ?",
                Integer.class,
                email);
        assertThat(matchingKeys).isZero();
        assertThat(rateLimitService.allowRequest("another-" + UUID.randomUUID() + "@example.com", ip))
                .isTrue();
    }

    @Test
    void resetTokenCanBeUsedOnceAndRevokesPreviouslyIssuedSessions() {
        Role role = roleRepository.findByName("OPERATOR")
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .name("OPERATOR").description("operator").build()));
        String username = "reset-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        User user = userRepository.save(User.builder()
                .username(username)
                .email(username + "@example.com")
                .passwordHash(passwordEncoder.encode("OldPassword!23"))
                .fullName("Password reset test")
                .status("ACTIVE")
                .roles(Set.of(role))
                .build());
        String oldSession = authTokenService.issue(user, "127.0.0.1");
        String rawResetToken = tokenService.issueFor(user.getId());

        assertThat(tokenService.resetPassword(rawResetToken, "NewPassword!23"))
                .map(User::getId).contains(user.getId());
        assertThat(tokenService.resetPassword(rawResetToken, "AnotherPassword!23")).isEmpty();
        assertThat(authTokenService.findUserId(oldSession)).isEmpty();
        assertThat(passwordEncoder.matches("NewPassword!23",
                userRepository.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();

        var storedToken = resetTokenRepository.findAll().stream()
                .filter(candidate -> candidate.getUser().getId().equals(user.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(storedToken.getTokenHash()).hasSize(64).isNotEqualTo(rawResetToken);
        assertThat(storedToken.getUsedAt()).isNotNull();
    }
}
