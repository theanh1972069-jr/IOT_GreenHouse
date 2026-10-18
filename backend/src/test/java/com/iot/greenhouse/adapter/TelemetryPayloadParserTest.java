package com.iot.greenhouse.adapter;

import com.iot.greenhouse.entity.Telemetry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The previous implementation hard-coded 31.5 whenever the payload mentioned
 * "temperature" and never read any other field. These tests pin the real parsing.
 */
class TelemetryPayloadParserTest {

    private final TelemetryPayloadParser parser = new TelemetryPayloadParser(new ObjectMapper());

    @Test
    void parsesAllFourReadingsFromRealPayload() {
        Telemetry telemetry = parser.parse(
                "{\"temperature\":25.4,\"humidity\":61.2,\"soilMoisture\":48.7,\"light\":1234.5}", "dev-01");

        assertThat(telemetry).isNotNull();
        assertThat(telemetry.getTemperature()).isEqualByComparingTo("25.4");
        assertThat(telemetry.getHumidity()).isEqualByComparingTo("61.2");
        assertThat(telemetry.getSoilMoisture()).isEqualByComparingTo("48.7");
        assertThat(telemetry.getLight()).isEqualByComparingTo("1234.5");
    }

    @Test
    void parsesReportPayloadAndPreservesContext() {
        Telemetry telemetry = parser.parse(
                "{\"device_id\":\"dev-01\",\"ts\":\"2026-11-02T08:15:00Z\",\"seq\":1042,"
                        + "\"zone\":\"livingroom\",\"temp\":{\"v\":29.4,\"u\":\"C\"},"
                        + "\"hum\":{\"v\":68.1,\"u\":\"%\"},\"q\":\"ok\",\"fw\":\"1.0.2\"}",
                "dev-01");

        assertThat(telemetry).isNotNull();
        assertThat(telemetry.getTimestamp()).isEqualTo(ZonedDateTime.parse("2026-11-02T08:15:00Z"));
        assertThat(telemetry.getSequenceNumber()).isEqualTo(1042L);
        assertThat(telemetry.getQuality()).isEqualTo("ok");
        assertThat(telemetry.getFirmwareVersion()).isEqualTo("1.0.2");
        assertThat(telemetry.getTemperature()).isEqualByComparingTo("29.4");
        assertThat(telemetry.getHumidity()).isEqualByComparingTo("68.1");
    }

    @Test
    void rejectsPayloadForDifferentDevice() {
        assertThat(parser.parse("{\"device_id\":\"other\",\"temperature\":22}", "dev-01")).isNull();
    }

    @Test
    void rejectsInvalidSequenceAndTimestamp() {
        assertThat(parser.parse("{\"temperature\":22,\"seq\":-1}", "dev-01")).isNull();
        assertThat(parser.parse("{\"temperature\":22,\"seq\":1.5}", "dev-01")).isNull();
        assertThat(parser.parse("{\"temperature\":22,\"ts\":\"not-a-time\"}", "dev-01")).isNull();
    }

    @Test
    void doesNotFabricateTemperature() {
        Telemetry telemetry = parser.parse("{\"humidity\":55.0}", "dev-01");

        assertThat(telemetry).isNotNull();
        assertThat(telemetry.getTemperature()).isNull();
        assertThat(telemetry.getHumidity()).isEqualByComparingTo("55.0");
    }

    @Test
    void acceptsSnakeCaseSoilMoisture() {
        Telemetry telemetry = parser.parse("{\"soil_moisture\":33.3}", "dev-01");

        assertThat(telemetry).isNotNull();
        assertThat(telemetry.getSoilMoisture()).isEqualByComparingTo("33.3");
    }

    @Test
    void acceptsNumericStrings() {
        Telemetry telemetry = parser.parse("{\"temperature\":\"27.5\",\"humidity\":\"44\"}", "dev-01");

        assertThat(telemetry).isNotNull();
        assertThat(telemetry.getTemperature()).isEqualByComparingTo("27.5");
        assertThat(telemetry.getHumidity()).isEqualByComparingTo("44");
    }

