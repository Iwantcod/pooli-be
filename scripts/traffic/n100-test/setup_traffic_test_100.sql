-- 100-line traffic policy test setup (fixed group mapping)
-- Timezone: Asia/Seoul (UTC+09:00)
--
-- Scope:
--   line_id   : 1 ~ 100
--   family_id : 1 ~ 25 (4 lines per family)
--
-- Group mapping (family boundary aligned):
--   G1_NO_RESTRICTION   : line 1~20   (family 1~5)    app_id=1
--   G2_LINE_DAILY_20MB  : line 21~40  (family 6~10)   app_id=1
--   G3_APP2_DAILY_5MB   : line 41~60  (family 11~15)  app_id=2
--   G4_SHARED_ONLY_APP3 : line 61~76  (family 16~19)  app_id=3
--   G5_APP2_SPEED_1MBPS : line 77~88  (family 20~22)  app_id=2
--   G6_APP4_DAILY_8MB   : line 89~100 (family 23~25)  app_id=4
--
-- Unit: 1MB = 1,048,576 bytes

SET SQL_SAFE_UPDATES = 0;
SET time_zone = '+09:00';

SET @LINE_START = 1;
SET @LINE_END = 100;
SET @FAMILY_START = 1;
SET @FAMILY_END = 25;

SET @ONE_MB_BYTES = 1048576;
SET @LINE_FULL_BYTES = 100 * @ONE_MB_BYTES;          -- 100MB
SET @FAMILY_SHARED_BYTES = 50 * @ONE_MB_BYTES;       -- 50MB
SET @DAILY_LIMIT_20MB = 20 * @ONE_MB_BYTES;          -- 20MB
SET @APP2_DAILY_LIMIT_5MB = 5 * @ONE_MB_BYTES;       -- 5MB
SET @APP4_DAILY_LIMIT_8MB = 8 * @ONE_MB_BYTES;       -- 8MB
SET @APP2_SPEED_LIMIT_KBPS = 1000;                   -- 1Mbps

SET @G1_START = 1;
SET @G1_END = 20;
SET @G2_START = 21;
SET @G2_END = 40;
SET @G3_START = 41;
SET @G3_END = 60;
SET @G4_START = 61;
SET @G4_END = 76;
SET @G5_START = 77;
SET @G5_END = 88;
SET @G6_START = 89;
SET @G6_END = 100;

START TRANSACTION;

-- ---------------------------------------------------------------------------
-- 0) Pre-check
-- ---------------------------------------------------------------------------
SELECT 'line_count_mod_4' AS check_name, MOD(@LINE_END - @LINE_START + 1, 4) AS value;

SELECT 'family_count_in_scope' AS check_name, COUNT(*) AS cnt
FROM FAMILY
WHERE family_id BETWEEN @FAMILY_START AND @FAMILY_END
  AND deleted_at IS NULL;

SELECT 'line_count_in_scope' AS check_name, COUNT(*) AS cnt
FROM LINE
WHERE line_id BETWEEN @LINE_START AND @LINE_END
  AND deleted_at IS NULL;

SELECT 'application_count_1_4' AS check_name, COUNT(*) AS cnt, 4 AS expected
FROM APPLICATION
WHERE application_id IN (1, 2, 3, 4);

SELECT 'group_boundaries' AS check_name,
       @G1_START AS g1_start, @G1_END AS g1_end,
       @G2_START AS g2_start, @G2_END AS g2_end,
       @G3_START AS g3_start, @G3_END AS g3_end,
       @G4_START AS g4_start, @G4_END AS g4_end,
       @G5_START AS g5_start, @G5_END AS g5_end,
       @G6_START AS g6_start, @G6_END AS g6_end;

-- ---------------------------------------------------------------------------
-- 1) Cleanup for deterministic run
-- ---------------------------------------------------------------------------
UPDATE LINE
SET block_end_at = NULL,
    updated_at = NOW(6)
WHERE line_id BETWEEN @LINE_START AND @LINE_END;

DELETE rbd
FROM REPEAT_BLOCK_DAY rbd
JOIN REPEAT_BLOCK rb ON rb.repeat_block_id = rbd.repeat_block_id
WHERE rb.line_id BETWEEN @LINE_START AND @LINE_END;

