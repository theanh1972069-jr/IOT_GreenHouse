package com.iot.greenhouse.config;

import com.iot.greenhouse.entity.Role;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.RoleRepository;
import com.iot.greenhouse.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AuthBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.bootstrap.admin.password:${BOOTSTRAP_ADMIN_PASSWORD:}}")
    private String bootstrapAdminPassword;

    @Value("${app.bootstrap.admin.email:${BOOTSTRAP_ADMIN_EMAIL:}}")
    private String bootstrapAdminEmail;

    @Override
    public void run(ApplicationArguments args) {
        ensureLightSchedulesTable();
        Role adminRole = ensureRole("ADMIN", "System administrator");
        ensureRole("OPERATOR", "Greenhouse monitoring and operations");
        ensureRole("VIEWER", "Read-only greenhouse access");
        ensureRole("USER", "Legacy standard user");

        if (userRepository.count() > 0) {
            Long adminId = jdbcTemplate.query("SELECT id FROM users WHERE username = ?",
                    resultSet -> resultSet.next() ? resultSet.getLong("id") : null, "admin");
            if (adminId != null) {
                jdbcTemplate.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                        adminId, adminRole.getId());
            }
            return;
        }

        String adminEmail = bootstrapAdminEmail == null ? "" : bootstrapAdminEmail.trim().toLowerCase(Locale.ROOT);
        if (bootstrapAdminPassword == null
                || bootstrapAdminPassword.length() < 12
                || bootstrapAdminPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || adminEmail.length() > 150
                || !adminEmail.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalStateException(
                    "Set BOOTSTRAP_ADMIN_PASSWORD (12-72 UTF-8 bytes) and BOOTSTRAP_ADMIN_EMAIL "
                            + "to a reachable email address before starting with an empty database.");
        }

        User admin = User.builder()
                .username("admin")
                .email(adminEmail)
                .passwordHash(passwordEncoder.encode(bootstrapAdminPassword))
                .fullName("System Administrator")
                .status("ACTIVE")
                .roles(Set.of(adminRole))
                .build();

        userRepository.save(admin);
    }

    private Role ensureRole(String name, String description) {
        return roleRepository.findByName(name)
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .name(name)
                        .description(description)
                        .build()));
    }

    /**
     * Tạo bảng light_schedules nếu chưa tồn tại (idempotent — dùng IF NOT EXISTS).
     * Vì dự án dùng ddl-auto=none và không có Flyway/Liquibase, migration thủ công
     * được thực hiện ở đây để bảng luôn tồn tại trước khi JPA truy cập.
     */
    private void ensureLightSchedulesTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS light_schedules (
                id            BIGSERIAL PRIMARY KEY,
                device_id     BIGINT      NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                label         VARCHAR(100) NOT NULL DEFAULT 'Lịch chiếu sáng',
                turn_on_time  TIME        NOT NULL,
                turn_off_time TIME        NOT NULL,
                enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
                created_by    BIGINT      REFERENCES users(id) ON DELETE SET NULL,
                updated_by    BIGINT      REFERENCES users(id) ON DELETE SET NULL,
                created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """);
    }
}
