-- 1. 외래 키 제약 조건 체크 해제
SET FOREIGN_KEY_CHECKS = 0;

-- 1. alram_history 테이블
ALTER TABLE `alram_history` MODIFY COLUMN `alarm_history_id` bigint NOT NULL AUTO_INCREMENT;

-- 2. answer 테이블
ALTER TABLE `answer` MODIFY COLUMN `answer_id` bigint NOT NULL AUTO_INCREMENT;

-- 3. answer_attachment 테이블
ALTER TABLE `answer_attachment` MODIFY COLUMN `answer_attachment_id` bigint NOT NULL AUTO_INCREMENT;

-- 4. app_policy 테이블
ALTER TABLE `app_policy` MODIFY COLUMN `app_policy_id` bigint NOT NULL AUTO_INCREMENT;

-- 5. application 테이블
ALTER TABLE `application` MODIFY COLUMN `application_id` int NOT NULL AUTO_INCREMENT;

-- 6. daily_limit 테이블
ALTER TABLE `daily_limit` MODIFY COLUMN `daily_limit_id` bigint NOT NULL AUTO_INCREMENT;

-- 7. family 테이블
ALTER TABLE `family` MODIFY COLUMN `family_id` bigint NOT NULL AUTO_INCREMENT;

-- 8. line 테이블
ALTER TABLE `line` MODIFY COLUMN `line_id` bigint NOT NULL AUTO_INCREMENT;

-- 9. permission 테이블
ALTER TABLE `permission` MODIFY COLUMN `permission_id` int NOT NULL AUTO_INCREMENT;

-- 10. plan 테이블
ALTER TABLE `plan` MODIFY COLUMN `plan_id` int NOT NULL AUTO_INCREMENT;

-- 11. policy 테이블
ALTER TABLE `policy` MODIFY COLUMN `policy_id` int NOT NULL AUTO_INCREMENT;

-- 12. policy_category 테이블
ALTER TABLE `policy_category` MODIFY COLUMN `policy_category_id` int NOT NULL AUTO_INCREMENT;

-- 13. question 테이블
ALTER TABLE `question` MODIFY COLUMN `question_id` bigint NOT NULL AUTO_INCREMENT;

-- 14. question_attachment 테이블
ALTER TABLE `question_attachment` MODIFY COLUMN `question_attachment_id` bigint NOT NULL AUTO_INCREMENT;

-- 15. question_category 테이블
ALTER TABLE `question_category` MODIFY COLUMN `question_category_id` int NOT NULL AUTO_INCREMENT;

-- 16. role 테이블
ALTER TABLE `role` MODIFY COLUMN `role_id` int NOT NULL AUTO_INCREMENT;

-- 17. shared_limit 테이블
ALTER TABLE `shared_limit` MODIFY COLUMN `shared_limit_id` bigint NOT NULL AUTO_INCREMENT;

-- 18. users 테이블
ALTER TABLE `users` MODIFY COLUMN `user_id` bigint NOT NULL AUTO_INCREMENT;

-- 19. whitelist 테이블
ALTER TABLE `whitelist` MODIFY COLUMN `whitelist_id` bigint NOT NULL AUTO_INCREMENT;

-- 2. 외래 키 제약 조건 체크 다시 활성화
SET FOREIGN_KEY_CHECKS = 1;
