package com.iot.greenhouse.repository;

import com.iot.greenhouse.entity.Command;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommandRepository extends JpaRepository<Command, Long>, JpaSpecificationExecutor<Command> {

    List<Command> findByDeviceIdOrderByCreatedAtDesc(Long deviceId);

    /** Fetch graph keeps this to two queries instead of one per row. */
    @Override
    @EntityGraph(attributePaths = "device")
    Page<Command> findAll(Specification<Command> spec, Pageable pageable);
}
