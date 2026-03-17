CREATE TABLE IF NOT EXISTS TRAFFIC_SHARED_THRESHOLD_ALARM_LOG (
    family_id     BIGINT      NOT NULL,
    target_month  CHAR(7)     NOT NULL,
    threshold_pct INT         NOT NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT NOW(6),
    PRIMARY KEY (family_id, target_month, threshold_pct)
);
