package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;

    @Transactional(readOnly = true)
    public List<Device> getAllDevices() {
        return deviceRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Device> getDeviceByDeviceId(String deviceId) {
        return deviceRepository.findByDeviceId(deviceId);
    }

    @Transactional
    public Device registerDevice(Device device) {
        return deviceRepository.save(device);
    }

    @Transactional
    public Optional<Device> updateDevice(String deviceId, String name, String zone, String firmwareVersion, String status) {
        return deviceRepository.findByDeviceId(deviceId)
                .map(device -> {
                    if (name != null) device.setName(name);
                    if (zone != null) device.setZone(zone);
                    if (firmwareVersion != null) device.setFirmwareVersion(firmwareVersion);
                    if (status != null) device.setStatus(status);
                    return deviceRepository.save(device);
                });
    }

    /**
     * Loads and mutates the device inside a single transaction. Doing this in the
     * controller would hand a detached entity to {@code save()} and downgrade the
     * update into a merge with an extra SELECT.
     */
    @Transactional
    public Optional<Device> updateStatus(String deviceId, String status) {
        return deviceRepository.findByDeviceId(deviceId)
                .map(device -> {
                    device.setStatus(status);
                    return deviceRepository.save(device);
                });
    }

    @Transactional
    public boolean deleteDeviceByDeviceId(String deviceId) {
        return deviceRepository.findByDeviceId(deviceId)
                .map(device -> {
                    deviceRepository.delete(device);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public void deleteDevice(Long id) {
        deviceRepository.deleteById(id);
    }
}
