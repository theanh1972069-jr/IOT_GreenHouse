package com.iot.greenhouse.controller;

import com.iot.greenhouse.entity.Telemetry;
import com.iot.greenhouse.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/telemetry")
@RequiredArgsConstructor
public class TelemetryRestController {

    private final TelemetryService telemetryService;

    @GetMapping
    public ResponseEntity<List<Telemetry>> getTelemetry(@RequestParam String deviceId) {
        List<Telemetry> data = telemetryService.getTelemetryByDeviceId(deviceId);
        return ResponseEntity.ok(data);
    }
}
