package com.iot.greenhouse.controller;

import com.iot.greenhouse.config.AuthInterceptor;
import com.iot.greenhouse.config.RequireRole;
import com.iot.greenhouse.entity.Alert;
import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.model.DeviceLiveStatus;
import com.iot.greenhouse.repository.AlertRepository;
import com.iot.greenhouse.service.AuditLogService;
import com.iot.greenhouse.service.DeviceLiveService;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertRestController {

    private final AlertRepository alertRepository;
    private final AuditLogService auditLogService;
    private final DeviceLiveService deviceLiveService;

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

    /**
     * Thực thi hành động khắc phục trực tiếp trên thiết bị liên quan đến cảnh báo.
     */
    @PostMapping("/{id}/execute-action")
    @RequireRole({"ADMIN", "OPERATOR"})
    @Transactional
    public ResponseEntity<?> executeAction(@PathVariable Long id,
                                          @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
                                          HttpServletRequest request) {
        Alert alert = alertRepository.findById(id).orElse(null);
        if (alert == null) {
            return ResponseEntity.notFound().build();
        }

        if (!"OPEN".equalsIgnoreCase(alert.getStatus())) {
            return ResponseEntity.badRequest().body(new ApiError("Cảnh báo không ở trạng thái chờ xử lý."));
        }

        Device device = alert.getDevice();
        String deviceId = device.getDeviceId();
        DeviceLiveStatus liveStatus = deviceLiveService.getOrCreateLiveStatus(deviceId);

        String targetActuator = alert.getTargetActuator() == null ? "" : alert.getTargetActuator().trim().toUpperCase(Locale.ROOT);
        String targetState = alert.getTargetState() == null ? "ON" : alert.getTargetState().trim().toUpperCase(Locale.ROOT);

        // Tự động suy luận nếu cảnh báo cũ chưa lưu targetActuator
        if (targetActuator.isEmpty()) {
            if ("TEMPERATURE_HIGH".equalsIgnoreCase(alert.getType())) {
                targetActuator = "FAN";
                targetState = "ON";
            } else if ("SOIL_MOISTURE_LOW".equalsIgnoreCase(alert.getType())) {
                targetActuator = "VALVE";
                targetState = "ON";
            } else if ("MANUAL_MODE".equalsIgnoreCase(alert.getType())) {
                targetActuator = "MODE";
                targetState = "AUTO";
            }
        }

        if ("MODE".equals(targetActuator) || "AUTO".equals(targetActuator)) {
            boolean alreadyInState = liveStatus.isAutoMode();
            if (alreadyInState) {
                alert.setStatus("PROCESSED");
                alert.setProcessedAt(ZonedDateTime.now());
                alert.setProcessedBy(actor);
                Alert saved = alertRepository.save(alert);
                auditLogService.record(actor, request, "ALERT_ACTION_EXECUTE", "Alert", String.valueOf(id),
                        Map.of("target", "MODE", "state", "AUTO", "alreadyInState", true));
                return ResponseEntity.ok(Map.of(
                        "message", "Thiết bị đã ở trạng thái yêu cầu",
                        "alreadyInState", true,
                        "deviceId", deviceId,
                        "alert", toView(saved)
                ));
            } else {
                deviceLiveService.setAutoMode(deviceId, true, actor, request);
                alert.setStatus("PROCESSED");
                alert.setProcessedAt(ZonedDateTime.now());
                alert.setProcessedBy(actor);
                Alert saved = alertRepository.save(alert);
                auditLogService.record(actor, request, "ALERT_ACTION_EXECUTE", "Alert", String.valueOf(id),
                        Map.of("target", "MODE", "state", "AUTO", "alreadyInState", false));
                return ResponseEntity.ok(Map.of(
                        "message", "Đã kích hoạt chế độ tự động",
                        "alreadyInState", false,
                        "deviceId", deviceId,
                        "alert", toView(saved)
                ));
            }
        } else {
            String actKey = targetActuator.toLowerCase(Locale.ROOT);
            if ("pump".equals(actKey)) actKey = "valve";
            if ("lamp".equals(actKey)) actKey = "light";

            boolean targetBool = !"OFF".equalsIgnoreCase(targetState);
            final String lookupKey = actKey;
            boolean currentActuatorState = liveStatus.getActuators().stream()
                    .filter(a -> a.id().equalsIgnoreCase(lookupKey))
                    .findFirst()
                    .map(DeviceLiveStatus.ActuatorLiveView::state)
                    .orElse(false);

            if (currentActuatorState == targetBool) {
                alert.setStatus("PROCESSED");
                alert.setProcessedAt(ZonedDateTime.now());
                alert.setProcessedBy(actor);
                Alert saved = alertRepository.save(alert);
                auditLogService.record(actor, request, "ALERT_ACTION_EXECUTE", "Alert", String.valueOf(id),
                        Map.of("actuator", actKey, "state", targetState, "alreadyInState", true));
                return ResponseEntity.ok(Map.of(
                        "message", "Thiết bị đã ở trạng thái yêu cầu",
                        "alreadyInState", true,
                        "deviceId", deviceId,
                        "alert", toView(saved)
                ));
            } else {
                deviceLiveService.controlActuator(deviceId, actKey, targetBool, actor, request, alert.getId());
                alert.setStatus("PROCESSED");
                alert.setProcessedAt(ZonedDateTime.now());
                alert.setProcessedBy(actor);
                Alert saved = alertRepository.save(alert);

                if (targetBool) {
                    alertRepository.expireOtherOpenAlerts(device.getId(), alert.getId(), ZonedDateTime.now());
                }

                auditLogService.record(actor, request, "ALERT_ACTION_EXECUTE", "Alert", String.valueOf(id),
                        Map.of("actuator", actKey, "state", targetState, "alreadyInState", false));
                return ResponseEntity.ok(Map.of(
                        "message", "Đã kích hoạt thiết bị thành công",
                        "alreadyInState", false,
                        "deviceId", deviceId,
                        "alert", toView(saved)
                ));
            }
        }
    }

    /**
     * Hủy cảnh báo: đặt trạng thái thành CANCELLED mà không gửi lệnh xuống thiết bị.
     */
    @PostMapping("/{id}/cancel")
    @RequireRole({"ADMIN", "OPERATOR"})
    @Transactional
    public ResponseEntity<?> cancel(@PathVariable Long id,
                                   @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
                                   HttpServletRequest request) {
        return alertRepository.findById(id)
                .map(alert -> {
                    if (!"OPEN".equalsIgnoreCase(alert.getStatus())) {
                        return ResponseEntity.badRequest().body(new ApiError("Chỉ có thể hủy cảnh báo đang chờ xử lý."));
                    }
                    alert.setStatus("CANCELLED");
                    alert.setProcessedAt(ZonedDateTime.now());
                    alert.setProcessedBy(actor);
                    Alert saved = alertRepository.save(alert);
                    auditLogService.record(actor, request, "ALERT_CANCEL", "Alert",
                            String.valueOf(id), Map.of("type", String.valueOf(alert.getType())));
                    return ResponseEntity.ok(toView(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/acknowledge")
    @RequireRole({"ADMIN", "OPERATOR"})
    @Transactional
    public ResponseEntity<?> acknowledge(@PathVariable Long id,
                                         @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
                                         HttpServletRequest request) {
        return executeAction(id, actor, request);
    }

    @PostMapping("/{id}/resolve")
    @RequireRole({"ADMIN", "OPERATOR"})
    @Transactional
    public ResponseEntity<?> resolve(@PathVariable Long id,
                                     @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
                                     HttpServletRequest request) {
        return cancel(id, actor, request);
    }

    private static AlertView toView(Alert alert) {
        return new AlertView(
                alert.getId(),
                alert.getDevice().getDeviceId(),
                alert.getDevice().getName(),
                alert.getDevice().getZone(),
                alert.getType(),
                alert.getSeverity(),
                alert.getMessage(),
                alert.getStatus(),
                alert.getTargetActuator(),
                alert.getTargetState(),
                alert.getCreatedAt(),
                alert.getAcknowledgedAt(),
                alert.getEffectiveProcessedAt(),
                alert.getEffectiveProcessedAt()
        );
    }

    public record AlertView(Long id, String deviceId, String deviceName, String zone, String type,
                            String severity, String message, String status,
                            String targetActuator, String targetState,
                            ZonedDateTime createdAt, ZonedDateTime acknowledgedAt,
                            ZonedDateTime resolvedAt, ZonedDateTime processedAt) {}

    public record ApiError(String message) {}
}
