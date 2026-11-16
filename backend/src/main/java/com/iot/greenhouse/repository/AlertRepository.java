package com.iot.greenhouse.repository;

import com.iot.greenhouse.entity.Alert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface AlertRepository extends JpaRepository<Alert, Long>, JpaSpecificationExecutor<Alert> {

    List<Alert> findByStatusOrderByCreatedAtDesc(String status);

    /**
     * Guards against duplicate alerts: a breach only raises a new alert when the
     * device has no unresolved one of the same type.
     */
    boolean existsByDevice_IdAndTypeInAndStatusIn(Long deviceId, Collection<String> types, Collection<String> statuses);

    /**
     * Chuyển tất cả các cảnh báo đang OPEN của thiết bị sang EXPIRED (ngoại trừ cảnh báo đang được xử lý).
     */
    @Modifying
    @Query("UPDATE Alert a SET a.status = 'EXPIRED', a.processedAt = :now, a.resolvedAt = :now WHERE a.device.id = :deviceId AND a.status = 'OPEN' AND a.id <> :excludeAlertId")
    int expireOtherOpenAlerts(@Param("deviceId") Long deviceId, @Param("excludeAlertId") Long excludeAlertId, @Param("now") ZonedDateTime now);

    /**
     * Chuyển toàn bộ các cảnh báo đang OPEN của thiết bị sang EXPIRED khi có actuator được bật thủ công.
     */
    @Modifying
    @Query("UPDATE Alert a SET a.status = 'EXPIRED', a.processedAt = :now, a.resolvedAt = :now WHERE a.device.id = :deviceId AND a.status = 'OPEN'")
    int expireAllOpenAlerts(@Param("deviceId") Long deviceId, @Param("now") ZonedDateTime now);

    /**
     * Redeclared from {@link JpaSpecificationExecutor} only to attach the fetch graph.
     * Without it the controller's view mapping triggers one query per alert for its
     * device.
     */
    @Override
    @EntityGraph(attributePaths = "device")
    Page<Alert> findAll(Specification<Alert> spec, Pageable pageable);
}
