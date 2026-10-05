package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.Telemetry;
import com.iot.greenhouse.repository.DeviceRepository;
import com.iot.greenhouse.repository.TelemetryRepository;
import com.iot.greenhouse.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node on a 15s interval adds ~5760 telemetry rows a day, and
 * {@code GET /api/telemetry} returns the device's whole history in one response.
 * This pins the retention sweep that stops that growing without bound.
 */
@SpringBootTest
@EnabledIf("dockerAvailable")
@TestPropertySource(properties = "app.telemetry.retention-days=30")
class TelemetryRetentionServiceTest extends TestDatabase {

    static boolean dockerAvailable() {
        return TestDatabase.isRunning();
    }

    @Autowired
    private TelemetryRetentionService retentionService;

    @Autowired
    private TelemetryRepository telemetryRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    private Device device;

    @BeforeEach
    void setUp() {
        telemetryRepository.deleteAll();
        deviceRepository.deleteAll();
        device = deviceRepository.save(Device.builder()
                .deviceId("retention-" + UUID.randomUUID().toString().substring(0, 8))
                .name("Thiet bi retention")
                .zone("Zone A")
                .status("ACTIVE")
                .build());
    }

    private void insertAged(int count, ZonedDateTime timestamp) {
        List<Telemetry> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(Telemetry.builder()
                    .device(device)
                    .timestamp(timestamp)
                    .temperature(new BigDecimal("20.00"))
                    .build());
        }
        telemetryRepository.saveAll(rows);
        telemetryRepository.flush();
    }

    @Test
    void deletesRowsOlderThanTheRetentionWindow() {
        insertAged(5, ZonedDateTime.now().minusDays(60));
        insertAged(5, ZonedDateTime.now().minusDays(1));
        assertThat(telemetryRepository.count()).isEqualTo(10);

        assertThat(retentionService.purgeExpired()).isEqualTo(5);
        assertThat(telemetryRepository.count()).isEqualTo(5);
    }

    @Test
    void keepsRecentRowsWhenNothingIsExpired() {
        insertAged(3, ZonedDateTime.now().minusHours(2));

        assertThat(retentionService.purgeExpired()).isZero();
        assertThat(telemetryRepository.count()).isEqualTo(3);
    }

    @Test
    void respectsTheWindowBoundary() {
        insertAged(1, ZonedDateTime.now().minusDays(31));
        insertAged(1, ZonedDateTime.now().minusDays(29));

        assertThat(retentionService.purgeExpired()).isEqualTo(1);
        assertThat(telemetryRepository.count()).isEqualTo(1);
    }

    @Test
    void isSafeToRunRepeatedlyAndOnAnEmptyTable() {
        assertThat(retentionService.purgeExpired()).isZero();
        insertAged(2, ZonedDateTime.now().minusDays(90));

        assertThat(retentionService.purgeExpired()).isEqualTo(2);
        assertThat(retentionService.purgeExpired()).isZero();
        assertThat(telemetryRepository.count()).isZero();
    }

    @Test
    void reportsTheConfiguredWindow() {
        assertThat(retentionService.retentionDays()).isEqualTo(30);
    }
}
