package com.iot.greenhouse.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemStatusRestController {

    @Value("${mqtt.enabled:false}")
    private boolean mqttEnabled;

    @GetMapping("/status")
    public SystemStatus getStatus() {
        return new SystemStatus(mqttEnabled, false);
    }

    public record SystemStatus(boolean mqttEnabled, boolean commandProtocolConfigured) {}
}