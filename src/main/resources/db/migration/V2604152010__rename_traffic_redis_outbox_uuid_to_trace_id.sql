-- TRAFFIC_REDIS_OUTBOX 공통 식별 컬럼을 uuid -> trace_id로 정규화한다.
-- 기존 NULL/blank 데이터는 마이그레이션 시점에 안전한 대체 trace_id로 백필해 NOT NULL 제약을 적용한다.

ALTER TABLE TRAFFIC_REDIS_OUTBOX
    CHANGE COLUMN uuid trace_id VARCHAR(64) NULL;

UPDATE TRAFFIC_REDIS_OUTBOX
SET trace_id = CONCAT('legacy-outbox-', id)
WHERE trace_id IS NULL
   OR TRIM(trace_id) = '';

ALTER TABLE TRAFFIC_REDIS_OUTBOX
    MODIFY COLUMN trace_id VARCHAR(64) NOT NULL;

ALTER TABLE TRAFFIC_REDIS_OUTBOX
    ADD INDEX idx_traffic_redis_outbox_trace_id (trace_id);
