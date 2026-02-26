-- APP_POLICY 테이블 create

CREATE TABLE `APP_POLICY` (
    `app_policy_id`     BIGINT	    NOT NULL,
    `line_id`	        BIGINT	    NOT NULL,
    `application_id`	INT	        NOT NULL,
    `data_limit`	    BIGINT	    NOT NULL	DEFAULT -1  COMMENT 'Unit:Byte',
    `speed_limit`	    INT	        NOT NULL	DEFAULT -1  COMMENT 'Unit:Kbps',
    `is_active`	        BOOLEAN	    NOT NULL    DEFAULT TRUE,
    `created_at`	    DATETIME(6)	NOT NULL,
    `deleted_at`	    DATETIME(6)	NULL,
    `updated_at`    	DATETIME(6)	NULL,
    PRIMARY KEY (app_policy_id),
    FOREIGN KEY (line_id) REFERENCES LINE (line_id),
    FOREIGN KEY (application_id) REFERENCES APPLICATION (application_id)
);