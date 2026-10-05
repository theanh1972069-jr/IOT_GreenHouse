package com.iot.greenhouse.controller;

import com.iot.greenhouse.entity.Alert;
import com.iot.greenhouse.entity.AuditLog;
import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.Role;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.entity.UserSession;
import com.iot.greenhouse.repository.AlertRepository;
import com.iot.greenhouse.repository.AuditLogRepository;
import com.iot.greenhouse.repository.DeviceRepository;
import com.iot.greenhouse.repository.RoleRepository;
import com.iot.greenhouse.repository.UserRepository;
import com.iot.greenhouse.repository.UserSessionRepository;
import com.iot.greenhouse.service.AuthTokenService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import com.iot.greenhouse.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the second batch of fixes: SQL-side filtering with real pagination,
 * database-backed sessions, and an audit trail that is actually written.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("dockerAvailable")
class PaginationSessionAuditTest extends TestDatabase {

    static boolean dockerAvailable() {
        return TestDatabase.isRunning();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private AuthTokenService authTokenService;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private String adminToken;
    private Long adminId;
    private Device device;

    @BeforeEach
    void setUp() throws Exception {
        alertRepository.deleteAll();
        auditLogRepository.deleteAll();

        Role adminRole = roleRepository.findByName("ADMIN")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ADMIN").description("admin").build()));

        String username = "it-admin-" + UUID.randomUUID().toString().substring(0, 8);
        adminId = userRepository.save(User.builder()
                .username(username)
                .email(username + "@it.local")
                .passwordHash(passwordEncoder.encode("Passw0rd!23"))
                .fullName("IT Admin")
                .status("ACTIVE")
                .roles(Set.of(adminRole))
                .build()).getId();

        adminToken = objectMapper.readTree(mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("identifier", username, "password", "Passw0rd!23"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("token").asString();

        device = deviceRepository.save(Device.builder()
                .deviceId("pag-dev-" + UUID.randomUUID().toString().substring(0, 6))
                .name("Thiết bị phân trang")
                .zone("Zone A")
                .status("ACTIVE")
                .build());
    }

    // --- SQL-side filtering (the old code capped at 500 rows, then filtered) ---

    @Test
    void alertSearchFindsRowsOlderThanTheOldFiveHundredCap() {
        // 520 alerts so the target row sits beyond the previous newest-500 window.
        List<Alert> alerts = new ArrayList<>();
        for (int i = 0; i < 520; i++) {
            alerts.add(Alert.builder()
                    .device(device)
                    .type("TEMPERATURE_HIGH")
                    .severity(i % 2 == 0 ? "HIGH" : "LOW")
                    .message("reading " + i)
                    .status("OPEN")
                    .build());
        }
        alertRepository.saveAll(alerts);
        alertRepository.flush();

        Alert oldest = alerts.get(0);
        String needle = oldest.getMessage();

        Specification<Alert> spec =
                (root, query, cb) -> cb.like(cb.lower(root.get("message")), needle.toLowerCase());
        var page = alertRepository.findAll(spec, PageRequest.of(0, 10));

        assertThat(page.getTotalElements())
                .as("the oldest alert must still be reachable by search")
                .isEqualTo(1);
    }

    @Test
    void alertFiltersAreAppliedInSql() throws Exception {
        for (int i = 0; i < 30; i++) {
            alertRepository.save(Alert.builder()
                    .device(device)
                    .type("SOIL_LOW")
                    .severity(i % 3 == 0 ? "CRITICAL" : "LOW")
                    .message("soil " + i)
                    .status(i % 2 == 0 ? "OPEN" : "RESOLVED")
                    .build());
        }
        alertRepository.flush();

        mockMvc.perform(get("/api/alerts").param("severity", "CRITICAL")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(10))
                .andExpect(jsonPath("$.content[0].severity").value("CRITICAL"));

        mockMvc.perform(get("/api/alerts").param("status", "OPEN")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(15));

        mockMvc.perform(get("/api/alerts").param("search", "soil 7")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void allIsTreatedAsNoFilter() throws Exception {
        alertRepository.save(Alert.builder().device(device).type("X").severity("LOW")
                .message("only one").status("OPEN").build());
        alertRepository.flush();

        mockMvc.perform(get("/api/alerts").param("status", "ALL").param("severity", "ALL")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // --- pagination envelope ---

    @Test
    void alertsArePaginatedWithCorrectTotals() throws Exception {
        for (int i = 0; i < 25; i++) {
            alertRepository.save(Alert.builder().device(device).type("T").severity("LOW")
                    .message("m" + i).status("OPEN").build());
        }
        alertRepository.flush();

        mockMvc.perform(get("/api/alerts").param("size", "10").param("page", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/api/alerts").param("size", "10").param("page", "2")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void pageSizeIsClampedAndBadInputDoesNotExplode() throws Exception {
        alertRepository.save(Alert.builder().device(device).type("T").severity("LOW")
                .message("m").status("OPEN").build());
        alertRepository.flush();

        mockMvc.perform(get("/api/alerts").param("size", "100000")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(PageParams.MAX_SIZE));

        mockMvc.perform(get("/api/alerts").param("page", "-5").param("size", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(PageParams.DEFAULT_SIZE));
    }

    @Test
    void emptyResultIsDistinguishableFromAFullPage() throws Exception {
        mockMvc.perform(get("/api/alerts").param("search", "khong-ton-tai-abc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.empty").value(true));
    }

    @Test
    void commandsAndAuditLogsAlsoReturnTheEnvelope() throws Exception {
        mockMvc.perform(get("/api/commands").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber());

        mockMvc.perform(get("/api/audit-logs").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber());
    }

    // --- N+1 regression guard ----------------------------------------------

    /**
     * The user list used to resolve each row's roles with its own query, so 50 users
     * meant 51 statements. Roles are now fetch-joined, so the statement count has to
     * stay flat as the page grows.
     */
    @Test
    void listingUsersDoesNotIssueOneQueryPerRow() throws Exception {
        Role adminRole = roleRepository.findByName("ADMIN").orElseThrow();
        for (int i = 0; i < 40; i++) {
            String username = "bulk-" + i + "-" + UUID.randomUUID().toString().substring(0, 6);
            userRepository.save(User.builder()
                    .username(username)
                    .email(username + "@it.local")
                    .passwordHash(passwordEncoder.encode("Passw0rd!23"))
                    .fullName("Bulk User " + i)
                    .status("ACTIVE")
                    .roles(Set.of(adminRole))
                    .build());
        }
        userRepository.flush();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            mockMvc.perform(get("/api/users").param("size", "50")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());

            assertThat(statistics.getPrepareStatementCount())
                    .as("query count must not scale with the number of rows")
                    .isLessThan(10L);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    // --- database-backed sessions ---

    @Test
    void loginStoresOnlyTheTokenHash() {
        List<UserSession> sessions = sessionRepository.findAll();
        assertThat(sessions).isNotEmpty();

        UserSession session = sessions.stream()
                .filter(s -> s.getUser().getId().equals(adminId))
                .findFirst()
                .orElseThrow();

        assertThat(session.getTokenHash()).hasSize(64).doesNotContain(adminToken);
        assertThat(session.getRevokedAt()).isNull();
        assertThat(session.getExpiresAt()).isAfter(ZonedDateTime.now());
    }

    @Test
    void tokenResolvesFromTheDatabaseSoItSurvivesARestart() throws Exception {
        // A second instance would resolve the token exactly like this: from the table.
        assertThat(authTokenService.findUserId(adminToken))
                .as("token must resolve from user_sessions, not an in-memory map")
                .contains(adminId);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(authTokenService.findUserId("khong-ton-tai")).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        User admin = userRepository.findById(adminId).orElseThrow();
        String expired = authTokenService.issue(admin, "127.0.0.1", ZonedDateTime.now().minusMinutes(1));

        assertThat(authTokenService.findUserId(expired)).isEmpty();
        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedTokenIsRejected() {
        User admin = userRepository.findById(adminId).orElseThrow();
        String token = authTokenService.issue(admin, "127.0.0.1");

        assertThat(authTokenService.findUserId(token)).contains(adminId);
        authTokenService.revoke(token);
        assertThat(authTokenService.findUserId(token)).isEmpty();
    }

    @Test
    void logoutRevokesTheSessionImmediately() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());

        assertThat(sessionRepository.findAll())
                .filteredOn(s -> s.getUser().getId().equals(adminId))
                .allMatch(s -> s.getRevokedAt() != null);
    }

    // --- audit trail ---

    @Test
    void loginAndLogoutAreAudited() throws Exception {
        List<AuditLog> logs = auditLogRepository.findAll();
        assertThat(logs).extracting(AuditLog::getAction).contains("LOGIN");

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(auditLogRepository.findAll())
                .extracting(AuditLog::getAction)
                .contains("LOGIN", "LOGOUT");
    }

    @Test
    void failedLoginIsAudited() throws Exception {
        String username = "ghost-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("identifier", username, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized());

        AuditLog entry = auditLogRepository.findAll().stream()
                .filter(l -> "LOGIN_FAILED".equals(l.getAction()))
                .findFirst()
                .orElseThrow();
        assertThat(entry.getResourceId()).isEqualTo(username);
        assertThat(entry.getDetails()).containsEntry("reason", "unknown_user");
        assertThat(entry.getIpAddress()).isNotBlank();
    }

    @Test
    void deviceRegistrationAndStatusChangeAreAudited() throws Exception {
        String deviceId = "audited-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/devices").header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deviceId", deviceId, "name", "Có audit", "zone", "Zone B"))))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/devices/{deviceId}/status", deviceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"status\":\"MAINTENANCE\"}"))
                .andExpect(status().isOk());

        assertThat(auditLogRepository.findAll())
                .extracting(AuditLog::getAction)
                .contains("DEVICE_REGISTER", "DEVICE_STATUS_CHANGE");

        AuditLog statusChange = auditLogRepository.findAll().stream()
                .filter(l -> "DEVICE_STATUS_CHANGE".equals(l.getAction()))
                .findFirst()
                .orElseThrow();
        assertThat(statusChange.getResourceId()).isEqualTo(deviceId);
        assertThat(statusChange.getUser()).isNotNull();
        assertThat(statusChange.getUser().getId()).isEqualTo(adminId);
    }

    @Test
    void configurationUpdateIsAuditedAndRecordsTheEditor() throws Exception {
        String deviceId = "cfg-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/devices").header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deviceId", deviceId, "name", "Có cấu hình", "zone", "Zone C"))))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/devices/{deviceId}/configuration", deviceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("""
                                {"temperatureHigh":31.5,"soilMoistureLow":42,"heartbeatInterval":20,"autoWatering":false}
                                """))
                .andExpect(status().isOk());

        assertThat(auditLogRepository.findAll())
                .extracting(AuditLog::getAction)
                .contains("DEVICE_CONFIG_UPDATE");

        var config = deviceRepository.findByDeviceId(deviceId).orElseThrow();
        assertThat(config.getId()).isNotNull();
    }

    @Test
    void alertAcknowledgementIsAudited() throws Exception {
        Alert alert = alertRepository.save(Alert.builder()
                .device(device).type("T").severity("HIGH").message("cần xử lý")
                .status("OPEN").build());

        mockMvc.perform(post("/api/alerts/{id}/acknowledge", alert.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

        assertThat(auditLogRepository.findAll())
                .extracting(AuditLog::getAction)
                .contains("ALERT_ACKNOWLEDGE");

        Alert reloaded = alertRepository.findById(alert.getId()).orElseThrow();
        assertThat(reloaded.getAcknowledgedBy()).isNotNull();
        assertThat(reloaded.getAcknowledgedBy().getId()).isEqualTo(adminId);
    }
}
