package com.iot.greenhouse.repository;

import com.iot.greenhouse.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {
    Optional<Device> findByDeviceId(String deviceId);

    @Query("select distinct d.zone from Device d order by d.zone")
    List<String> findDistinctZones();
}
