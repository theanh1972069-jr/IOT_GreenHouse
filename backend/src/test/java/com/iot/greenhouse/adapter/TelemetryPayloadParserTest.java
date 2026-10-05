package com.iot.greenhouse.adapter;

import com.iot.greenhouse.entity.Telemetry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

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
}
