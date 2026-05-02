-- EM1: Redis-Only 전환을 위한 스키마 마이그레이션
-- 합의사항:
-- 1) 기존 TRAFFIC_DEDUCT_DONE 레코드 전량 제거
-- 2) TRAFFIC_DEDUCT_DONE.enqueued_at NOT NULL 추가
-- 3) LINE/FAMILY.last_balance_refreshed_at 초기값 2026-05-01 00:00:00(Asia/Seoul) 부여

-- 기존 done log는 유지하지 않고 전량 제거한다.
DELETE FROM TRAFFIC_DEDUCT_DONE;

-- done log 스키마를 개인/공유 분리 컬럼 기준으로 변경한다.
ALTER TABLE TRAFFIC_DEDUCT_DONE
    ADD COLUMN enqueued_at DATETIME(6) NOT NULL AFTER app_id,
    ADD COLUMN deducted_individual_bytes BIGINT NOT NULL AFTER api_total_data,
    ADD COLUMN deducted_shared_bytes BIGINT NOT NULL AFTER deducted_individual_bytes,
    DROP COLUMN deducted_total_bytes;

CREATE INDEX idx_traffic_deduct_done_enqueued_at
    ON TRAFFIC_DEDUCT_DONE (enqueued_at);

-- LINE 잔량 컬럼명을 total_data로 전환하고 월 갱신 시각 컬럼을 추가한다.
ALTER TABLE LINE
    CHANGE COLUMN remaining_data total_data BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_balance_refreshed_at DATETIME(6) NULL AFTER total_data;

UPDATE LINE
SET last_balance_refreshed_at = '2026-05-01 00:00:00'
WHERE last_balance_refreshed_at IS NULL;

ALTER TABLE LINE
    MODIFY COLUMN last_balance_refreshed_at DATETIME(6) NOT NULL;

-- FAMILY 공유풀 잔량 컬럼을 제거하고 월 갱신 시각 컬럼을 추가한다.
ALTER TABLE FAMILY
    DROP COLUMN pool_remaining_data,
    ADD COLUMN last_balance_refreshed_at DATETIME(6) NULL AFTER pool_total_data;

UPDATE FAMILY
SET last_balance_refreshed_at = '2026-05-01 00:00:00'
WHERE last_balance_refreshed_at IS NULL;

ALTER TABLE FAMILY
    MODIFY COLUMN last_balance_refreshed_at DATETIME(6) NOT NULL;
