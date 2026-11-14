package com.iot.greenhouse.repository;

import com.iot.greenhouse.entity.LightSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LightScheduleRepository extends JpaRepository<LightSchedule, Long> {

    List<LightSchedule> findByDevice_DeviceIdOrderByTurnOnTimeAsc(String deviceId);

    List<LightSchedule> findByEnabledTrue();
}

