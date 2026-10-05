package com.iot.greenhouse.repository;

import com.iot.greenhouse.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    /** Fetch graph keeps this to two queries instead of one per row. */
    @Override
    @EntityGraph(attributePaths = "user")
    Page<AuditLog> findAll(Specification<AuditLog> spec, Pageable pageable);
}
