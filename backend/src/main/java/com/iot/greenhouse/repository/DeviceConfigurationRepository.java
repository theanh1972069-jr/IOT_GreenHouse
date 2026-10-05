package com.iot.greenhouse.repository;

import com.iot.greenhouse.entity.DeviceConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DeviceConfigurationRepository extends JpaRepository<DeviceConfiguration, Long> {
    Optional<DeviceConfiguration> findByDeviceId(Long deviceId);
}
