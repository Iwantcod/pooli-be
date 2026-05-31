-- TRAFFIC_DEDUCT_DONE 복구 메타데이터 컬럼을 추가하고
-- trace_id NOT NULL 및 복구 조회 인덱스를 정합화합니다.

ALTER TABLE TRAFFIC_DEDUCT_DONE
    MODIFY COLUMN trace_id VARCHAR(64) NOT NULL,
    ADD COLUMN restore_status VARCHAR(32) NULL AFTER persisted_at,
    ADD COLUMN restore_status_updated_at DATETIME(6) NULL AFTER restore_status,
    ADD COLUMN restore_retry_count INT NULL AFTER restore_status_updated_at,
    ADD COLUMN restore_last_error_message VARCHAR(1000) NULL AFTER restore_retry_count;

-- 기존 행이 있더라도 restore_* 컬럼이 즉시 운영 가능한 기본값을 가지도록 보정합니다.
UPDATE TRAFFIC_DEDUCT_DONE
SET restore_status = COALESCE(restore_status, 'NONE'),
    restore_status_updated_at = COALESCE(restore_status_updated_at, created_at),
    restore_retry_count = COALESCE(restore_retry_count, 0)
WHERE restore_status IS NULL
   OR restore_status_updated_at IS NULL
   OR restore_retry_count IS NULL;

ALTER TABLE TRAFFIC_DEDUCT_DONE
    MODIFY COLUMN restore_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    MODIFY COLUMN restore_status_updated_at DATETIME(6) NOT NULL,
    MODIFY COLUMN restore_retry_count INT NOT NULL DEFAULT 0,
    MODIFY COLUMN restore_last_error_message VARCHAR(1000) NULL DEFAULT NULL;

CREATE INDEX idx_traffic_deduct_done_created_at
    ON TRAFFIC_DEDUCT_DONE (created_at);
