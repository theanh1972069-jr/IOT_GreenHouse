package com.iot.greenhouse.repository;

import com.iot.greenhouse.entity.FirmwareVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FirmwareVersionRepository extends JpaRepository<FirmwareVersion, Long> {
    Optional<FirmwareVersion> findByVersion(String version);
}
