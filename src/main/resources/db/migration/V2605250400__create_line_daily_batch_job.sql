-- 일별 사용량 동기화 배치의 실행 이력과 진행 카운트를 저장한다.
-- 같은 batch_name + usage_date 중복 실행 방어는 DB 고유 제약이 아니라 애플리케이션 조회 절차가 담당한다.
CREATE TABLE IF NOT EXISTS LINE_DAILY_BATCH_JOB (
    id                         BIGINT       NOT NULL AUTO_INCREMENT,
    batch_name                 VARCHAR(64)  NOT NULL,
    usage_date                 DATE         NOT NULL,
    status                     VARCHAR(16)  NOT NULL,
    status_updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    run_started_at             DATETIME(6)  NULL,
    finished_at                DATETIME(6)  NULL,
    target_count               BIGINT       NOT NULL DEFAULT 0,
    success_count              BIGINT       NOT NULL DEFAULT 0,
    failed_count               BIGINT       NOT NULL DEFAULT 0,
    skipped_count              BIGINT       NOT NULL DEFAULT 0,
    processed_count_updated_at DATETIME(6)  NULL,
    manager_instance_id        VARCHAR(128) NULL,
    last_error_code            VARCHAR(64)  NULL,
    last_error_message         VARCHAR(512) NULL,
    created_at                 DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                 DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_line_daily_batch_job_lookup (batch_name, usage_date, status),
    KEY idx_line_daily_batch_job_usage_date (usage_date)
);
