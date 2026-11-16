package com.iot.greenhouse.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertSchemaMigration implements ApplicationRunner {

    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Add new columns to alerts table if not exists
            stmt.execute("ALTER TABLE alerts ADD COLUMN IF NOT EXISTS target_actuator VARCHAR(30)");
            stmt.execute("ALTER TABLE alerts ADD COLUMN IF NOT EXISTS target_state VARCHAR(30)");
            stmt.execute("ALTER TABLE alerts ADD COLUMN IF NOT EXISTS processed_at TIMESTAMP WITH TIME ZONE");
            stmt.execute("ALTER TABLE alerts ADD COLUMN IF NOT EXISTS processed_by BIGINT");

            // Migrate legacy statuses: RESOLVED -> PROCESSED, ACKNOWLEDGED -> OPEN
            stmt.executeUpdate("UPDATE alerts SET status = 'PROCESSED' WHERE status = 'RESOLVED'");
            stmt.executeUpdate("UPDATE alerts SET status = 'OPEN' WHERE status = 'ACKNOWLEDGED'");

            // Migrate legacy severities: HIGH / MEDIUM / LOW -> WARNING
            stmt.executeUpdate("UPDATE alerts SET severity = 'WARNING' WHERE severity IN ('HIGH', 'MEDIUM', 'LOW')");

            // Backfill processed_at / processed_by from resolved_at / resolved_by if null
            stmt.executeUpdate("UPDATE alerts SET processed_at = resolved_at WHERE processed_at IS NULL AND resolved_at IS NOT NULL");
            stmt.executeUpdate("UPDATE alerts SET processed_by = resolved_by WHERE processed_by IS NULL AND resolved_by IS NOT NULL");

            // Backfill targetActuator and targetState for existing alerts if null
            stmt.executeUpdate("UPDATE alerts SET target_actuator = 'FAN', target_state = 'ON' WHERE type = 'TEMPERATURE_HIGH' AND target_actuator IS NULL");
            stmt.executeUpdate("UPDATE alerts SET target_actuator = 'VALVE', target_state = 'ON' WHERE type = 'SOIL_MOISTURE_LOW' AND target_actuator IS NULL");
            stmt.executeUpdate("UPDATE alerts SET target_actuator = 'MODE', target_state = 'AUTO' WHERE type = 'MANUAL_MODE' AND target_actuator IS NULL");

            log.info("Alert schema and data migration applied successfully.");
        } catch (Exception e) {
            log.warn("Alert schema migration notice: {}", e.getMessage());
        }
    }
}
