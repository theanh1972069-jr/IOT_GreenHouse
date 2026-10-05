-- Migration for databases created before user_sessions existed.
-- Safe to re-run.
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

CREATE INDEX IF NOT EXISTS idx_session_active
    ON user_sessions(token_hash)
    WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_session_expires
    ON user_sessions(expires_at);
