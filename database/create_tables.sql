-- roles
CREATE TABLE IF NOT EXISTS roles (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255)
);

-- User
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,

    username VARCHAR(50) NOT NULL UNIQUE,

    email VARCHAR(150) NOT NULL UNIQUE,

    password_hash VARCHAR(255) NOT NULL,

    full_name VARCHAR(150),

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_user_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'LOCKED'))
);

-- User_roles
CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,

    PRIMARY KEY (user_id, role_id),

    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_id)
        REFERENCES roles(id)
        ON DELETE CASCADE
);

-- user_sessions: bearer tokens live here so sessions survive a restart and can be
-- shared across instances. Only the SHA-256 hash of the token is stored, never the
-- token itself, so a database leak cannot be replayed against the API.
CREATE TABLE IF NOT EXISTS user_sessions (
    id BIGSERIAL PRIMARY KEY,

    user_id BIGINT NOT NULL,

    token_hash CHAR(64) NOT NULL UNIQUE,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,

    revoked_at TIMESTAMP WITH TIME ZONE,

    ip_address VARCHAR(45),

    CONSTRAINT fk_session_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_session_user
    ON user_sessions(user_id);

-- Partial index: resolving a bearer token only ever hits live rows.
CREATE INDEX IF NOT EXISTS idx_session_active
    ON user_sessions(token_hash)
    WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_session_expires
    ON user_sessions(expires_at);

-- Device_status
CREATE TABLE IF NOT EXISTS devices (
    id BIGSERIAL PRIMARY KEY,

    device_id VARCHAR(100) NOT NULL UNIQUE,

    name VARCHAR(150) NOT NULL,

    zone VARCHAR(50) NOT NULL,

    status VARCHAR(30) NOT NULL DEFAULT 'REGISTERED',

    firmware_version VARCHAR(50),

    mac_address VARCHAR(17),

    ip_address VARCHAR(45),

    state JSONB DEFAULT '{}'::jsonb,

    last_seen TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_device_status
        CHECK (
            status IN (
                'REGISTERED',
                'PROVISIONED',
                'ACTIVE',
                'OFFLINE',
                'MAINTENANCE',
                'LOCKED',
                'DECOMMISSIONED'
            )
        )
);

-- device_configurations
CREATE TABLE IF NOT EXISTS device_configurations (
    id BIGSERIAL PRIMARY KEY,

    device_id BIGINT NOT NULL UNIQUE,

    temperature_high NUMERIC(5,2) NOT NULL DEFAULT 35.00,

    soil_moisture_low NUMERIC(5,2) NOT NULL DEFAULT 30.00,

    heartbeat_interval INTEGER NOT NULL DEFAULT 10,

    auto_watering BOOLEAN NOT NULL DEFAULT TRUE,

    updated_by BIGINT,

    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_configuration_device
        FOREIGN KEY (device_id)
        REFERENCES devices(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_configuration_user
        FOREIGN KEY (updated_by)
        REFERENCES users(id)
        ON DELETE SET NULL,

    CONSTRAINT chk_temperature_high
        CHECK (temperature_high > 0),

    CONSTRAINT chk_soil_moisture_low
        CHECK (
            soil_moisture_low >= 0
            AND soil_moisture_low <= 100
        ),

    CONSTRAINT chk_heartbeat_interval
        CHECK (heartbeat_interval > 0)
);

-- telemetry
CREATE TABLE IF NOT EXISTS telemetry (
    id BIGSERIAL PRIMARY KEY,

    device_id BIGINT NOT NULL,

    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,

    temperature NUMERIC(6,2),

    humidity NUMERIC(6,2),

    soil_moisture NUMERIC(6,2),

    light NUMERIC(10,2),

    CONSTRAINT fk_telemetry_device
        FOREIGN KEY (device_id)
        REFERENCES devices(id)
        ON DELETE CASCADE,

    CONSTRAINT chk_humidity
        CHECK (
            humidity IS NULL
            OR (humidity >= 0 AND humidity <= 100)
        ),

    CONSTRAINT chk_soil_moisture
        CHECK (
            soil_moisture IS NULL
            OR (soil_moisture >= 0 AND soil_moisture <= 100)
        ),

    CONSTRAINT chk_light
        CHECK (
            light IS NULL OR light >= 0
        )
);

-- Index cho Telemetry
CREATE INDEX IF NOT EXISTS idx_telemetry_device_timestamp
ON telemetry(device_id, timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_telemetry_timestamp
ON telemetry(timestamp DESC);

-- commands
CREATE TABLE IF NOT EXISTS commands (
    id BIGSERIAL PRIMARY KEY,

    command_id VARCHAR(100) NOT NULL UNIQUE,

    device_id BIGINT NOT NULL,

    user_id BIGINT,

    command_type VARCHAR(50) NOT NULL,

    payload JSONB,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    sent_at TIMESTAMP WITH TIME ZONE,

    executed_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_command_device
        FOREIGN KEY (device_id)
        REFERENCES devices(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_command_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE SET NULL,

    CONSTRAINT chk_command_status
        CHECK (
            status IN (
                'PENDING',
                'SENT',
                'SUCCESS',
                'FAILED',
                'TIMEOUT',
                'REJECTED'
            )
        )
);

-- alerts
CREATE TABLE IF NOT EXISTS alerts (
    id BIGSERIAL PRIMARY KEY,

    device_id BIGINT NOT NULL,

    type VARCHAR(50) NOT NULL,

    severity VARCHAR(20) NOT NULL,

    message VARCHAR(500) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    acknowledged_at TIMESTAMP WITH TIME ZONE,

    acknowledged_by BIGINT,

    resolved_at TIMESTAMP WITH TIME ZONE,

    resolved_by BIGINT,

    CONSTRAINT fk_alert_device
        FOREIGN KEY (device_id)
        REFERENCES devices(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_alert_ack_user
        FOREIGN KEY (acknowledged_by)
        REFERENCES users(id)
        ON DELETE SET NULL,

    CONSTRAINT fk_alert_resolve_user
        FOREIGN KEY (resolved_by)
        REFERENCES users(id)
        ON DELETE SET NULL,

    CONSTRAINT chk_alert_severity
        CHECK (
            severity IN (
                'LOW',
                'MEDIUM',
                'HIGH',
                'CRITICAL'
            )
        ),

    CONSTRAINT chk_alert_status
        CHECK (
            status IN (
                'OPEN',
                'ACKNOWLEDGED',
                'RESOLVED'
            )
        )
);

--Alert Index
CREATE INDEX IF NOT EXISTS idx_alert_device_created
ON alerts(device_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_alert_status
ON alerts(status, created_at DESC);

--audit_logs
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGSERIAL PRIMARY KEY,

    user_id BIGINT,

    action VARCHAR(100) NOT NULL,

    resource_type VARCHAR(50),

    resource_id VARCHAR(100),

    ip_address VARCHAR(45),

    details JSONB,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_audit_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE SET NULL
);
--Index:
CREATE INDEX IF NOT EXISTS idx_audit_created
ON audit_logs(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_user
ON audit_logs(user_id);

--firmware_versions
CREATE TABLE IF NOT EXISTS firmware_versions (
    id BIGSERIAL PRIMARY KEY,

    version VARCHAR(50) NOT NULL UNIQUE,

    file_url TEXT NOT NULL,

    checksum VARCHAR(128) NOT NULL,

    release_notes TEXT,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
