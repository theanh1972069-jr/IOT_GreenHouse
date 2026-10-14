package com.iot.greenhouse.controller;

import com.iot.greenhouse.config.AuthInterceptor;
import com.iot.greenhouse.config.RequireRole;
import com.iot.greenhouse.entity.Alert;
import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.AlertRepository;
import com.iot.greenhouse.service.AuditLogService;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertRestController {

    private final AlertRepository alertRepository;
    private final AuditLogService auditLogService;

    /**
     * Filters are applied by the database. The previous implementation loaded the
     * newest 500 rows and then filtered them in Java, so any match older than the
     * cap was reported as "no results".
     */
    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<AlertView> getAlerts(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String severity,
                                             @RequestParam(required = false) String search,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        Specification<Alert> spec = buildSpecification(status, severity, search);
        Page<Alert> result = alertRepository.findAll(spec,
                PageParams.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.from(result, AlertRestController::toView);
    }

    private static Specification<Alert> buildSpecification(String status, String severity, String search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
                predicates.add(cb.equal(cb.upper(root.get("status")), status.trim().toUpperCase(Locale.ROOT)));
            }
            if (severity != null && !severity.isBlank() && !severity.equalsIgnoreCase("ALL")) {
                predicates.add(cb.equal(cb.upper(root.get("severity")), severity.trim().toUpperCase(Locale.ROOT)));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                Join<Alert, Device> device = root.join("device", JoinType.LEFT);
                predicates.add(cb.or(
                        cb.like(cb.lower(device.get("deviceId")), like),
                        cb.like(cb.lower(device.get("name")), like),
                        cb.like(cb.lower(device.get("zone")), like),
                        cb.like(cb.lower(root.get("message")), like)));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    @PostMapping("/{id}/acknowledge")
    @RequireRole({"ADMIN", "OPERATOR"})
    @Transactional
    public ResponseEntity<?> acknowledge(@PathVariable Long id,
                                         @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
                                         HttpServletRequest request) {
        return alertRepository.findById(id)
                .map(alert -> {
                    if ("RESOLVED".equals(alert.getStatus())) {
                        return ResponseEntity.badRequest().body(new ApiError("Cảnh báo đã được xử lý."));
                    }
                    alert.setStatus("ACKNOWLEDGED");
                    alert.setAcknowledgedAt(ZonedDateTime.now());
                    alert.setAcknowledgedBy(actor);
                    Alert saved = alertRepository.save(alert);
                    auditLogService.record(actor, request, "ALERT_ACKNOWLEDGE", "Alert",
                            String.valueOf(id), Map.of("type", String.valueOf(alert.getType())));
                    return ResponseEntity.ok(toView(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/resolve")
    @RequireRole({"ADMIN", "OPERATOR"})
    @Transactional
    public ResponseEntity<?> resolve(@PathVariable Long id,
                                     @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
                                     HttpServletRequest request) {
        return alertRepository.findById(id)
                .map(alert -> {
                    alert.setStatus("RESOLVED");
                    alert.setResolvedAt(ZonedDateTime.now());
                    alert.setResolvedBy(actor);
                    Alert saved = alertRepository.save(alert);
                    auditLogService.record(actor, request, "ALERT_RESOLVE", "Alert",
                            String.valueOf(id), Map.of("type", String.valueOf(alert.getType())));
                    return ResponseEntity.ok(toView(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private static AlertView toView(Alert alert) {
        return new AlertView(alert.getId(), alert.getDevice().getDeviceId(), alert.getDevice().getName(),
                alert.getDevice().getZone(), alert.getType(), alert.getSeverity(), alert.getMessage(),
                alert.getStatus(), alert.getCreatedAt(), alert.getAcknowledgedAt(), alert.getResolvedAt());
    }

    public record AlertView(Long id, String deviceId, String deviceName, String zone, String type,
                            String severity, String message, String status, ZonedDateTime createdAt,
                            ZonedDateTime acknowledgedAt, ZonedDateTime resolvedAt) {}
    public record ApiError(String message) {}
}
