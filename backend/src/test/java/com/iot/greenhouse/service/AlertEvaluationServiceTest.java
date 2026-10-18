package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.Alert;
import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.DeviceConfiguration;
import com.iot.greenhouse.entity.Telemetry;
import com.iot.greenhouse.repository.AlertRepository;
import com.iot.greenhouse.repository.DeviceConfigurationRepository;
import com.iot.greenhouse.repository.DeviceRepository;
import com.iot.greenhouse.repository.TelemetryRepository;
import com.iot.greenhouse.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telemetry used to be stored without ever producing an alert, so the alerts screen
 * had no data source. These tests pin the threshold comparison and the duplicate
 * guard that stops a misbehaving device from raising one alert per reading.
 */
@SpringBootTest
@EnabledIf("dockerAvailable")
class AlertEvaluationServiceTest extends TestDatabase {

    static boolean dockerAvailable() {
        return TestDatabase.isRunning();
    }

    @Autowired
    private AlertEvaluationService alertEvaluationService;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private DeviceConfigurationRepository configurationRepository;

    @Autowired
    private TelemetryRepository telemetryRepository;

    private Device device;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        configurationRepository.deleteAll();
        deviceRepository.deleteAll();

        device = deviceRepository.save(Device.builder()
                .deviceId("alert-" + UUID.randomUUID().toString().substring(0, 8))
                .name("Thiết bị cảnh báo")
                .zone("Zone A")
                .status("ACTIVE")
                .build());