DELETE FROM REPEAT_BLOCK
WHERE line_id BETWEEN @LINE_START AND @LINE_END;

DELETE FROM APP_POLICY
WHERE line_id BETWEEN @LINE_START AND @LINE_END;

DELETE FROM LINE_LIMIT
WHERE line_id BETWEEN @LINE_START AND @LINE_END;

DELETE FROM DAILY_APP_TOTAL_DATA
WHERE line_id BETWEEN @LINE_START AND @LINE_END;

DELETE FROM DAILY_TOTAL_DATA
WHERE line_id BETWEEN @LINE_START AND @LINE_END;

DELETE FROM FAMILY_SHARED_USAGE_DAILY
WHERE family_id BETWEEN @FAMILY_START AND @FAMILY_END
   OR line_id BETWEEN @LINE_START AND @LINE_END;

DELETE FROM TRAFFIC_REDIS_OUTBOX;

DELETE FROM TRAFFIC_REDIS_USAGE_DELTA
WHERE line_id BETWEEN @LINE_START AND @LINE_END;

DELETE FROM TRAFFIC_DB_SPEED_BUCKET
WHERE (pool_type = 'INDIVIDUAL' AND owner_id BETWEEN @LINE_START AND @LINE_END)
   OR (pool_type = 'SHARED' AND owner_id BETWEEN @FAMILY_START AND @FAMILY_END);

DELETE FROM TRAFFIC_DEDUCT_DONE
WHERE line_id BETWEEN @LINE_START AND @LINE_END;

-- ---------------------------------------------------------------------------
-- 2) Base quota reset
-- ---------------------------------------------------------------------------
UPDATE LINE
SET remaining_data = @LINE_FULL_BYTES,
    deleted_at = NULL,
    updated_at = NOW(6)
WHERE line_id BETWEEN @LINE_START AND @LINE_END;

UPDATE FAMILY
SET pool_base_data = @FAMILY_SHARED_BYTES,
    pool_total_data = @FAMILY_SHARED_BYTES,
    pool_remaining_data = @FAMILY_SHARED_BYTES,
    deleted_at = NULL,
    updated_at = NOW(6)
WHERE family_id BETWEEN @FAMILY_START AND @FAMILY_END;

-- ---------------------------------------------------------------------------
-- 3) Enforce family-line mapping (4 lines per family)
-- ---------------------------------------------------------------------------
DELETE FROM FAMILY_LINE
WHERE line_id BETWEEN @LINE_START AND @LINE_END;

INSERT INTO FAMILY_LINE (family_id, line_id, role, is_public, created_at, updated_at)
SELECT
    FLOOR((l.line_id - @LINE_START) / 4) + @FAMILY_START AS family_id,
    l.line_id,
    CASE WHEN MOD(l.line_id - @LINE_START, 4) = 0 THEN 'OWNER' ELSE 'MEMBER' END AS role,
    1,
    NOW(6),
    NOW(6)
FROM LINE l
WHERE l.line_id BETWEEN @LINE_START AND @LINE_END
  AND l.deleted_at IS NULL
ORDER BY l.line_id;

-- ---------------------------------------------------------------------------
-- 4) Group-specific policy setup
-- ---------------------------------------------------------------------------
-- G2: line daily limit 20MB
INSERT INTO LINE_LIMIT (
    line_id,
    daily_data_limit,
    is_daily_limit_active,
    shared_data_limit,
    is_shared_limit_active,
    created_at,
    updated_at
)
SELECT
    l.line_id,
    @DAILY_LIMIT_20MB,
    1,
    -1,
    0,
    NOW(6),
    NOW(6)
FROM LINE l
WHERE l.line_id BETWEEN @G2_START AND @G2_END
  AND l.deleted_at IS NULL
ORDER BY l.line_id;

-- G3: app2 daily limit 5MB
INSERT INTO APP_POLICY (
    line_id,
    application_id,
    data_limit,
    speed_limit,
    is_active,
    is_whitelist,
    created_at,
    updated_at
)
SELECT
    l.line_id,
    2,
    @APP2_DAILY_LIMIT_5MB,
    -1,
    1,
    0,
    NOW(6),
    NOW(6)
