CREATE TABLE devices (
                         id          BIGSERIAL PRIMARY KEY,
                         name        VARCHAR(255) NOT NULL,
                         ip_address  VARCHAR(255) NOT NULL UNIQUE,
                         status      VARCHAR(50)  NOT NULL DEFAULT 'UNKNOWN',
                         last_seen_at TIMESTAMP
);

CREATE INDEX idx_devices_status ON devices(status);