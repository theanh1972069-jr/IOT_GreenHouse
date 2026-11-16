package com.iot.greenhouse.controller;

import com.iot.greenhouse.config.AuthInterceptor;
import com.iot.greenhouse.config.RequireRole;
import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.LightSchedule;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.DeviceRepository;
import com.iot.greenhouse.repository.LightScheduleRepository;
import com.iot.greenhouse.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * API CRUD cho lịch tự động bật/tắt đèn quang hợp.
 *
 * GET    /api/devices/{deviceId}/light-schedules         — Lấy danh sách lịch
 * POST   /api/devices/{deviceId}/light-schedules         — Tạo lịch mới
 * PUT    /api/devices/{deviceId}/light-schedules/{id}    — Cập nhật lịch
 * DELETE /api/devices/{deviceId}/light-schedules/{id}    — Xoá lịch
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/light-schedules")
@RequiredArgsConstructor
public class LightScheduleRestController {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final LightScheduleRepository lightScheduleRepository;
    private final DeviceRepository deviceRepository;
    private final AuditLogService auditLogService;
    private final com.iot.greenhouse.service.LightScheduleService lightScheduleService;

    // ─── GET: danh sách lịch của thiết bị ───────────────────────────────────

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> listSchedules(@PathVariable String deviceId) {
        if (deviceRepository.findByDeviceId(deviceId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<LightScheduleView> views = lightScheduleRepository
                .findByDevice_DeviceIdOrderByTurnOnTimeAsc(deviceId)
                .stream()
                .map(LightScheduleRestController::toView)
                .toList();
        return ResponseEntity.ok(views);
    }

    // ─── POST: tạo lịch mới ─────────────────────────────────────────────────

    @PostMapping
    @RequireRole({"ADMIN", "OPERATOR"})
    @Transactional
    public ResponseEntity<?> createSchedule(
            @PathVariable String deviceId,
            @RequestBody ScheduleRequest request,
            @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
            HttpServletRequest httpRequest) {

        ResponseEntity<?> err = validateRequest(request);
        if (err != null) return err;

        return deviceRepository.findByDeviceId(deviceId)
                .map(device -> {
                    LocalTime onTime  = LocalTime.parse(request.turnOnTime(), TIME_FMT);
                    LocalTime offTime = LocalTime.parse(request.turnOffTime(), TIME_FMT);

                    LightSchedule schedule = LightSchedule.builder()
                            .device(device)
                            .label(request.label() != null ? request.label().trim() : "Lịch chiếu sáng")
                            .turnOnTime(onTime)
                            .turnOffTime(offTime)
                            .enabled(request.enabled() != null ? request.enabled() : true)
                            .createdBy(actor)
                            .updatedBy(actor)
                            .build();

                    LightSchedule saved = lightScheduleRepository.save(schedule);
                    try {
                        lightScheduleService.syncDeviceLightScheduleToMqtt(deviceId);
                        lightScheduleService.evaluateSchedules();
                    } catch (Exception ignored) {}
                    auditLogService.record(actor, httpRequest, "LIGHT_SCHEDULE_CREATE", "Device", deviceId,
                            Map.of("label", saved.getLabel(),
                                    "turnOnTime", saved.getTurnOnTime().toString(),
                                    "turnOffTime", saved.getTurnOffTime().toString(),
                                    "enabled", saved.getEnabled()));
                    return ResponseEntity.ok(toView(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── PUT: cập nhật lịch ─────────────────────────────────────────────────

    @PutMapping("/{id}")
    @RequireRole({"ADMIN", "OPERATOR"})
    @Transactional
    public ResponseEntity<?> updateSchedule(
            @PathVariable String deviceId,
            @PathVariable Long id,
            @RequestBody ScheduleRequest request,
            @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
            HttpServletRequest httpRequest) {

        ResponseEntity<?> err = validateRequest(request);
        if (err != null) return err;

        return lightScheduleRepository.findById(id)
                .filter(s -> s.getDevice().getDeviceId().equals(deviceId))
                .map(schedule -> {
                    if (request.label() != null) schedule.setLabel(request.label().trim());
                    schedule.setTurnOnTime(LocalTime.parse(request.turnOnTime(), TIME_FMT));
                    schedule.setTurnOffTime(LocalTime.parse(request.turnOffTime(), TIME_FMT));
                    if (request.enabled() != null) schedule.setEnabled(request.enabled());
                    schedule.setUpdatedBy(actor);

                    LightSchedule saved = lightScheduleRepository.save(schedule);
                    try {
                        lightScheduleService.syncDeviceLightScheduleToMqtt(deviceId);
                        lightScheduleService.evaluateSchedules();
                    } catch (Exception ignored) {}
                    auditLogService.record(actor, httpRequest, "LIGHT_SCHEDULE_UPDATE", "Device", deviceId,
                            Map.of("id", id,
                                    "label", saved.getLabel(),
                                    "turnOnTime", saved.getTurnOnTime().toString(),
                                    "turnOffTime", saved.getTurnOffTime().toString(),
                                    "enabled", saved.getEnabled()));
                    return ResponseEntity.ok(toView(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── DELETE: xoá lịch ───────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    @RequireRole({"ADMIN", "OPERATOR"})
    @Transactional
    public ResponseEntity<?> deleteSchedule(
            @PathVariable String deviceId,
            @PathVariable Long id,
            @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
            HttpServletRequest httpRequest) {

        return lightScheduleRepository.findById(id)
                .filter(s -> s.getDevice().getDeviceId().equals(deviceId))
                .map(schedule -> {
                    lightScheduleRepository.delete(schedule);
                    try { lightScheduleService.evaluateSchedules(); } catch (Exception ignored) {}
                    auditLogService.record(actor, httpRequest, "LIGHT_SCHEDULE_DELETE", "Device", deviceId,
                            Map.of("id", id, "label", schedule.getLabel()));
                    return ResponseEntity.ok(Map.of("message", "Đã xoá lịch chiếu sáng."));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── Helper ─────────────────────────────────────────────────────────────

    private ResponseEntity<?> validateRequest(ScheduleRequest request) {
        if (request == null || request.turnOnTime() == null || request.turnOffTime() == null) {
            return ResponseEntity.badRequest().body(new ApiError("Vui lòng cung cấp giờ bật và tắt đèn."));
        }
        try {
            LocalTime on  = LocalTime.parse(request.turnOnTime(), TIME_FMT);
            LocalTime off = LocalTime.parse(request.turnOffTime(), TIME_FMT);
            if (on.equals(off)) {
                return ResponseEntity.badRequest().body(new ApiError("Giờ bật và tắt đèn không được trùng nhau."));
            }
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(new ApiError("Định dạng giờ không hợp lệ. Dùng HH:mm (ví dụ: 06:00)."));
        }
        if (request.label() != null && request.label().trim().length() > 100) {
            return ResponseEntity.badRequest().body(new ApiError("Nhãn lịch tối đa 100 ký tự."));
        }
        return null;
    }

    private static LightScheduleView toView(LightSchedule s) {
        return new LightScheduleView(
                s.getId(),
                s.getDevice().getDeviceId(),
                s.getLabel(),
                s.getTurnOnTime().format(TIME_FMT),
                s.getTurnOffTime().format(TIME_FMT),
                s.getEnabled(),
                s.getUpdatedAt()
        );
    }

    public record ScheduleRequest(String label, String turnOnTime, String turnOffTime, Boolean enabled) {}
    public record LightScheduleView(Long id, String deviceId, String label,
                                    String turnOnTime, String turnOffTime,
                                    Boolean enabled, java.time.ZonedDateTime updatedAt) {}
    public record ApiError(String message) {}
}