        configurationRepository.save(DeviceConfiguration.builder()
                .device(device)
                .temperatureHigh(new BigDecimal("35.00"))
                .soilMoistureLow(new BigDecimal("30.00"))
                .heartbeatInterval(10)
                .autoWatering(true)
                .build());
    }

    private Telemetry reading(String temperature, String soilMoisture) {
        // timestamp is NOT NULL in the schema and is normally filled in by
        // TelemetryService, so the fixture has to supply it.
        return telemetryRepository.save(Telemetry.builder()
                .device(device)
                .timestamp(ZonedDateTime.now())
                .temperature(temperature == null ? null : new BigDecimal(temperature))
                .soilMoisture(soilMoisture == null ? null : new BigDecimal(soilMoisture))
                .build());
    }

    // --- temperature ---------------------------------------------------------

    @Test
    void raisesAlertWhenTemperatureExceedsThreshold() {
        // Threshold is 35C; 37C is 2C over, which is HIGH rather than CRITICAL.
        alertEvaluationService.evaluate(device, reading("37.0", null));

        assertThat(alertRepository.findAll())
                .singleElement()
                .satisfies(alert -> {
                    assertThat(alert.getType()).isEqualTo("TEMPERATURE_HIGH");
                    assertThat(alert.getSeverity()).isEqualTo("HIGH");
                    assertThat(alert.getStatus()).isEqualTo("OPEN");
                    assertThat(alert.getMessage()).contains("37.0").contains("35.0");
                });
    }

    @Test
    void escalatesToCriticalWhenFarAboveThreshold() {
        // 44C is 9C over the 35C threshold, past the 5C escalation margin.
        alertEvaluationService.evaluate(device, reading("44.0", null));

        assertThat(alertRepository.findAll())
                .singleElement()
                .extracting(Alert::getSeverity)
                .isEqualTo("CRITICAL");
    }

    @Test
    void doesNotAlertAtOrBelowThreshold() {
        alertEvaluationService.evaluate(device, reading("35.0", null));
        alertEvaluationService.evaluate(device, reading("20.0", null));

        assertThat(alertRepository.findAll()).isEmpty();
    }

    // --- soil moisture -------------------------------------------------------

    @Test
    void raisesAlertWhenSoilMoistureDropsBelowThreshold() {
        alertEvaluationService.evaluate(device, reading(null, "12.0"));

        assertThat(alertRepository.findAll())
                .singleElement()
                .satisfies(alert -> {
                    assertThat(alert.getType()).isEqualTo("SOIL_MOISTURE_LOW");
                    assertThat(alert.getSeverity()).isEqualTo("HIGH");
                });
    }

    @Test
    void slightlyDrySoilIsOnlyMediumSeverity() {
        alertEvaluationService.evaluate(device, reading(null, "28.0"));

        assertThat(alertRepository.findAll())
                .singleElement()
                .extracting(Alert::getSeverity)
                .isEqualTo("MEDIUM");
    }

    @Test
    void doesNotAlertWhenSoilMoistureIsHealthy() {
        alertEvaluationService.evaluate(device, reading(null, "55.0"));

        assertThat(alertRepository.findAll()).isEmpty();
    }

    @Test
    void raisesAlertForSensorFaultQualityFlag() {
        Telemetry telemetry = reading(null, null);
        telemetry.setQuality("sensor_fault");

        alertEvaluationService.evaluate(device, telemetry);

        assertThat(alertRepository.findAll())
                .singleElement()
                .satisfies(alert -> {
                    assertThat(alert.getType()).isEqualTo("SENSOR_FAULT");
                    assertThat(alert.getSeverity()).isEqualTo("HIGH");
                });
    }

    // --- duplicate guard -----------------------------------------------------

    @Test
    void doesNotRepeatAnUnresolvedAlertOfTheSameType() {
        alertEvaluationService.evaluate(device, reading("42.0", null));
        alertEvaluationService.evaluate(device, reading("43.0", null));
        alertEvaluationService.evaluate(device, reading("44.0", null));

        assertThat(alertRepository.findAll()).hasSize(1);
    }

    @Test
    void keepsOneAlertPerTypeIndependently() {
        alertEvaluationService.evaluate(device, reading("42.0", "10.0"));

        assertThat(alertRepository.findAll())
                .extracting(Alert::getType)
                .containsExactlyInAnyOrder("TEMPERATURE_HIGH", "SOIL_MOISTURE_LOW");
    }

    @Test
    void acknowledgedAlertStillBlocksDuplicates() {
        alertEvaluationService.evaluate(device, reading("42.0", null));
        Alert alert = alertRepository.findAll().get(0);
        alert.setStatus("ACKNOWLEDGED");
        alertRepository.save(alert);

        alertEvaluationService.evaluate(device, reading("43.0", null));

        assertThat(alertRepository.findAll()).hasSize(1);
    }

    @Test
    void aNewAlertIsRaisedAfterThePreviousOneWasResolved() {
        alertEvaluationService.evaluate(device, reading("42.0", null));
        Alert alert = alertRepository.findAll().get(0);
        alert.setStatus("RESOLVED");
        alertRepository.save(alert);

        alertEvaluationService.evaluate(device, reading("43.0", null));

        assertThat(alertRepository.findAll()).hasSize(2);
    }

    // --- edge cases ----------------------------------------------------------

    @Test
    void ignoresReadingsThatAreWithinRange() {
        alertEvaluationService.evaluate(device, reading("22.0", "48.0"));

        assertThat(alertRepository.findAll()).isEmpty();
    }

    @Test
    void fallsBackToDefaultThresholdsWhenDeviceHasNoConfiguration() {
        configurationRepository.deleteAll();

        // Default high is 35C, so 36C still breaches without any stored config.
        alertEvaluationService.evaluate(device, reading("36.0", null));

        assertThat(alertRepository.findAll())
                .singleElement()
                .extracting(Alert::getType)
                .isEqualTo("TEMPERATURE_HIGH");
    }

    @Test
    void respectsConfiguredThresholdInsteadOfDefault() {
        DeviceConfiguration configuration = configurationRepository.findByDeviceId(device.getId()).orElseThrow();
        configuration.setTemperatureHigh(new BigDecimal("50.00"));
        configurationRepository.save(configuration);

        // 41.5C breaches the 35C default but is fine against the configured 50C.
        alertEvaluationService.evaluate(device, reading("41.5", null));

        assertThat(alertRepository.findAll()).isEmpty();
    }
}
