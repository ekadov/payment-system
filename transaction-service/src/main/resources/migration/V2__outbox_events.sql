SET search_path TO transaction;

CREATE TABLE outbox_events
(
    uid             UUID PRIMARY KEY       DEFAULT gen_random_uuid(),
    created_at      TIMESTAMP     NOT NULL DEFAULT now(),
    user_uid        UUID          NOT NULL,
    topic           VARCHAR(128)  NOT NULL,
    message_key     VARCHAR(128)  NOT NULL,
    payload         TEXT          NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    attempts        INT           NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP     NOT NULL DEFAULT now(),
    last_error      VARCHAR(1024),
    sent_at         TIMESTAMP,
    trace_id        VARCHAR(32),
    span_id         VARCHAR(16)
);

CREATE INDEX idx_outbox_events_ready ON outbox_events (next_attempt_at) WHERE status = 'NEW';
