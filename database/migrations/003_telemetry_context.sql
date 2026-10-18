ALTER TABLE telemetry
    ADD COLUMN IF NOT EXISTS sequence_number BIGINT,
    ADD COLUMN IF NOT EXISTS zone VARCHAR(50),
    ADD COLUMN IF NOT EXISTS quality VARCHAR(20),
    ADD COLUMN IF NOT EXISTS firmware_version VARCHAR(50);

ALTER TABLE devices
    DROP CONSTRAINT IF EXISTS chk_device_status;

ALTER TABLE devices
    ADD CONSTRAINT chk_device_status
    CHECK (status IN (
        'REGISTERED', 'PROVISIONED', 'ACTIVE', 'OFFLINE', 'FAULT',
        'MAINTENANCE', 'LOCKED', 'DECOMMISSIONED'
    ));

CREATE UNIQUE INDEX IF NOT EXISTS idx_telemetry_device_sequence
    ON telemetry(device_id, sequence_number)
    WHERE sequence_number IS NOT NULL;
