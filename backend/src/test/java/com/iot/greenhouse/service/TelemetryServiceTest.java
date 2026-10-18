package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.Telemetry;
import com.iot.greenhouse.repository.DeviceRepository;
import com.iot.greenhouse.repository.TelemetryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TelemetryServiceTest {

    @Mock
    private TelemetryRepository telemetryRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private AlertEvaluationService alertEvaluationService;

    @InjectMocks
    private TelemetryService telemetryService;

    @Test
    void ignoresDuplicateSequenceWithoutSavingOrEvaluatingAlerts() {
        Device device = Device.builder()
                .id(12L)
                .deviceId("esp32-n1")
                .name("Node 1")
                .zone("livingroom")
                .status("ACTIVE")
                .build();
        Telemetry telemetry = Telemetry.builder().sequenceNumber(1042L).build();
        when(deviceRepository.findByDeviceId("esp32-n1")).thenReturn(Optional.of(device));
        when(telemetryRepository.existsByDevice_IdAndSequenceNumber(12L, 1042L)).thenReturn(true);

        telemetryService.processTelemetryData("esp32-n1", telemetry);

        verify(telemetryRepository, never()).save(telemetry);
        verify(deviceRepository, never()).save(device);
        verify(alertEvaluationService, never()).evaluate(device, telemetry);
    }

    @Test
    void enrichesTelemetryFromRegisteredDeviceBeforePersisting() {
        Device device = Device.builder()
                .id(13L)
                .deviceId("esp32-n2")
                .name("Node 2")
                .zone("balcony")
                .status("REGISTERED")
                .build();
        Telemetry telemetry = Telemetry.builder().sequenceNumber(1043L).quality("ok").build();
        when(deviceRepository.findByDeviceId("esp32-n2")).thenReturn(Optional.of(device));

        telemetryService.processTelemetryData("esp32-n2", telemetry);

        assertThat(telemetry.getZone()).isEqualTo("balcony");
        assertThat(telemetry.getDevice()).isSameAs(device);
        verify(telemetryRepository).save(telemetry);
        verify(deviceRepository).save(device);
        verify(alertEvaluationService).evaluate(device, telemetry);
    }

    @Test
    void rejectsTelemetryFromLockedDevice() {
        Device device = Device.builder()
                .id(14L)
                .deviceId("revoked-node")
                .name("Revoked node")
                .zone("balcony")
                .status("LOCKED")
                .build();
        when(deviceRepository.findByDeviceId("revoked-node")).thenReturn(Optional.of(device));

        telemetryService.processTelemetryData("revoked-node", Telemetry.builder().build());

        verify(telemetryRepository, never()).save(org.mockito.ArgumentMatchers.any(Telemetry.class));
        verify(deviceRepository, never()).save(device);
        verify(alertEvaluationService, never()).evaluate(
                org.mockito.ArgumentMatchers.any(Device.class),
                org.mockito.ArgumentMatchers.any(Telemetry.class));
    }

    @Test
    void rejectsTelemetryWithZoneDifferentFromRegisteredDevice() {
        Device device = Device.builder()
                .id(15L)
                .deviceId("esp32-n2")
                .name("Node 2")
                .zone("balcony")
                .status("ACTIVE")
                .build();
        Telemetry telemetry = Telemetry.builder().zone("livingroom").sequenceNumber(1044L).build();
        when(deviceRepository.findByDeviceId("esp32-n2")).thenReturn(Optional.of(device));

        telemetryService.processTelemetryData("esp32-n2", telemetry);

        verify(telemetryRepository, never()).save(org.mockito.ArgumentMatchers.any(Telemetry.class));
        verify(deviceRepository, never()).save(device);
        verify(alertEvaluationService, never()).evaluate(device, telemetry);
    }
}
