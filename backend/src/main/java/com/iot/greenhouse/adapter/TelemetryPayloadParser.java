package com.iot.greenhouse.adapter;

import com.iot.greenhouse.entity.Telemetry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.function.Consumer;

/**
 * Turns a raw MQTT telemetry payload into a {@link Telemetry}.
 *
 * <p>Kept free of any MQTT dependency so it can be unit tested on its own.
 * Individual readings that are absent, non-numeric or outside the range allowed by
 * the {@code telemetry} table CHECK constraints are dropped instead of failing the
 * whole insert, so one bad field from a device cannot discard a whole reading.
 */
@Slf4j
@Component
public class TelemetryPayloadParser {

    private static final BigDecimal MIN_PERCENT = BigDecimal.ZERO;
    private static final BigDecimal MAX_PERCENT = new BigDecimal("100");
    private static final BigDecimal MIN_LIGHT = BigDecimal.ZERO;
    private static final BigDecimal MAX_LIGHT = new BigDecimal("99999999.99");
    private static final BigDecimal MIN_TEMPERATURE = new BigDecimal("-9999.99");
    private static final BigDecimal MAX_TEMPERATURE = new BigDecimal("9999.99");

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

        Telemetry telemetry = new Telemetry();
        boolean any = false;
        any |= read(root, deviceId, new String[] { "temperature" }, telemetry::setTemperature, MIN_TEMPERATURE, MAX_TEMPERATURE);
        any |= read(root, deviceId, new String[] { "humidity" }, telemetry::setHumidity, MIN_PERCENT, MAX_PERCENT);
        any |= read(root, deviceId, new String[] { "soilMoisture", "soil_moisture" }, telemetry::setSoilMoisture, MIN_PERCENT, MAX_PERCENT);
        any |= read(root, deviceId, new String[] { "light" }, telemetry::setLight, MIN_LIGHT, MAX_LIGHT);

        if (!any) {
            log.warn("Discarding telemetry from device {}: no usable sensor readings", deviceId);
            return null;
        }
        return telemetry;
    }

    private static boolean read(JsonNode root, String deviceId, String[] names,
                                Consumer<BigDecimal> setter, BigDecimal min, BigDecimal max) {
        for (String name : names) {
            JsonNode node = root.get(name);
            if (node == null || node.isNull()) {
                continue;
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
