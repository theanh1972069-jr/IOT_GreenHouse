package com.iot.greenhouse.repository;

import com.iot.greenhouse.entity.Alert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

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
     * Redeclared from {@link JpaSpecificationExecutor} only to attach the fetch graph.
     * Without it the controller's view mapping triggers one query per alert for its
     * device.
     */
    @Override
    @EntityGraph(attributePaths = "device")
    Page<Alert> findAll(Specification<Alert> spec, Pageable pageable);
}
