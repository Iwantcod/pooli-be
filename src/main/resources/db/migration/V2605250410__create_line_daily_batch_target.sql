-- 일별 사용량 동기화 worker가 line 단위 처리 대상을 선점하고 terminal 상태를 기록하는 테이블이다.
-- target set은 batch metadata id 없이 usage_date 기준으로 하나만 유지한다.
CREATE TABLE IF NOT EXISTS LINE_DAILY_BATCH_TARGET (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    usage_date         DATE         NOT NULL,
    line_id            BIGINT       NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    status_updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    worker_id          VARCHAR(128) NULL,
    retry_count        INT          NOT NULL DEFAULT 0,
    last_error_code    VARCHAR(64)  NULL,
    last_error_message VARCHAR(512) NULL,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_line_daily_batch_target (usage_date, line_id),
    KEY idx_line_daily_batch_target_claim (usage_date, status, status_updated_at)
);