FROM LINE l
WHERE l.line_id BETWEEN @G3_START AND @G3_END
  AND l.deleted_at IS NULL
ORDER BY l.line_id;

-- G5: app2 speed limit 1000Kbps
INSERT INTO APP_POLICY (
    line_id,
    application_id,
    data_limit,
    speed_limit,
    is_active,
    is_whitelist,
    created_at,
    updated_at
)
SELECT
    l.line_id,
    2,
    -1,
    @APP2_SPEED_LIMIT_KBPS,
    1,
    0,
    NOW(6),
    NOW(6)
FROM LINE l
WHERE l.line_id BETWEEN @G5_START AND @G5_END
  AND l.deleted_at IS NULL
ORDER BY l.line_id;

-- G6: app4 daily limit 8MB
INSERT INTO APP_POLICY (
    line_id,
    application_id,
    data_limit,
    speed_limit,
    is_active,
    is_whitelist,
    created_at,
    updated_at
)
SELECT
    l.line_id,
    4,
    @APP4_DAILY_LIMIT_8MB,
    -1,
    1,
    0,
    NOW(6),
    NOW(6)
FROM LINE l
WHERE l.line_id BETWEEN @G6_START AND @G6_END
  AND l.deleted_at IS NULL
ORDER BY l.line_id;

-- G4: shared-pool-only
UPDATE LINE
SET remaining_data = 0,
    updated_at = NOW(6)
WHERE line_id BETWEEN @G4_START AND @G4_END;

-- Keep global policies active for traffic path.
UPDATE POLICY
SET is_active = 1,
    deleted_at = NULL,
    updated_at = NOW(6)
WHERE policy_id BETWEEN 1 AND 7;

COMMIT;

-- ---------------------------------------------------------------------------
-- 5) Post-check summary
-- ---------------------------------------------------------------------------
SELECT 'line_scope' AS check_name, COUNT(*) AS cnt
FROM LINE
WHERE line_id BETWEEN @LINE_START AND @LINE_END;

SELECT 'family_scope' AS check_name, COUNT(*) AS cnt
FROM FAMILY
WHERE family_id BETWEEN @FAMILY_START AND @FAMILY_END;

SELECT 'family_line_scope' AS check_name, COUNT(*) AS cnt
FROM FAMILY_LINE
WHERE line_id BETWEEN @LINE_START AND @LINE_END;

SELECT 'line_limit_g2_count' AS check_name, COUNT(*) AS cnt, 20 AS expected
FROM LINE_LIMIT
WHERE line_id BETWEEN @G2_START AND @G2_END;

SELECT 'app_policy_g3_count' AS check_name, COUNT(*) AS cnt, 20 AS expected
FROM APP_POLICY
WHERE line_id BETWEEN @G3_START AND @G3_END
  AND application_id = 2
  AND data_limit = @APP2_DAILY_LIMIT_5MB
  AND speed_limit = -1;

SELECT 'app_policy_g5_count' AS check_name, COUNT(*) AS cnt, 12 AS expected
FROM APP_POLICY
WHERE line_id BETWEEN @G5_START AND @G5_END
  AND application_id = 2
  AND speed_limit = @APP2_SPEED_LIMIT_KBPS;

SELECT 'app_policy_g6_count' AS check_name, COUNT(*) AS cnt, 12 AS expected
FROM APP_POLICY
WHERE line_id BETWEEN @G6_START AND @G6_END
  AND application_id = 4
  AND data_limit = @APP4_DAILY_LIMIT_8MB
  AND speed_limit = -1;

SELECT 'g4_zero_personal_count' AS check_name, COUNT(*) AS cnt, 16 AS expected
FROM LINE
WHERE line_id BETWEEN @G4_START AND @G4_END
  AND remaining_data = 0;

SELECT 'app_policy_distribution_in_scope' AS check_name,
       application_id,
       COUNT(*) AS cnt
FROM APP_POLICY
WHERE line_id BETWEEN @LINE_START AND @LINE_END
GROUP BY application_id
ORDER BY application_id;

SET SQL_SAFE_UPDATES = 1;