    @Test
    void acceptsLegacyFlatTempAndHumAliases() {
        Telemetry telemetry = parser.parse("{\"temp\":27.5,\"hum\":44}", "dev-01");

        assertThat(telemetry).isNotNull();
        assertThat(telemetry.getTemperature()).isEqualByComparingTo("27.5");
        assertThat(telemetry.getHumidity()).isEqualByComparingTo("44");
    }

    @Test
    void dropsOutOfRangeValuesInsteadOfFailingTheInsert() {
        // humidity > 100 and light < 0 violate the telemetry CHECK constraints.
        Telemetry telemetry = parser.parse("{\"temperature\":20.0,\"humidity\":150,\"light\":-5}", "dev-01");

        assertThat(telemetry).isNotNull();
        assertThat(telemetry.getTemperature()).isEqualByComparingTo("20.0");
        assertThat(telemetry.getHumidity()).isNull();
        assertThat(telemetry.getLight()).isNull();
    }

    @Test
    void discardsMalformedJson() {
        assertThat(parser.parse("not json at all", "dev-01")).isNull();
        assertThat(parser.parse("", "dev-01")).isNull();
    }

    @Test
    void discardsNonObjectPayloads() {
        assertThat(parser.parse("[1,2,3]", "dev-01")).isNull();
        assertThat(parser.parse("\"a string\"", "dev-01")).isNull();
    }

    @Test
    void discardsPayloadWithNoUsableReadings() {
        assertThat(parser.parse("{}", "dev-01")).isNull();
        assertThat(parser.parse("{\"temperature\":null,\"humidity\":\"warm\"}", "dev-01")).isNull();
    }

    @Test
    void keepsZeroReadings() {
        Telemetry telemetry = parser.parse("{\"humidity\":0,\"light\":0}", "dev-01");

        assertThat(telemetry).isNotNull();
        assertThat(telemetry.getHumidity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(telemetry.getLight()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void allowsNegativeTemperature() {
        Telemetry telemetry = parser.parse("{\"temperature\":-3.5}", "dev-01");

        assertThat(telemetry).isNotNull();
        assertThat(telemetry.getTemperature()).isEqualByComparingTo("-3.5");
    }

    @Test
    void rejectsTemperatureOutsideDht22Range() {
        assertThat(parser.parse("{\"temperature\":81}", "dev-01")).isNull();
        assertThat(parser.parse("{\"temperature\":-41}", "dev-01")).isNull();
    }

    @Test
    void preservesSensorFaultEventsWithoutUsableReadings() {
        Telemetry telemetry = parser.parse(
                "{\"device_id\":\"dev-01\",\"ts\":\"2026-11-02T08:15:00Z\","
                        + "\"seq\":1043,\"zone\":\"livingroom\",\"q\":\"sensor_fault\",\"fw\":\"1.0.2\"}",
                "dev-01");

        assertThat(telemetry).isNotNull();
        assertThat(telemetry.getTemperature()).isNull();
        assertThat(telemetry.getQuality()).isEqualTo("sensor_fault");
    }

    @Test
    void rejectsStructuredPayloadWithMissingMetadataOrWrongUnits() {
        Telemetry wrongUnit = parser.parse(
                "{\"device_id\":\"dev-01\",\"temp\":{\"v\":25,\"u\":\"F\"},\"hum\":{\"v\":50,\"u\":\"%\"},"
                        + "\"ts\":\"2026-11-02T08:15:00Z\",\"seq\":1,\"zone\":\"livingroom\","
                        + "\"q\":\"ok\",\"fw\":\"1.0.2\"}",
                "dev-01");
        assertThat(wrongUnit).isNotNull();
        assertThat(wrongUnit.getTemperature()).isNull();
        assertThat(wrongUnit.getHumidity()).isEqualByComparingTo("50");

        assertThat(parser.parse(
                "{\"device_id\":\"dev-01\",\"temp\":{\"v\":25,\"u\":\"C\"},\"hum\":{\"v\":50,\"u\":\"%\"},"
                        + "\"ts\":\"2026-11-02T08:15:00Z\",\"seq\":1,\"q\":\"ok\",\"fw\":\"1.0.2\"}",
                "dev-01")).isNull();
    }
}
