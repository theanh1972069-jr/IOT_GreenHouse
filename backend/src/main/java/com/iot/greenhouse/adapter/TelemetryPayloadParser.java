package com.iot.greenhouse.adapter;

import com.iot.greenhouse.entity.Telemetry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Turns a raw MQTT telemetry payload into a {@link Telemetry}.
 *
 * <p>Kept free of any MQTT dependency so it can be unit tested on its own.
 * Individual readings that are absent, non-numeric, use the wrong unit or fall
 * outside the supported sensor range are dropped instead of failing the
 * whole insert, so one bad field from a device cannot discard a whole reading.
 */
@Slf4j
@Component
public class TelemetryPayloadParser {

    private static final BigDecimal MIN_PERCENT = BigDecimal.ZERO;
    private static final BigDecimal MAX_PERCENT = new BigDecimal("100");
    private static final BigDecimal MIN_LIGHT = BigDecimal.ZERO;
    private static final BigDecimal MAX_LIGHT = new BigDecimal("99999999.99");
    private static final BigDecimal MIN_TEMPERATURE = new BigDecimal("-40");
    private static final BigDecimal MAX_TEMPERATURE = new BigDecimal("80");

    private final ObjectMapper objectMapper;

    public TelemetryPayloadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @return a populated telemetry, or {@code null} when the payload carries no
     *         usable reading and should be discarded.
     */
    public Telemetry parse(String payload, String deviceId) {
        if (payload == null || payload.isBlank()) {
            log.warn("Discarding empty telemetry payload from device {}", deviceId);
            return null;
        }
        JsonNode root;
        try {
            // A UTF-8 BOM is common when payloads are produced by Windows tooling and
            // is not valid JSON; it would otherwise fail the parse outright.
            String body = payload.stripLeading().startsWith("\uFEFF")
                    ? payload.substring(payload.indexOf('\uFEFF') + 1)
                    : payload;
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("Discarding malformed telemetry payload from device {}: {}", deviceId, e.getMessage());
            return null;
        }
        if (root == null || !root.isObject()) {
            log.warn("Discarding non-object telemetry payload from device {}", deviceId);
            return null;
        }
        JsonNode payloadDeviceId = root.get("device_id");
        if (payloadDeviceId != null && (!payloadDeviceId.isTextual()
                || !deviceId.equals(payloadDeviceId.asText()))) {
            log.warn("Discarding telemetry with mismatched device_id from device {}", deviceId);
            return null;
        }
        boolean structuredPayload = root.path("temp").isObject() || root.path("hum").isObject();
        JsonNode timestamp = root.get("ts");
        JsonNode sequence = root.get("seq");
        JsonNode zone = root.get("zone");
        JsonNode quality = root.get("q");
        JsonNode firmwareVersion = root.get("fw");
        if (structuredPayload && (payloadDeviceId == null || timestamp == null || !timestamp.isTextual()
                || sequence == null || !sequence.isIntegralNumber() || zone == null || !zone.isTextual()
                || quality == null || !quality.isTextual()
                || firmwareVersion == null || !firmwareVersion.isTextual())) {
            log.warn("Discarding structured telemetry without required metadata from device {}", deviceId);
            return null;
        }

        Telemetry telemetry = new Telemetry();
        if (quality != null && !quality.isNull()) {
            if (!quality.isTextual() || quality.asText().isBlank() || quality.asText().length() > 20) {
                log.warn("Discarding telemetry with invalid q from device {}", deviceId);
                return null;
            }
            telemetry.setQuality(quality.asText());
        }
        boolean any = false;
        any |= read(root, deviceId, new String[] { "temperature", "temp" }, "C",
                telemetry::setTemperature, MIN_TEMPERATURE, MAX_TEMPERATURE);
        any |= read(root, deviceId, new String[] { "humidity", "hum" }, "%",
                telemetry::setHumidity, MIN_PERCENT, MAX_PERCENT);
        any |= read(root, deviceId, new String[] { "soilMoisture", "soil_moisture", "soil" }, "%",
                telemetry::setSoilMoisture, MIN_PERCENT, MAX_PERCENT);
        any |= read(root, deviceId, new String[] { "light" }, "lx",
                telemetry::setLight, MIN_LIGHT, MAX_LIGHT);

        if (!any && !"sensor_fault".equalsIgnoreCase(telemetry.getQuality())) {
            log.warn("Discarding telemetry from device {}: no usable sensor readings", deviceId);
            return null;
        }
        if (zone != null && !zone.isNull()) {
            if (!zone.isTextual() || zone.asText().isBlank() || zone.asText().length() > 50) {
                log.warn("Discarding telemetry with invalid zone from device {}", deviceId);
                return null;
            }
            telemetry.setZone(zone.asText());
        }
        if (timestamp != null && !timestamp.isNull()) {
            if (!timestamp.isTextual()) {
                log.warn("Discarding telemetry with invalid ts from device {}", deviceId);
                return null;
            }
            try {
                telemetry.setTimestamp(ZonedDateTime.parse(timestamp.asText()));
            } catch (DateTimeException e) {
                log.warn("Discarding telemetry with invalid ts from device {}: {}", deviceId, timestamp.asText());
                return null;
            }
        }
        if (sequence != null && !sequence.isNull()) {
            if (!sequence.isIntegralNumber() || !sequence.canConvertToLong() || sequence.longValue() < 0) {
                log.warn("Discarding telemetry with invalid seq from device {}: {}", deviceId, sequence);
                return null;
            }
            telemetry.setSequenceNumber(sequence.longValue());
        }
        if (firmwareVersion != null && !firmwareVersion.isNull()) {
            if (!firmwareVersion.isTextual() || firmwareVersion.asText().isBlank()
                    || firmwareVersion.asText().length() > 50) {
                log.warn("Discarding telemetry with invalid fw from device {}", deviceId);
                return null;
            }
            telemetry.setFirmwareVersion(firmwareVersion.asText());
        }
        return telemetry;
    }

    private static boolean read(JsonNode root, String deviceId, String[] names, String expectedUnit,
                                Consumer<BigDecimal> setter, BigDecimal min, BigDecimal max) {
        for (String name : names) {
            JsonNode node = root.get(name);
            if (node == null || node.isNull()) {
                continue;
            }
            if (node.isObject()) {
                JsonNode unit = node.get("u");
                JsonNode value = node.get("v");
                if (unit == null || !unit.isTextual() || !Objects.equals(expectedUnit, unit.asText())) {
                    log.warn("Ignoring {} with unexpected or missing unit for device {}", name, deviceId);
                    return false;
                }
                if (value == null || value.isNull()) {
                    log.warn("Ignoring structured sensor value without v for device {}", deviceId);
                    return false;
                }
                node = value;
            }
            if (!node.isNumber() && !node.isTextual()) {
                log.warn("Ignoring non-numeric {} for device {}: {}", name, deviceId, node);
                return false;
            }
            BigDecimal value;
            try {
                value = node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText().trim());
            } catch (NumberFormatException e) {
                log.warn("Ignoring unparsable {} for device {}: {}", name, deviceId, node);
                return false;
            }
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                log.warn("Ignoring out-of-range {} for device {}: {}", name, deviceId, value);
                return false;
            }
            setter.accept(value);
            return true;
        }
        return false;
    }
}
