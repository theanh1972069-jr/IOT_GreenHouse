package com.iot.greenhouse.controller;

import com.iot.greenhouse.entity.Role;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.RoleRepository;
import com.iot.greenhouse.repository.UserRepository;
import com.iot.greenhouse.repository.UserSessionRepository;
import com.iot.greenhouse.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateRoleReproTest {

    @Mock
    UserRepository userRepository;
    @Mock
    RoleRepository roleRepository;
    @Mock
    UserSessionRepository sessionRepository;
    @Mock
    AuditLogService auditLogService;

    @InjectMocks
    UserRestController controller;

    @Test
    void updateRoleDoesNotThrow() {
        Role viewer = Role.builder().id(3L).name("VIEWER").build();
        Role operator = Role.builder().id(2L).name("OPERATOR").description("x").build();
        User target = User.builder().id(4L).username("tech").email("t@t.io")
                .status("ACTIVE").roles(Set.of(viewer)).build();
        User actor = User.builder().id(1L).username("admin")
                .roles(Set.of(Role.builder().id(1L).name("ADMIN").build())).build();

        when(userRepository.findById(4L)).thenReturn(Optional.of(target));
        when(roleRepository.findByName("OPERATOR")).thenReturn(Optional.of(operator));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionRepository.existsByUserIdAndRevokedAtIsNullAndExpiresAtAfter(eq(4L), any()))
                .thenReturn(false);

        ResponseEntity<?> response = controller.updateRole(4L,
                new UserRestController.RoleRequest("operator"), actor, new MockHttpServletRequest());

        System.out.println("REPRO_STATUS=" + response.getStatusCode());
        System.out.println("REPRO_BODY=" + response.getBody());
    }
}
