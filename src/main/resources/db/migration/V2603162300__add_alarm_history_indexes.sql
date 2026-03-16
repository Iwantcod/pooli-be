ALTER TABLE ALARM_HISTORY
    ADD INDEX idx_alarm_history_line_deleted_read (line_id, deleted_at, read_at),
    ADD INDEX idx_alarm_history_line_deleted_created (line_id, deleted_at, created_at);
