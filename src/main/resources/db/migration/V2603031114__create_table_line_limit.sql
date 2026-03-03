-- LINE_LIMIT(회선별 제한 정책 정보) 테이블 생성

DROP TABLE IF EXISTS `LINE_LIMIT`;
create table LINE_LIMIT(
    `limit_id`                  BIGINT      NOT NULL,
    `line_id`                   BIGINT      NOT NULL,
    `daily_data_limit`          BIGINT      NOT NULL DEFAULT -1     COMMENT 'Unit:Byte, -1: Unlimited',
    `is_daily_limit_active`     BOOLEAN     NOT NULL DEFAULT TRUE,
    `shared_data_limit`         BIGINT      NOT NULL DEFAULT -1     COMMENT 'Unit:Byte, -1: Unlimited',
    `is_shared_limit_active`    BOOLEAN     NOT NULL DEFAULT TRUE,
    `created_at`                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `deleted_at`	            DATETIME(6)	NULL,
    `updated_at`    	        DATETIME(6)	NULL,
    primary key (limit_id),
    foreign key (line_id) references LINE (line_id)
)