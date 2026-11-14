package com.iot.greenhouse.repository;

import com.iot.greenhouse.entity.LightSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LightScheduleRepository extends JpaRepository<LightSchedule, Long> {

    List<LightSchedule> findByDevice_DeviceIdOrderByTurnOnTimeAsc(String deviceId);

    @Query("SELECT s FROM LightSchedule s JOIN FETCH s.device WHERE s.enabled = true")
    List<LightSchedule> findByEnabledTrue();
}

