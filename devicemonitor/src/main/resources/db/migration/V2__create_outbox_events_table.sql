CREATE TABLE outbox_events (
                               id           BIGSERIAL PRIMARY KEY,
                               device_id    BIGINT NOT NULL,
                               event_type   VARCHAR(50) NOT NULL,
                               payload      TEXT NOT NULL,
                               status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                               created_at   TIMESTAMP NOT NULL,
                               processed_at TIMESTAMP
);

CREATE INDEX idx_outbox_status ON outbox_events(status);