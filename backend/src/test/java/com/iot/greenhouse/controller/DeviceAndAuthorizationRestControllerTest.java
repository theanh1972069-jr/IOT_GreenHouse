package com.iot.greenhouse.controller;

import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.Role;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.DeviceRepository;
import com.iot.greenhouse.repository.RoleRepository;
import com.iot.greenhouse.repository.UserRepository;
import com.iot.greenhouse.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Regression tests for the critical backend defects:
 * <ul>
 *   <li>device registration used to bind the JPA entity straight from the request
 *       body, which left {@code status} null and made every insert fail with 500;</li>
 *   <li>only {@code POST /api/users} checked for the ADMIN role, so any logged-in
 *       user could list all users and mutate devices, configs and alerts.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("dockerAvailable")
class DeviceAndAuthorizationRestControllerTest extends TestDatabase {

    static boolean dockerAvailable() {
        return TestDatabase.isRunning();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String adminToken;
    private String memberToken;

    @BeforeEach
    void setUp() throws Exception {
        deviceRepository.deleteAll();

        Role adminRole = roleRepository.findByName("ADMIN")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ADMIN").description("admin").build()));
        Role userRole = roleRepository.findByName("USER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("USER").description("user").build()));

        adminToken = login("it-admin", adminRole);
        memberToken = login("it-member", userRole);
    }

    private String login(String prefix, Role role) throws Exception {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        userRepository.save(User.builder()
                .username(username)
                .email(username + "@it.local")
                .passwordHash(passwordEncoder.encode("Passw0rd!23"))
                .fullName("IT " + username)
                .status("ACTIVE")
                .roles(Set.of(role))
                .build());

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("identifier", username, "password", "Passw0rd!23"))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = read(result);
        return body.get("token").asString();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String uniqueDeviceId() {
        return "it-dev-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // --- POST /api/devices -------------------------------------------------

    @Test
    void registersDeviceWithExactPayloadFrontendSends() throws Exception {
        String deviceId = uniqueDeviceId();

        // Frontend (App.jsx) posts only these four fields - no status.
        mockMvc.perform(post("/api/devices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"%s","name":"Nhà kính 1","zone":"Zone A","firmwareVersion":"1.0.3"}
                                """.formatted(deviceId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value(deviceId))
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.zone").value("Zone A"));

        Device stored = deviceRepository.findByDeviceId(deviceId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(stored.getStatus()).isEqualTo("REGISTERED");
    }

    @Test
    void ignoresClientSuppliedServerOwnedFields() throws Exception {
        String deviceId = uniqueDeviceId();

        mockMvc.perform(post("/api/devices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"%s","name":"Spoof","zone":"Zone B",
                                 "id":999999,"status":"DECOMMISSIONED","createdAt":"2000-01-01T00:00:00Z",
                                 "lastSeen":"2000-01-01T00:00:00Z"}
                                """.formatted(deviceId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REGISTERED"));

        Device stored = deviceRepository.findByDeviceId(deviceId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(stored.getId()).isNotEqualTo(999999L);
        org.assertj.core.api.Assertions.assertThat(stored.getLastSeen()).isNull();
    }

    @Test
    void rejectsDuplicateDeviceIdWithConflict() throws Exception {
        String deviceId = uniqueDeviceId();
        String payload = """
                {"deviceId":"%s","name":"Nhà kính 1","zone":"Zone A"}
                """.formatted(deviceId);

        mockMvc.perform(post("/api/devices").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/devices").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void rejectsInvalidRegistrationWithBadRequest() throws Exception {
        mockMvc.perform(post("/api/devices").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"ab","name":"","zone":"Zone A"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(post("/api/devices").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"%s","name":"Ok","zone":"Zone A","macAddress":"zz:zz"}
                                """.formatted(uniqueDeviceId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidStatusWithBadRequestNotServerError() throws Exception {
        String deviceId = uniqueDeviceId();
        mockMvc.perform(post("/api/devices").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"%s","name":"Ok","zone":"Zone A"}
                                """.formatted(deviceId)))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/devices/{deviceId}/status", deviceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"GARBAGE"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/devices/{deviceId}/status", deviceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"active"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // --- authorization -----------------------------------------------------

    @Test
    void nonAdminCannotRegisterDevice() throws Exception {
        mockMvc.perform(post("/api/devices").header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"%s","name":"X","zone":"Z"}
                                """.formatted(uniqueDeviceId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void nonAdminCannotListUsers() throws Exception {
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonAdminCannotChangeDeviceStatusOrConfigOrAlerts() throws Exception {
        String deviceId = uniqueDeviceId();
        mockMvc.perform(post("/api/devices").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"%s","name":"Ok","zone":"Z"}
                                """.formatted(deviceId)))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/devices/{deviceId}/status", deviceId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"OFFLINE"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/devices/{deviceId}/configuration", deviceId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"temperatureHigh":30,"soilMoistureLow":40,"heartbeatInterval":15,"autoWatering":false}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/alerts/{id}/acknowledge", 1L)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanStillListUsers() throws Exception {
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void readsRemainAvailableToNonAdmin() throws Exception {
        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/alerts").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/system/status").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
    }

    @Test
    void missingTokenIs401AndBadTokenIs401() throws Exception {
        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").isNotEmpty());
        mockMvc.perform(get("/api/devices").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    // --- CORS --------------------------------------------------------------

    @Test
    void corsPreflightIsAnsweredForAllowedOrigin() throws Exception {
        mockMvc.perform(options("/api/devices")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void corsActualRequestCarriesAllowOriginHeader() throws Exception {
        mockMvc.perform(get("/api/devices")
                        .header("Origin", "http://localhost:5173")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}

