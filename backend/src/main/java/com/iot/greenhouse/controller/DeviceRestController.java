package com.iot.greenhouse.controller;

import com.iot.greenhouse.config.AuthInterceptor;
import com.iot.greenhouse.config.RequireRole;
import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.service.AuditLogService;
import com.iot.greenhouse.service.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceRestController {

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "REGISTERED", "PROVISIONED", "ACTIVE", "OFFLINE", "MAINTENANCE", "LOCKED", "DECOMMISSIONED");

    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{3,100}");
    private static final Pattern MAC_PATTERN = Pattern.compile("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}");
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");

    private static final int MAX_NAME = 150;
    private static final int MAX_ZONE = 50;
    private static final int MAX_FIRMWARE_VERSION = 50;
    private static final int MAX_IP_ADDRESS = 45;

    private final DeviceService deviceService;
    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<List<Device>> getAllDevices() {
        return ResponseEntity.ok(deviceService.getAllDevices());
    }

    @GetMapping("/{deviceId}")
    public ResponseEntity<Device> getDevice(@PathVariable String deviceId) {
        return deviceService.getDeviceByDeviceId(deviceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Registers a device from an explicit whitelist of fields. The JPA entity is never
     * bound straight from the request body, so a client cannot inject server-owned
     * columns (id, status, state, createdAt, lastSeen, ...).
     */
    @PostMapping
    @RequireRole("ADMIN")
    public ResponseEntity<?> registerDevice(@RequestBody DeviceRegistrationRequest request,
                                            @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
                                            HttpServletRequest httpRequest) {
        List<String> errors = validateRegistration(request);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ApiError(String.join(" ", errors)));
        }

        String deviceId = request.deviceId().trim();
        if (deviceService.getDeviceByDeviceId(deviceId).isPresent()) {
            return ResponseEntity.status(409).body(new ApiError("Mã thiết bị đã tồn tại."));
        }

        Device device = Device.builder()
                .deviceId(deviceId)
                .name(request.name().trim())
                .zone(request.zone().trim())
                .status("REGISTERED")
                .firmwareVersion(trimToNull(request.firmwareVersion()))
                .macAddress(trimToNull(request.macAddress()))
                .ipAddress(trimToNull(request.ipAddress()))
                .build();

        Device saved = deviceService.registerDevice(device);
        auditLogService.record(actor, httpRequest, "DEVICE_REGISTER", "Device",
                saved.getDeviceId(), Map.of("name", saved.getName(), "zone", saved.getZone()));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{deviceId}")
                .buildAndExpand(saved.getDeviceId())
                .toUri();
        return ResponseEntity.created(location).body(saved);
    }

    @PatchMapping("/{deviceId}/status")
    @RequireRole("ADMIN")
    public ResponseEntity<?> updateStatus(@PathVariable String deviceId,
                                          @RequestBody StatusRequest request,
                                          @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
                                          HttpServletRequest httpRequest) {
        if (request == null || request.status() == null || request.status().isBlank()) {
            return ResponseEntity.badRequest().body(new ApiError("Trạng thái thiết bị là bắt buộc."));
        }
        String status = request.status().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(status)) {
            return ResponseEntity.badRequest().body(new ApiError("Trạng thái thiết bị không hợp lệ."));
        }
        Optional<Device> updated = deviceService.updateStatus(deviceId, status);
        updated.ifPresent(device -> auditLogService.record(actor, httpRequest, "DEVICE_STATUS_CHANGE", "Device",
                device.getDeviceId(), Map.of("status", status)));
        return updated.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static List<String> validateRegistration(DeviceRegistrationRequest request) {
        if (request == null) {
            return List.of("Nội dung yêu cầu không hợp lệ.");
        }

        String deviceId = trimToNull(request.deviceId());
        String name = trimToNull(request.name());
        String zone = trimToNull(request.zone());

        if (deviceId == null) {
            return List.of("Mã thiết bị là bắt buộc.");
        }
        if (!DEVICE_ID_PATTERN.matcher(deviceId).matches()) {
            return List.of("Mã thiết bị phải dài 3-100 ký tự và chỉ gồm chữ, số, dấu chấm, gạch dưới, gạch ngang hoặc dấu hai chấm.");
        }
        if (name == null || name.length() > MAX_NAME) {
            return List.of("Tên thiết bị là bắt buộc và không vượt quá " + MAX_NAME + " ký tự.");
        }
        if (zone == null || zone.length() > MAX_ZONE) {
            return List.of("Khu vực là bắt buộc và không vượt quá " + MAX_ZONE + " ký tự.");
        }

        List<String> errors = new ArrayList<>();
        String firmwareVersion = trimToNull(request.firmwareVersion());
        if (firmwareVersion != null && firmwareVersion.length() > MAX_FIRMWARE_VERSION) {
            errors.add("Phiên bản firmware không vượt quá " + MAX_FIRMWARE_VERSION + " ký tự.");
        }
        String macAddress = trimToNull(request.macAddress());
        if (macAddress != null && !MAC_PATTERN.matcher(macAddress).matches()) {
            errors.add("Địa chỉ MAC không hợp lệ (định dạng AA:BB:CC:DD:EE:FF).");
        }
        String ipAddress = trimToNull(request.ipAddress());
        if (ipAddress != null && (ipAddress.length() > MAX_IP_ADDRESS || !IPV4_PATTERN.matcher(ipAddress).matches())) {
            errors.add("Địa chỉ IP không hợp lệ.");
        }
        return List.copyOf(errors);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Only these fields are client-settable on registration. */
    public record DeviceRegistrationRequest(String deviceId, String name, String zone,
                                            String firmwareVersion, String macAddress, String ipAddress) {}

    public record StatusRequest(String status) {}

    public record ApiError(String message) {}
}
