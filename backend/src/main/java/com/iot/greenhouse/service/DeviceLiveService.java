package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.Alert;
import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.DeviceConfiguration;
import com.iot.greenhouse.entity.Telemetry;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.model.DeviceLiveStatus;
import com.iot.greenhouse.model.DeviceLiveStatus.ActuatorLiveView;
import com.iot.greenhouse.repository.AlertRepository;
import com.iot.greenhouse.repository.DeviceConfigurationRepository;
import com.iot.greenhouse.repository.DeviceRepository;
import com.iot.greenhouse.repository.TelemetryRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceLiveService {

    private final ConcurrentMap<String, DeviceLiveStatus> liveStore = new ConcurrentHashMap<>();

    private final DeviceRepository deviceRepository;
    private final DeviceConfigurationRepository configurationRepository;
    private final TelemetryRepository telemetryRepository;
    private final AlertRepository alertRepository;
    private final MqttPublisherService mqttPublisherService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * Standard wired actuators connected to the ESP32 node pins.
     * Default power is 0.0 W. Only current measured power is tracked.
     */
    public static List<ActuatorLiveView> createDefaultActuators() {
        return List.of(
                new ActuatorLiveView("valve", "Van tưới nước (IN1)", "GPIO 25 (IN1)", "VALVE", false, 0.0, 4.8),
                new ActuatorLiveView("fan", "Quạt thông gió (IN2)", "GPIO 33 (IN2)", "FAN", false, 0.0, 1.8),
                new ActuatorLiveView("light", "Đèn quang hợp LED (IN3)", "GPIO 32 (IN3)", "LIGHT", false, 0.0, 9.6)
        );
    }

    public void refreshOnlineStatus(DeviceLiveStatus status) {
        if (status == null) return;
        boolean wasOnline = status.isOnline();
        boolean stillRecent = status.getLastSeen() != null &&
                status.getLastSeen().isAfter(ZonedDateTime.now().minusSeconds(25));

        if (!stillRecent) {
            status.setOnline(false);
            status.setTotalPowerW(0.0);
            if (status.getActuators() != null) {
                List<ActuatorLiveView> zeroed = new ArrayList<>();
                for (ActuatorLiveView act : status.getActuators()) {
                    zeroed.add(new ActuatorLiveView(act.id(), act.name(), act.pin(), act.type(), act.state(), 0.0, act.ratedPowerW()));
                }
                status.setActuators(zeroed);
            }
        } else if (!wasOnline) {
            status.setOnline(false);
            status.setTotalPowerW(0.0);
        }
    }

    public DeviceLiveStatus getOrCreateLiveStatus(String deviceId) {
        DeviceLiveStatus status = liveStore.computeIfAbsent(deviceId, id -> {
            Optional<Device> deviceOpt = deviceRepository.findByDeviceId(id);
            String name = deviceOpt.map(Device::getName).orElse(id);
            String zone = deviceOpt.map(Device::getZone).orElse("Nhà kính");

            Double tempHigh = 35.0;
            Double soilLow = 30.0;
            boolean auto = true;

            if (deviceOpt.isPresent()) {
                Optional<DeviceConfiguration> configOpt = configurationRepository.findByDeviceId(deviceOpt.get().getId());
                if (configOpt.isPresent()) {
                    DeviceConfiguration cfg = configOpt.get();
                    if (cfg.getTemperatureHigh() != null) tempHigh = cfg.getTemperatureHigh().doubleValue();
                    if (cfg.getSoilMoistureLow() != null) soilLow = cfg.getSoilMoistureLow().doubleValue();
                    if (cfg.getAutoWatering() != null) auto = cfg.getAutoWatering();
                }
            }

            DeviceLiveStatus newStatus = DeviceLiveStatus.builder()
                    .deviceId(id)
                    .deviceName(name)
                    .zone(zone)
                    .online(deviceOpt.map(d -> "ACTIVE".equalsIgnoreCase(d.getStatus())).orElse(false))
                    .lastSeen(deviceOpt.map(Device::getLastSeen).orElse(null))
                    .autoMode(auto)
                    .tempThreshold(tempHigh)
                    .soilThreshold(soilLow)
                    .actuators(new ArrayList<>(createDefaultActuators()))
                    .totalPowerW(0.0)
                    .build();

            if (deviceOpt.isPresent()) {
                List<Telemetry> past = telemetryRepository.findByDeviceIdOrderByTimestampDesc(deviceOpt.get().getId());
                if (past != null && !past.isEmpty()) {
                    Telemetry latestT = past.get(0);
                    if (latestT.getTemperature() != null) newStatus.setTemperature(latestT.getTemperature().doubleValue());
                    if (latestT.getHumidity() != null) newStatus.setHumidity(latestT.getHumidity().doubleValue());
                    if (latestT.getSoilMoisture() != null) newStatus.setSoilMoisture(latestT.getSoilMoisture().intValue());
                }
            }

            newStatus.recalculateTotalPower();
            return newStatus;
        });

        refreshOnlineStatus(status);
        return status;
    }

    /**
     * Updates in-memory live status when telemetry is received from ESP32 via MQTT.
     * Does NOT save actuator or power data to the database, fulfilling the requirement.
     */
    public void updateFromTelemetry(String deviceId, String rawPayload, Telemetry telemetry) {
        DeviceLiveStatus status = getOrCreateLiveStatus(deviceId);

        if (telemetry != null) {
            if (telemetry.getTemperature() != null) status.setTemperature(telemetry.getTemperature().doubleValue());
            if (telemetry.getHumidity() != null) status.setHumidity(telemetry.getHumidity().doubleValue());
            if (telemetry.getSoilMoisture() != null) status.setSoilMoisture(telemetry.getSoilMoisture().intValue());
        }

        status.setOnline(true);
        status.setLastSeen(ZonedDateTime.now());

        if (rawPayload != null && !rawPayload.isBlank()) {
            try {
                String body = rawPayload.stripLeading().startsWith("\uFEFF")
                        ? rawPayload.substring(rawPayload.indexOf('\uFEFF') + 1)
                        : rawPayload;
                JsonNode root = objectMapper.readTree(body);

                if (root != null && root.isObject()) {
                    if (root.has("temperature") && root.get("temperature").isNumber()) {
                        status.setTemperature(root.get("temperature").asDouble());
                    }
                    if (root.has("humidity") && root.get("humidity").isNumber()) {
                        status.setHumidity(root.get("humidity").asDouble());
                    }
                    if (root.has("soilMoisture") && root.get("soilMoisture").isNumber()) {
                        status.setSoilMoisture(root.get("soilMoisture").asInt());
                    }

                    if (root.has("auto")) {
                        status.setAutoMode(root.get("auto").asInt(1) == 1);
                    }
                    if (root.has("tempThreshold")) {
                        status.setTempThreshold(root.get("tempThreshold").asDouble(status.getTempThreshold()));
                    }
                    if (root.has("soilThreshold")) {
                        status.setSoilThreshold(root.get("soilThreshold").asDouble(status.getSoilThreshold()));
                    }
                    if (root.has("lightTempThreshold")) {
                        status.setLightTempThreshold(root.get("lightTempThreshold").asDouble(status.getLightTempThreshold()));
                    }

                    // Check for individual actuator states
                    boolean valveState = root.has("valve") ? root.get("valve").asInt(0) == 1 : false;
                    boolean fanState = root.has("fan") ? root.get("fan").asInt(0) == 1 : false;
                    boolean lightState = root.has("light") ? root.get("light").asInt(0) == 1 : false;

                    // If actuators array exists, parse it directly using actual reported power
                    if (root.has("actuators") && root.get("actuators").isArray()) {
                        List<ActuatorLiveView> parsed = new ArrayList<>();
                        for (JsonNode item : root.get("actuators")) {
                            String id = item.has("id") ? item.get("id").asText() : "";
                            String name = item.has("name") ? item.get("name").asText() : id;
                            String pin = item.has("pin") ? item.get("pin").asText() : "";
                            String type = item.has("type") ? item.get("type").asText() : id.toUpperCase();
                            boolean state = item.has("state") && (item.get("state").asInt() == 1 || item.get("state").asBoolean());
                            double ratedW = item.has("ratedW") ? item.get("ratedW").asDouble(0.0) : 0.0;
                            double powerW = item.has("powerW") ? item.get("powerW").asDouble(0.0) : 0.0;
                            parsed.add(new ActuatorLiveView(id, name, pin, type, state, powerW, ratedW));
                        }
                        if (!parsed.isEmpty()) {
                            status.setActuators(parsed);
                        }
                    } else if (root.has("valve") || root.has("fan") || root.has("light")) {
                        // Update default actuators
                        updateActuatorState(status, "valve", valveState);
                        updateActuatorState(status, "fan", fanState);
                        updateActuatorState(status, "light", lightState);
                    }

                    if (root.has("totalPowerW")) {
                        status.setTotalPowerW(root.get("totalPowerW").asDouble(0.0));
                    }
                }
            } catch (Exception e) {
                log.debug("Could not parse extra actuator/power payload from device {}: {}", deviceId, e.getMessage());
            }
        }

        if (!status.isOnline()) {
            status.setTotalPowerW(0.0);
        } else {
            status.recalculateTotalPower();
        }
    }

    private void updateActuatorState(DeviceLiveStatus status, String id, boolean state) {
        List<ActuatorLiveView> current = status.getActuators();
        List<ActuatorLiveView> updated = new ArrayList<>();
        boolean found = false;
        for (ActuatorLiveView act : current) {
            if (act.id().equalsIgnoreCase(id)) {
                found = true;
                double defaultRated = act.id().equalsIgnoreCase("fan") ? 1.8 : act.id().equalsIgnoreCase("valve") ? 4.8 : 9.6;
                double rated = act.ratedPowerW() > 0 ? act.ratedPowerW() : defaultRated;
                double power = (status.isOnline() && state) ? rated : 0.0;
                updated.add(new ActuatorLiveView(act.id(), act.name(), act.pin(), act.type(), state, power, rated));
            } else {
                updated.add(act);
            }
        }
        if (!found) {
            String name = id.equalsIgnoreCase("fan") ? "Quạt thông gió (IN2)" : id.equalsIgnoreCase("light") ? "Đèn quang hợp (IN3)" : "Van tưới nước (IN1)";
            String pin = id.equalsIgnoreCase("fan") ? "GPIO 33 (IN2)" : id.equalsIgnoreCase("light") ? "GPIO 32 (IN3)" : "GPIO 25 (IN1)";
            double rated = id.equalsIgnoreCase("fan") ? 1.8 : id.equalsIgnoreCase("valve") ? 4.8 : 9.6;
            double power = (status.isOnline() && state) ? rated : 0.0;
            updated.add(new ActuatorLiveView(id, name, pin, id.toUpperCase(), state, power, rated));
        }
        status.setActuators(updated);
    }

    /**
     * Controls an actuator manually from the web. Updates live state immediately and
     * publishes an MQTT command to the device control topic.
     */
    public DeviceLiveStatus controlActuator(String deviceId, String actuatorId, boolean turnOn,
                                           User actor, HttpServletRequest httpRequest) {
        if ("lamp".equalsIgnoreCase(actuatorId)) {
            actuatorId = "light";
        } else if ("pump".equalsIgnoreCase(actuatorId)) {
            actuatorId = "valve";
        }

        DeviceLiveStatus status = getOrCreateLiveStatus(deviceId);

        if ("all".equalsIgnoreCase(actuatorId)) {
            // Emergency stop / Turn off all
            List<ActuatorLiveView> stopped = new ArrayList<>();
            for (ActuatorLiveView act : status.getActuators()) {
                stopped.add(new ActuatorLiveView(act.id(), act.name(), act.pin(), act.type(), false, 0.0, 0.0));
            }
            status.setActuators(stopped);
            status.recalculateTotalPower();

            String topic = "greenhouse/" + deviceId + "/control";
            String payload = "{\"valve\":0,\"fan\":0,\"light\":0}";
            mqttPublisherService.publish(topic, payload);

            if (actor != null) {
                auditLogService.record(actor, httpRequest, "ACTUATOR_EMERGENCY_STOP", "Device",
                        deviceId, Map.of("action", "STOP_ALL", "state", "OFF"));
            }
            return status;
        }

        boolean switchedFromAuto = false;
        if (status.isAutoMode()) {
            status.setAutoMode(false);
            switchedFromAuto = true;
            deviceRepository.findByDeviceId(deviceId).ifPresent(device -> {
                DeviceConfiguration config = configurationRepository.findByDeviceId(device.getId())
                        .orElseGet(() -> DeviceConfiguration.builder().device(device).build());
                config.setAutoWatering(false);
                configurationRepository.save(config);
            });
        }

        updateActuatorState(status, actuatorId, turnOn);
        status.recalculateTotalPower();

        int stateInt = turnOn ? 1 : 0;
        String topic = "greenhouse/" + deviceId + "/control";
        String payload = switchedFromAuto
                ? String.format("{\"auto\":0,\"%s\":%d}", actuatorId.toLowerCase(), stateInt)
                : String.format("{\"%s\":%d}", actuatorId.toLowerCase(), stateInt);
        mqttPublisherService.publish(topic, payload);

        if (actor != null) {
            auditLogService.record(actor, httpRequest, "ACTUATOR_CONTROL", "Device",
                    deviceId, Map.of("actuator", actuatorId, "state", turnOn ? "ON" : "OFF"));
        }

        return status;
    }

    /**
     * Switches between Auto and Manual mode.
     */
    public DeviceLiveStatus setAutoMode(String deviceId, boolean autoMode,
                                        User actor, HttpServletRequest httpRequest) {
        DeviceLiveStatus status = getOrCreateLiveStatus(deviceId);
        status.setAutoMode(autoMode);

        deviceRepository.findByDeviceId(deviceId).ifPresent(device -> {
            DeviceConfiguration config = configurationRepository.findByDeviceId(device.getId())
                    .orElseGet(() -> DeviceConfiguration.builder().device(device).build());
            config.setAutoWatering(autoMode);
            configurationRepository.save(config);
        });

        String topic = "greenhouse/" + deviceId + "/control";
        String payload = String.format("{\"auto\":%d}", autoMode ? 1 : 0);
        mqttPublisherService.publish(topic, payload);

        if (actor != null) {
            auditLogService.record(actor, httpRequest, "MODE_CHANGE", "Device",
                    deviceId, Map.of("mode", autoMode ? "AUTO" : "MANUAL"));
        }

        return status;
    }

    /**
     * Updates threshold settings directly from web, saves to DB for persistence,
     * updates in-memory live state, and dispatches command to ESP32 over MQTT.
     */
    @Transactional
    public DeviceLiveStatus updateThresholds(String deviceId, BigDecimal temperatureHigh,
                                             BigDecimal soilMoistureLow, BigDecimal lightTempLow,
                                             Boolean autoMode,
                                             User actor, HttpServletRequest httpRequest) {
        DeviceLiveStatus status = getOrCreateLiveStatus(deviceId);

        if (temperatureHigh != null) status.setTempThreshold(temperatureHigh.doubleValue());
        if (soilMoistureLow != null) status.setSoilThreshold(soilMoistureLow.doubleValue());
        if (lightTempLow != null) status.setLightTempThreshold(lightTempLow.doubleValue());
        if (autoMode != null) status.setAutoMode(autoMode);

        // Persist to DeviceConfiguration table
        Optional<Device> deviceOpt = deviceRepository.findByDeviceId(deviceId);
        if (deviceOpt.isPresent()) {
            Device device = deviceOpt.get();
            DeviceConfiguration config = configurationRepository.findByDeviceId(device.getId())
                    .orElseGet(() -> DeviceConfiguration.builder().device(device).build());
            if (temperatureHigh != null) config.setTemperatureHigh(temperatureHigh);
            if (soilMoistureLow != null) config.setSoilMoistureLow(soilMoistureLow);
            if (autoMode != null) config.setAutoWatering(autoMode);
            if (actor != null) config.setUpdatedBy(actor);
            configurationRepository.save(config);
        }

        // Send command to ESP32 via MQTT so it immediately operates with the new thresholds
        String topic = "greenhouse/" + deviceId + "/control";
        String payload = String.format(
                java.util.Locale.US,
                "{\"tempThreshold\":%.1f,\"soilThreshold\":%.1f,\"lightTempThreshold\":%.1f,\"auto\":%d}",
                status.getTempThreshold(),
                status.getSoilThreshold(),
                status.getLightTempThreshold(),
                status.isAutoMode() ? 1 : 0
        );
        mqttPublisherService.publish(topic, payload);

        if (actor != null) {
            auditLogService.record(actor, httpRequest, "DEVICE_THRESHOLD_UPDATE", "Device",
                    deviceId, Map.of("tempThreshold", status.getTempThreshold(),
                            "soilThreshold", status.getSoilThreshold(),
                            "lightTempThreshold", status.getLightTempThreshold(),
                            "auto", status.isAutoMode()));
        }

        return status;
    }

    public List<DeviceLiveStatus> getAllLiveStatuses() {
        List<Device> all = deviceRepository.findAll();
        List<DeviceLiveStatus> result = new ArrayList<>();
        for (Device d : all) {
            result.add(getOrCreateLiveStatus(d.getDeviceId()));
        }
        return result;
    }
}
