-- 장애 복구 진행 여부를 나타내는 내부 전역 policy flag이다.
-- 일반 정책 목록에서는 policy_id = 8 조건으로 제외한다.

INSERT INTO POLICY (
    policy_id,
    policy_category_id,
    policy_name,
    is_active,
    is_new,
    created_at
)
SELECT
    8,
    pc.policy_category_id,
    'TRAFFIC_RESTORE_IN_PROGRESS',
    FALSE,
    FALSE,
    CURRENT_TIMESTAMP(6)
FROM POLICY_CATEGORY pc
WHERE pc.policy_category_id = 1
  AND NOT EXISTS (
      SELECT 1
      FROM POLICY p
      WHERE p.policy_id = 8
  );

UPDATE POLICY
SET policy_name = 'TRAFFIC_RESTORE_IN_PROGRESS',
    is_active = FALSE,
    is_new = FALSE,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE policy_id = 8;
