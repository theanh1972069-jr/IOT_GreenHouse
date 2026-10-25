package com.iot.greenhouse.config;

import com.iot.greenhouse.entity.Role;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.RoleRepository;
import com.iot.greenhouse.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthBootstrapTest {

    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private PasswordEncoder passwordEncoder;
    private AuthBootstrap authBootstrap;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authBootstrap = new AuthBootstrap(userRepository, roleRepository, passwordEncoder, mock(JdbcTemplate.class));
        when(userRepository.count()).thenReturn(0L);
        when(roleRepository.findByName(anyString()))
                .thenAnswer(invocation -> Optional.of(Role.builder()
                        .id(1L)
                        .name(invocation.getArgument(0))
                        .build()));
    }

    @Test
    void refusesToInitializeAnEmptyDatabaseWithoutBootstrapPassword() {
        ReflectionTestUtils.setField(authBootstrap, "bootstrapAdminPassword", "");

        assertThrows(IllegalStateException.class,
                () -> authBootstrap.run(new DefaultApplicationArguments(new String[0])));

        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void hashesConfiguredBootstrapPasswordBeforeSavingAdmin() {
        ReflectionTestUtils.setField(authBootstrap, "bootstrapAdminPassword", "a-strong-local-secret");
        when(passwordEncoder.encode("a-strong-local-secret")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> authBootstrap.run(new DefaultApplicationArguments(new String[0])));

        verify(passwordEncoder).encode("a-strong-local-secret");
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(
                user -> "bcrypt-hash".equals(user.getPasswordHash())));
    }
}
