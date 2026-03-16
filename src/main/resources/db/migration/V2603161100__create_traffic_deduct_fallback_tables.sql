CREATE TABLE IF NOT EXISTS TRAFFIC_DB_SPEED_BUCKET (
    pool_type        VARCHAR(16)  NOT NULL,
    owner_id         BIGINT       NOT NULL,
    app_id           INT          NOT NULL,
    bucket_epoch_sec BIGINT       NOT NULL,
    used_bytes       BIGINT       NOT NULL DEFAULT 0,
    created_at       DATETIME(6)  NOT NULL DEFAULT NOW(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT NOW(6),
    PRIMARY KEY (pool_type, owner_id, app_id, bucket_epoch_sec)
);

CREATE TABLE IF NOT EXISTS TRAFFIC_REDIS_USAGE_DELTA (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    trace_id          VARCHAR(128) NOT NULL,
    pool_type         VARCHAR(16)  NOT NULL,
    line_id           BIGINT       NOT NULL,
    family_id         BIGINT       NULL,
    app_id            INT          NOT NULL,
    used_bytes        BIGINT       NOT NULL,
    usage_date        DATE         NOT NULL,
    target_month      VARCHAR(7)   NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    retry_count       INT          NOT NULL DEFAULT 0,
    last_error_message VARCHAR(255) NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT NOW(6),
    status_updated_at DATETIME(6)  NOT NULL DEFAULT NOW(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_traffic_redis_usage_delta_trace_pool (trace_id, pool_type),
    KEY idx_traffic_redis_usage_delta_status (status, id),
    KEY idx_traffic_redis_usage_delta_processing (status, status_updated_at)
);
