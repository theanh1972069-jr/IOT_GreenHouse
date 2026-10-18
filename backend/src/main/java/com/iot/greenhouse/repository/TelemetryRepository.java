package com.iot.greenhouse.repository;

import com.iot.greenhouse.entity.Telemetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;

@Repository
public interface TelemetryRepository extends JpaRepository<Telemetry, Long> {
    List<Telemetry> findByDeviceIdOrderByTimestampDesc(Long deviceId);

    boolean existsByDevice_IdAndSequenceNumber(Long deviceId, Long sequenceNumber);

    /**
     * Deletes a bounded batch of rows older than the cutoff.
     *
     * <p>Native rather than JPQL: Spring Data forbids {@code int} returns on derived
     * queries that take a {@code Pageable}. PostgreSQL has no {@code DELETE ... LIMIT},
     * so the batch is selected in a subquery, which also keeps the sweep in small
     * chunks and off a long lock against the ingest path.
     */
    @Modifying
    @Query(value = """
            DELETE FROM telemetry
            WHERE id IN (
                SELECT id FROM telemetry
                WHERE timestamp < :cutoff
                ORDER BY timestamp
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteBatchOlderThan(@Param("cutoff") ZonedDateTime cutoff, @Param("batchSize") int batchSize);
}
