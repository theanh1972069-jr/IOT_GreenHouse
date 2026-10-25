package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.UserZoneRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ZoneAccessService {

    private final UserZoneRepository userZoneRepository;
    private final AuditLogService auditLogService;

    public boolean isAdministrator(User user) {
        return hasRole(user, "ADMIN");
    }

    public boolean canAccess(User user, String zone) {
        if (isAdministrator(user)) {
            return true;
        }
        return user != null && zone != null
                && userZoneRepository.existsByUser_IdAndZoneIgnoreCase(user.getId(), zone.trim());
    }

    public List<String> accessibleZones(User user) {
        if (user == null || isAdministrator(user)) {
            return List.of();
        }
        return userZoneRepository.findByUser_IdOrderByZoneAsc(user.getId()).stream()
                .map(userZone -> userZone.getZone())
                .toList();
    }

    public void auditDenied(User user, HttpServletRequest request, String resourceType, String resourceId, String zone) {
        auditLogService.record(user, request, "ACCESS_DENIED", resourceType, resourceId,
                zone == null ? java.util.Map.of() : java.util.Map.of("zone", zone));
    }

    public boolean canReadAnyGreenhouseData(User user) {
        return hasRole(user, "ADMIN") || hasRole(user, "OPERATOR")
                || hasRole(user, "VIEWER") || hasRole(user, "USER");
    }

    public static boolean hasRole(User user, String expectedRole) {
        if (user == null || user.getRoles() == null) {
            return false;
        }
        return user.getRoles().stream()
                .map(role -> role.getName())
                .filter(name -> name != null)
                .map(name -> name.toUpperCase(Locale.ROOT))
                .anyMatch(name -> name.equals(expectedRole));
    }
}
