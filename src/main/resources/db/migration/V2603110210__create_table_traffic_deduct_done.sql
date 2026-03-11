-- 트래픽 차감 처리 완료(DONE) 이력 저장 테이블
-- trace_id UNIQUE로 idempotency를 보장한다.

CREATE TABLE IF NOT EXISTS TRAFFIC_DEDUCT_DONE (
    traffic_deduct_done_id BIGINT NOT NULL AUTO_INCREMENT,
    trace_id               VARCHAR(64)  NOT NULL,
    line_id                BIGINT       NOT NULL,
    family_id              BIGINT       NOT NULL,
    app_id                 INT          NOT NULL,
    api_total_data         BIGINT       NOT NULL,
    deducted_total_bytes   BIGINT       NOT NULL,
    api_remaining_data     BIGINT       NOT NULL,
    final_status           VARCHAR(32)  NOT NULL,
    last_lua_status        VARCHAR(32)  NULL,
    created_at             DATETIME(6)  NOT NULL,
    finished_at            DATETIME(6)  NOT NULL,
    persisted_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (traffic_deduct_done_id),
    UNIQUE KEY uk_traffic_deduct_done_trace_id (trace_id)
);
