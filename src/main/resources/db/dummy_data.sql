-- ============================================================
-- Dummy Data: POLICY_CATEGORY, POLICY, REPEAT_BLOCK, REPEAT_BLOCK_DAY
-- ============================================================

SET FOREIGN_KEY_CHECKS = 0;

-- -------------------------------------------------------
-- 1. POLICY_CATEGORY 더미 데이터 (5건)
-- -------------------------------------------------------
INSERT INTO `POLICY_CATEGORY` (`policy_category_name`, `created_at`, `deleted_at`, `updated_at`) VALUES
('데이터 제한',      NOW(), NULL, NULL),
('시간 차단',        NOW(), NULL, NULL),
('앱 사용 제어',     NOW(), NULL, NULL),
('속도 제한',        NOW(), NULL, NULL),
('가족 공유 설정',   NOW(), NULL, NULL);

-- -------------------------------------------------------
-- 2. POLICY 더미 데이터 (5건)
--    policy_category_id 1~5 참조
-- -------------------------------------------------------
INSERT INTO `POLICY` (`policy_category_id`, `policy_name`, `is_active`, `is_new`, `created_at`, `deleted_at`, `updated_at`) VALUES
(1, '일일 데이터 1GB 제한',       TRUE,  FALSE, NOW(), NULL, NULL),
(2, '심야 시간 인터넷 차단',       TRUE,  TRUE,  NOW(), NULL, NULL),
(3, 'SNS 앱 사용 시간 제한',       FALSE, TRUE,  NOW(), NULL, NULL),
(4, '동영상 스트리밍 속도 제한',   TRUE,  FALSE, NOW(), NULL, NULL),
(5, '가족 공유 데이터 균등 분배',  FALSE, TRUE,  NOW(), NULL, NULL);

-- -------------------------------------------------------
-- 3. REPEAT_BLOCK 더미 데이터 (5건)
--    line_id는 LINE 테이블에 존재하는 값으로 가정 (1~5)
-- -------------------------------------------------------
INSERT INTO `REPEAT_BLOCK` (`line_id`, `is_active`, `created_at`, `deleted_at`, `updated_at`) VALUES
(1, TRUE,  NOW(), NULL, NULL),
(2, TRUE,  NOW(), NULL, NULL),
(3, FALSE, NOW(), NULL, NULL),
(4, TRUE,  NOW(), NULL, NULL),
(5, FALSE, NOW(), NULL, NULL);

-- -------------------------------------------------------
-- 4. REPEAT_BLOCK_DAY 더미 데이터 (5건)
--    repeat_block_id 1~5 참조
-- -------------------------------------------------------
INSERT INTO `REPEAT_BLOCK_DAY` (`repeat_block_id`, `day_of_week`, `start_at`, `end_at`, `created_at`, `deleted_at`, `updated_at`) VALUES
(1, 'MON', '22:00:00', '07:00:00', NOW(), NULL, NULL),
(2, 'TUE', '23:00:00', '06:00:00', NOW(), NULL, NULL),
(3, 'WED', '21:00:00', '08:00:00', NOW(), NULL, NULL),
(4, 'SAT', '00:00:00', '09:00:00', NOW(), NULL, NULL),
(5, 'SUN', '20:00:00', '07:30:00', NOW(), NULL, NULL);

SET FOREIGN_KEY_CHECKS = 1;
