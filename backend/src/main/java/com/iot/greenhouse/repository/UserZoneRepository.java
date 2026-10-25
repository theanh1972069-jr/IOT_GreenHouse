package com.iot.greenhouse.repository;

import com.iot.greenhouse.entity.UserZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface UserZoneRepository extends JpaRepository<UserZone, Long> {

    List<UserZone> findByUser_IdOrderByZoneAsc(Long userId);

    boolean existsByUser_IdAndZoneIgnoreCase(Long userId, String zone);

    @Modifying
    @Transactional
    void deleteByUser_Id(Long userId);
}
