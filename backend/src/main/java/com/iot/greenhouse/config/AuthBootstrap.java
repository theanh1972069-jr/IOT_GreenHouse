package com.iot.greenhouse.config;

import com.iot.greenhouse.entity.Role;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.RoleRepository;
import com.iot.greenhouse.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class AuthBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
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

        User admin = User.builder()
                .username("admin")
                .email("admin@greenhouse.local")
                .passwordHash(passwordEncoder.encode("admin123"))
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
