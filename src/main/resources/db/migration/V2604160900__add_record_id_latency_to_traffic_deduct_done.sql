-- TRAFFIC_DEDUCT_DONE에 Mongo done log 호환 필드(record_id, latency)를 추가합니다.

ALTER TABLE TRAFFIC_DEDUCT_DONE
    ADD COLUMN record_id VARCHAR(128) NULL AFTER trace_id,
    ADD COLUMN latency BIGINT NULL AFTER finished_at;
