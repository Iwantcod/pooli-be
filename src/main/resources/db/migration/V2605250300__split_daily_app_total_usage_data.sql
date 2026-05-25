ALTER TABLE `DAILY_APP_TOTAL_DATA`
    ADD COLUMN `individual_usage_data` BIGINT NOT NULL DEFAULT 0 AFTER `application_id`,
    ADD COLUMN `shared_usage_data` BIGINT NOT NULL DEFAULT 0 AFTER `individual_usage_data`,
    ADD COLUMN `qos_usage_data` BIGINT NOT NULL DEFAULT 0 AFTER `shared_usage_data`,
    DROP COLUMN `total_usage_data`;
