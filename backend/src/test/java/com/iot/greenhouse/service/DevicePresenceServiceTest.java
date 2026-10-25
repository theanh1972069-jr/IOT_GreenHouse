package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.Alert;
import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.repository.AlertRepository;
import com.iot.greenhouse.repository.DeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevicePresenceServiceTest {

    private DeviceRepository deviceRepository;
    private AlertRepository alertRepository;
    private AuditLogService auditLogService;
    private DevicePresenceService presenceService;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        alertRepository = mock(AlertRepository.class);
        auditLogService = mock(AuditLogService.class);
        presenceService = new DevicePresenceService(deviceRepository, alertRepository, auditLogService);
        ReflectionTestUtils.setField(presenceService, "offlineTimeoutSeconds", 30L);
    }

    @Test
    void detectsOfflineDeviceAndCreatesOneAlertAndAuditEntry() {
        Device device = Device.builder()
                .id(11L)
                .deviceId("node-11")
                .status("ACTIVE")
                .build();
        when(deviceRepository.findActiveDevicesNotSeenSince(any(ZonedDateTime.class)))
                .thenReturn(List.of(device));
        when(alertRepository.existsByDevice_IdAndTypeInAndStatusIn(
                eq(11L), anyCollection(), anyCollection())).thenReturn(false);

        int changed = presenceService.markStaleDevicesOffline();

        assertThat(changed).isEqualTo(1);
        assertThat(device.getStatus()).isEqualTo("OFFLINE");
        verify(alertRepository).save(any(Alert.class));
        verify(deviceRepository).saveAll(List.of(device));
        verify(auditLogService).record(eq(null), eq(null), eq("DEVICE_OFFLINE_DETECTED"),
                eq("Device"), eq("node-11"), any());
    }

    @Test
    void reconnectRestoresDeviceAndResolvesOfflineAlerts() {
        Device device = Device.builder().id(12L).deviceId("node-12").status("OFFLINE").build();
        Alert alert = Alert.builder().id(55L).device(device).type("DEVICE_OFFLINE").status("OPEN").build();
        when(alertRepository.findByDevice_IdAndTypeAndStatusIn(eq(12L), eq("DEVICE_OFFLINE"), anyList()))
                .thenReturn(List.of(alert));

        presenceService.markOnline(device);

        assertThat(device.getStatus()).isEqualTo("ACTIVE");
        assertThat(alert.getStatus()).isEqualTo("RESOLVED");
        assertThat(alert.getResolvedAt()).isNotNull();
        verify(deviceRepository).save(device);
        verify(alertRepository).saveAll(List.of(alert));
    }

    @Test
    void onlineTelemetryDoesNotMutateDevicePresence() {
        Device device = Device.builder().id(13L).deviceId("node-13").status("ACTIVE").build();

        presenceService.markOnline(device);

        verify(deviceRepository, never()).save(device);
        verify(alertRepository, never()).saveAll(anyList());
    }
}
