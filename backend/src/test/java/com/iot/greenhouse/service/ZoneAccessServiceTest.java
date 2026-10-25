package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.Role;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.entity.UserZone;
import com.iot.greenhouse.repository.UserZoneRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZoneAccessServiceTest {

    private UserZoneRepository userZoneRepository;
    private AuditLogService auditLogService;
    private ZoneAccessService zoneAccessService;

    @BeforeEach
    void setUp() {
        userZoneRepository = mock(UserZoneRepository.class);
        auditLogService = mock(AuditLogService.class);
        zoneAccessService = new ZoneAccessService(userZoneRepository, auditLogService);
    }

    @Test
    void administratorCanAccessAnyZoneWithoutGrantRows() {
        User admin = userWithRole(7L, "ADMIN");

        assertThat(zoneAccessService.canAccess(admin, "Zone A")).isTrue();
        verify(userZoneRepository, org.mockito.Mockito.never())
                .existsByUser_IdAndZoneIgnoreCase(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void viewerOnlyAccessesAssignedZones() {
        User viewer = userWithRole(8L, "VIEWER");
        when(userZoneRepository.existsByUser_IdAndZoneIgnoreCase(8L, "Zone A")).thenReturn(true);
        when(userZoneRepository.findByUser_IdOrderByZoneAsc(8L)).thenReturn(List.of(
                UserZone.builder().zone("Zone A").build()));

        assertThat(zoneAccessService.canAccess(viewer, "Zone A")).isTrue();
        assertThat(zoneAccessService.canAccess(viewer, "Zone B")).isFalse();
        assertThat(zoneAccessService.accessibleZones(viewer)).containsExactly("Zone A");
        assertThat(zoneAccessService.canAccess(viewer, null)).isFalse();
    }

    @Test
    void recordsDeniedZoneAccessForAudit() {
        User operator = userWithRole(9L, "OPERATOR");
        HttpServletRequest request = mock(HttpServletRequest.class);

        zoneAccessService.auditDenied(operator, request, "Device", "node-1", "Zone B");

        verify(auditLogService).record(operator, request, "ACCESS_DENIED", "Device", "node-1",
                java.util.Map.of("zone", "Zone B"));
    }

    private static User userWithRole(Long id, String roleName) {
        return User.builder()
                .id(id)
                .roles(Set.of(Role.builder().name(roleName).build()))
                .build();
    }
}
