package com.iot.greenhouse.config;

import com.iot.greenhouse.entity.Role;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.RoleRepository;
import com.iot.greenhouse.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.auth.bootstrap-admin.username:admin}")
    private String adminUsername;

    @Value("${app.auth.bootstrap-admin.email:admin@greenhouse.local}")
    private String adminEmail;

    @Value("${app.auth.bootstrap-admin.password:}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        Role adminRole = ensureRole("ADMIN", "System administrator");
        ensureRole("OPERATOR", "Greenhouse monitoring and operations");
        ensureRole("VIEWER", "Read-only greenhouse access");
        ensureRole("USER", "Legacy standard user");

        if (userRepository.count() > 0) {
            Long adminId = jdbcTemplate.query("SELECT id FROM users WHERE username = ?",
                    resultSet -> resultSet.next() ? resultSet.getLong("id") : null, adminUsername);
            if (adminId != null) {
                jdbcTemplate.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                        adminId, adminRole.getId());
            }
            return;
        }

        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("No users exist and BOOTSTRAP_ADMIN_PASSWORD is not configured; "
                    + "skipping default administrator creation");
            return;
        }
        if (adminUsername == null || adminUsername.isBlank() || adminEmail == null || adminEmail.isBlank()) {
            throw new IllegalStateException("Bootstrap admin username and email must be configured when "
                    + "BOOTSTRAP_ADMIN_PASSWORD is set");
        }

        User admin = User.builder()
                .username(adminUsername)
                .email(adminEmail)
                .passwordHash(passwordEncoder.encode(adminPassword))
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
}
