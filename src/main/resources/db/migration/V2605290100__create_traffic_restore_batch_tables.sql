-- Redis 장애 복구 phase 0/1 worker가 병렬로 처리할 target row를 저장한다.
-- 기존 TRAFFIC_DEDUCT_DONE.enqueued_at 단일 인덱스는 다른 조회 경로를 위해 유지한다.

CREATE TABLE IF NOT EXISTS RESTORE_HYDRATE_TARGET (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    batch_name         VARCHAR(64)   NOT NULL,
    target_month_start DATE          NOT NULL,
    target_type        VARCHAR(32)   NOT NULL,
    target_owner_id    BIGINT        NOT NULL,
    status             VARCHAR(16)   NOT NULL,
    status_updated_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    worker_id          VARCHAR(128)  NULL,
    retry_count        INT           NOT NULL DEFAULT 0,
    last_error_code    VARCHAR(64)   NULL,
    last_error_message VARCHAR(1000) NULL,
    created_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_restore_hydrate_target (
        batch_name,
        target_month_start,
        target_type,
        target_owner_id
    ),
    KEY idx_restore_hydrate_target_claim (
        batch_name,
        status,
        status_updated_at
    )
);

CREATE TABLE IF NOT EXISTS RESTORE_DAILY_APP_TARGET (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    batch_name         VARCHAR(64)   NOT NULL,
    usage_date         DATE          NOT NULL,
    line_id            BIGINT        NOT NULL,
    application_id     INT           NOT NULL,
    status             VARCHAR(16)   NOT NULL,
    status_updated_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    worker_id          VARCHAR(128)  NULL,
    retry_count        INT           NOT NULL DEFAULT 0,
    last_error_code    VARCHAR(64)   NULL,
    last_error_message VARCHAR(1000) NULL,
    created_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_restore_daily_app_target (
        batch_name,
        usage_date,
        line_id,
        application_id
    ),
    KEY idx_restore_daily_app_target_claim (
        batch_name,
        status,
        status_updated_at
    )
);

CREATE INDEX idx_traffic_deduct_done_restore_claim
    ON TRAFFIC_DEDUCT_DONE (restore_status, enqueued_at, restore_status_updated_at);
