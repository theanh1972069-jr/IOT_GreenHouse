CREATE TABLE IF NOT EXISTS user_zones (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    zone VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_zones_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE CASCADE,
    CONSTRAINT uq_user_zones_user_zone UNIQUE (user_id, zone)
);

CREATE INDEX IF NOT EXISTS idx_user_zones_zone
    ON user_zones(zone);
