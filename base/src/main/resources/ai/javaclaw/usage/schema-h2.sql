CREATE TABLE IF NOT EXISTS token_usage (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    recorded_at         TIMESTAMP    NOT NULL,
    session_id          VARCHAR(64),
    conversation        VARCHAR(255),
    channel             VARCHAR(32),
    kind                VARCHAR(16),
    model               VARCHAR(128),
    input_tokens        INT NOT NULL,
    output_tokens       INT NOT NULL,
    cache_read_tokens   INT NOT NULL DEFAULT 0,
    cache_write_tokens  INT NOT NULL DEFAULT 0,
    total_tokens        INT NOT NULL
);
CREATE INDEX IF NOT EXISTS token_usage_recorded_at ON token_usage (recorded_at);
