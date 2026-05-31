-- TRAFFIC_DEDUCT_DONE에서 persisted_at을 제거하고 started_at을 도입합니다.
-- 기존 레코드는 created_at 값을 started_at으로 이관합니다.

ALTER TABLE TRAFFIC_DEDUCT_DONE
    ADD COLUMN started_at DATETIME(6) NULL AFTER created_at;

UPDATE TRAFFIC_DEDUCT_DONE
SET started_at = created_at
WHERE started_at IS NULL;

ALTER TABLE TRAFFIC_DEDUCT_DONE
    MODIFY COLUMN started_at DATETIME(6) NOT NULL,
    DROP COLUMN persisted_at;
