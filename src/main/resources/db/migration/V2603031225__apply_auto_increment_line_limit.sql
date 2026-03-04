-- LINE_LIMIT 테이블에 auto increment 적용

-- 1. 외래 키 제약 조건 체크 해제
SET FOREIGN_KEY_CHECKS = 0;

-- 2. 적용
ALTER TABLE `LINE_LIMIT` MODIFY COLUMN `limit_id` bigint NOT NULL AUTO_INCREMENT;