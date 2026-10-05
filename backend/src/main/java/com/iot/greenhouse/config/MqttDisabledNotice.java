package com.iot.greenhouse.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Makes the most confusing failure mode visible on startup: with MQTT disabled the
 * backend still boots and serves the dashboard, but silently drops every device
 * message, so the charts just stop moving with nothing in the log to explain why.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mqtt.enabled", havingValue = "false", matchIfMissing = true)
public class MqttDisabledNotice {

    private final Environment environment;

    public MqttDisabledNotice(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnAboutDisabledMqtt() {
        String broker = environment.getProperty("mqtt.broker-url", "tcp://localhost:1883");
        log.warn("""

                ==================== MQTT IS DISABLED ====================
                 Device telemetry will NOT reach the database, and the
                 dashboard will silently show frozen sensor values.

                 To fix, restart the backend with MQTT enabled:
                   $env:MQTT_ENABLED="true"
                   $env:MQTT_BROKER_URL="{}"
                   .\\mvnw.cmd spring-boot:run

                 Confirm the broker is reachable:
                   Test-NetConnection -ComputerName localhost -Port {}
                =========================================================
                """, broker, broker.contains(":") ? broker.split(":")[1].split("/")[0] : "1883");
    }
}
