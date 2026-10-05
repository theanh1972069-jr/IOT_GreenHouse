package com.iot.greenhouse.service;

import com.iot.greenhouse.repository.TelemetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

/**
 * Drops telemetry rows past the retention window.
 *
 * <p>A node publishing every 15s adds ~5,760 rows a day and ~2.1 million a year, so
 * the table grows without bound. That matters here because
 * {@code GET /api/telemetry} returns the device's whole history in one response.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryRetentionService {

    private final TelemetryRepository telemetryRepository;

    /** Days of history to keep. Set to 0 to disable pruning. */
    @Value("${app.telemetry.retention-days:30}")
    private int retentionDays;

    /** How often to sweep, in milliseconds. */
    @Value("${app.telemetry.cleanup-interval-ms:21600000}")
    private long cleanupIntervalMs;

    /**
     * Deletes in batches so a sweep never holds a long-running lock on a table the
     * ingest path writes to. Returns the number of rows removed.
     */
    @Scheduled(fixedDelayString = "${app.telemetry.cleanup-interval-ms:21600000}",
               initialDelayString = "${app.telemetry.cleanup-initial-delay-ms:60000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeExpired() {
        if (retentionDays <= 0) {
            log.debug("Telemetry retention disabled (retention-days={})", retentionDays);
            return 0;
        }

        ZonedDateTime cutoff = ZonedDateTime.now().minusDays(retentionDays);
        int total = 0;
        while (true) {
            int removed = telemetryRepository.deleteBatchOlderThan(cutoff, BATCH_SIZE);
            if (removed == 0 || removed < BATCH_SIZE) {
                total += removed;
                break;
            }
            total += removed;
        }

        if (total > 0) {
            log.info("Purged {} telemetry row(s) older than {} day(s)", total, retentionDays);
        }
        return total;
    }

    private static final int BATCH_SIZE = 5000;

    /** Exposed so the settings screen can report the configured window. */
    public int retentionDays() {
        return retentionDays;
    }
}
