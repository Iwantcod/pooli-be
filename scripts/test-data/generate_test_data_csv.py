#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import random
from datetime import date, datetime, timedelta
from pathlib import Path

ADMIN_COUNT = 5
DEFAULT_SEED = 20260306
DEFAULT_NOW = datetime(2026, 3, 6, 9, 0, 0, 0)
DEFAULT_START_USAGE_DATE = date(2026, 2, 28)
DEFAULT_USAGE_DAYS = 7
USER_CREATED_AT_START = datetime(2010, 1, 1, 0, 0, 0, 0)
USER_CREATED_AT_END = datetime(2025, 12, 31, 23, 59, 59, 999999)
ONE_GB_BYTE = 1_000_000_000
PASSWORD_HASH = "$2b$12$tz4Zk2GGvyHGk1JKMdeYtOfZ/orlRdOD1DtWmlfabvR/F2rAq9hiG"


def ts(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%d %H:%M:%S.%f")


def null() -> str:
    return "NULL"


def random_dt_between(rng: random.Random, start: datetime, end: datetime) -> datetime:
    if start >= end:
        return start
    delta_us = int((end - start).total_seconds() * 1_000_000)
    return start + timedelta(microseconds=rng.randint(0, delta_us))


def random_dt_after(rng: random.Random, base: datetime, end: datetime) -> datetime:
    lower = base + timedelta(seconds=1)
    if lower >= end:
        return end
    return random_dt_between(rng, lower, end)


def applications() -> list[tuple[str, str]]:
    return [
        ("SNS", "WhatsApp"),
        ("SNS", "WeChat"),
        ("SNS", "Telegram"),
        ("SNS", "Snapchat"),
        ("SNS", "LINE"),
        ("SNS", "KakaoTalk"),
        ("SNS", "Facebook"),
        ("SNS", "Instagram"),
        ("ENTERTAINMENT", "Netflix"),
        ("ENTERTAINMENT", "YouTube"),
        ("ENTERTAINMENT", "Disney+"),
        ("ENTERTAINMENT", "Spotify"),
        ("ENTERTAINMENT", "TikTok"),
        ("ENTERTAINMENT", "Twitch"),
        ("ENTERTAINMENT", "Prime Video"),
        ("ENTERTAINMENT", "Hulu"),
        ("ENTERTAINMENT", "Apple TV"),
        ("ENTERTAINMENT", "Crunchyroll"),
        ("WEB", "Chrome"),
        ("WEB", "Firefox"),
        ("WEB", "Edge"),
        ("WEB", "Opera"),
        ("WEB", "Safari"),
        ("WEB", "Brave"),
        ("EDUCATION", "Duolingo"),
        ("EDUCATION", "Brilliant"),
        ("EDUCATION", "Coursera"),
        ("EDUCATION", "Udemy"),
        ("EDUCATION", "Quizlet"),
        ("EDUCATION", "Codecademy"),
        ("EDUCATION", "Google Classroom"),
        ("SHOPPING", "Coupang"),
        ("SHOPPING", "AliExpress"),
        ("SHOPPING", "Temu"),
        ("SHOPPING", "Gmarket"),
        ("SHOPPING", "11st"),
        ("SHOPPING", "Naver Store"),
        ("SHOPPING", "Olive Young"),
        ("SHOPPING", "Amazon"),
        ("SHOPPING", "eBay"),
        ("OTHER", "Gmail"),
        ("OTHER", "Google Maps"),
        ("OTHER", "Google Drive"),
        ("OTHER", "Notion"),
        ("OTHER", "Slack"),
        ("OTHER", "Zoom"),
        ("OTHER", "Teams"),
    ]


def plans() -> list[tuple[str, str, int, int, str, int, int]]:
    return [
        ("CHILD", "LTE 키즈 22", 700000000, 0, "LTE", 400, 0),
        ("CHILD", "5G 키즈 29", 3300000000, 0, "5G", 400, 0),
        ("CHILD", "5G 키즈 39", 5500000000, 0, "5G", 1000, 0),
        ("CHILD", "5G 키즈 45", 9000000000, 0, "5G", 1000, 0),
        ("TEEN", "LTE 청소년 19", 350000000, 0, "LTE", 0, 0),
        ("TEEN", "추가 요금 걱정없는 데이터 청소년 33", 2000000000, 0, "LTE", 400, 0),
        ("TEEN", "추가 요금 걱정없는 데이터 청소년 59", 9000000000, 0, "LTE", 1000, 0),
        ("TEEN", "5G 라이트 청소년", 8000000000, 0, "5G", 1000, 0),
        ("YOUTH", "5G 슬림+", 9000000000, 0, "5G", 400, 0),
        ("YOUTH", "5G 라이트+", 14000000000, 0, "5G", 1000, 0),
        ("YOUTH", "5G 베이직+", 24000000000, 0, "5G", 1000, 0),
        ("YOUTH", "5G 심플+", 31000000000, 0, "5G", 1000, 0),
        ("YOUTH", "5G 데이터 레귤러", 50000000000, 7500000000, "5G", 1000, 0),
        ("YOUTH", "5G 데이터 플러스", 80000000000, 16000000000, "5G", 1000, 0),
        ("YOUTH", "5G 데이터 슈퍼", 95000000000, 21850000000, "5G", 3000, 0),
        ("YOUTH", "5G 스탠다드 에센셜", 125000000000, 33750000000, "5G", 5000, 0),
        ("YOUTH", "5G 스탠다드", 150000000000, 45000000000, "5G", 5000, 0),
        ("MIDDLE", "5G 프리미어 에센셜", -1, 75000000000, "5G", 0, 1),
        ("MIDDLE", "5G 프리미어 레귤러", -1, 100000000000, "5G", 0, 1),
        ("MIDDLE", "5G 프리미어 플러스", -1, 125000000000, "5G", 0, 1),
        ("MIDDLE", "5G 프리미어 슈퍼", -1, 150000000000, "5G", 0, 1),
        ("MIDDLE", "5G 시그니처", -1, 150000000000, "5G", 0, 1),
        ("SENIOR", "LTE 시니어 16.5", 300000000, 0, "LTE", 0, 0),
        ("SENIOR", "LTE 데이터 시니어 33", 1700000000, 0, "LTE", 0, 0),
        ("SENIOR", "5G 시니어 A형", 10000000000, 0, "5G", 1000, 0),
        ("SENIOR", "5G 시니어 B형", 10000000000, 0, "5G", 1000, 0),
        ("SENIOR", "5G 시니어 C형", 10000000000, 0, "5G", 1000, 0),
    ]


class CsvBundle:
    def __init__(self, out_dir: Path):
        self.out_dir = out_dir
        self.files: dict[str, object] = {}
        self.writers: dict[str, csv.writer] = {}

    def open(self, filename: str, headers: list[str]) -> None:
        f = (self.out_dir / filename).open("w", encoding="utf-8", newline="")
        w = csv.writer(f, lineterminator="\n")
        w.writerow(headers)
        self.files[filename] = f
        self.writers[filename] = w

    def write(self, filename: str, row: list[object]) -> None:
        self.writers[filename].writerow(row)

    def close(self) -> None:
        for f in self.files.values():
            f.close()


def mixed_alarm_flags(rng: random.Random) -> list[int]:
    # mixed means at least one ON and one OFF, and not all-on
    while True:
        vals = [rng.randint(0, 1) for _ in range(6)]
        if any(v == 1 for v in vals) and any(v == 0 for v in vals):
            return vals


def write_load_sql(out_dir: Path) -> None:
    load_sql = """-- Run from this profile directory:
--   mysql --local-infile=1 -u <user> -p <db_name> < load_data.sql

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
SET UNIQUE_CHECKS = 0;
SET AUTOCOMMIT = 0;

START TRANSACTION;

TRUNCATE TABLE DAILY_APP_TOTAL_DATA;
TRUNCATE TABLE FAMILY_SHARED_USAGE_DAILY;
TRUNCATE TABLE DAILY_TOTAL_DATA;
TRUNCATE TABLE APP_POLICY;
TRUNCATE TABLE LINE_LIMIT;
TRUNCATE TABLE PERMISSION_LINE;
TRUNCATE TABLE FAMILY_LINE;
TRUNCATE TABLE LINE;
TRUNCATE TABLE ALARM_SETTING;
TRUNCATE TABLE USER_ROLE;
TRUNCATE TABLE FAMILY;
TRUNCATE TABLE USERS;
TRUNCATE TABLE PLAN;
TRUNCATE TABLE APPLICATION;
TRUNCATE TABLE QUESTION_CATEGORY;
TRUNCATE TABLE POLICY;
TRUNCATE TABLE POLICY_CATEGORY;
TRUNCATE TABLE PERMISSION;
TRUNCATE TABLE ROLE;

LOAD DATA LOCAL INFILE '01_role.csv'
INTO TABLE ROLE
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(role_id, @role_name)
SET role_name = REPLACE(@role_name, CHAR(13), '');

LOAD DATA LOCAL INFILE '02_permission.csv'
INTO TABLE PERMISSION
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(permission_id, permission_title, created_at, @deleted_at, @updated_at)
SET deleted_at = NULL, updated_at = NULL;

LOAD DATA LOCAL INFILE '03_policy_category.csv'
INTO TABLE POLICY_CATEGORY
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(policy_category_id, policy_category_name, created_at, @deleted_at, @updated_at)
SET deleted_at = NULL, updated_at = NULL;

LOAD DATA LOCAL INFILE '04_policy.csv'
INTO TABLE POLICY
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(policy_id, policy_category_id, policy_name, is_active, is_new, created_at, @deleted_at, @updated_at)
SET deleted_at = NULL, updated_at = NULL;

LOAD DATA LOCAL INFILE '05_question_category.csv'
INTO TABLE QUESTION_CATEGORY
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(question_category_id, question_category_name, created_at, @deleted_at)
SET deleted_at = NULL;

LOAD DATA LOCAL INFILE '06_application.csv'
INTO TABLE APPLICATION
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(application_id, application_name, category);

LOAD DATA LOCAL INFILE '07_plan.csv'
INTO TABLE PLAN
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(plan_id, plan_category, plan_name, basic_data_amount, shared_pool_amount, network_type, qos_speed_limit, is_unlimited, created_at, @deleted_at, @updated_at)
SET deleted_at = NULL, updated_at = NULL;

LOAD DATA LOCAL INFILE '10_users.csv'
INTO TABLE USERS
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(user_id, user_name, email, password, age, created_at, @deleted_at, @updated_at)
SET deleted_at = NULL, updated_at = NULL;

LOAD DATA LOCAL INFILE '11_user_role.csv'
INTO TABLE USER_ROLE
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(user_id, role_id, created_at);

LOAD DATA LOCAL INFILE '12_alarm_setting.csv'
INTO TABLE ALARM_SETTING
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(user_id, family_alarm, user_alarm, policy_change_alarm, policy_limit_alarm, permission_alarm, question_alarm, created_at, @deleted_at, @updated_at)
SET deleted_at = NULL, updated_at = NULL;

LOAD DATA LOCAL INFILE '20_family.csv'
INTO TABLE FAMILY
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(family_id, pool_base_data, pool_total_data, pool_remaining_data, created_at, @deleted_at, @updated_at, family_threshold, is_threshold_active)
SET deleted_at = NULL, updated_at = NULL;

LOAD DATA LOCAL INFILE '21_line.csv'
INTO TABLE LINE
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(line_id, user_id, plan_id, phone, @block_end_at, remaining_data, is_main, created_at, @deleted_at, @updated_at, individual_threshold, is_threshold_active)
SET
  block_end_at = NULLIF(NULLIF(@block_end_at, '\\N'), 'NULL'),
  deleted_at = NULL,
  updated_at = NULL;

LOAD DATA LOCAL INFILE '22_family_line.csv'
INTO TABLE FAMILY_LINE
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(family_id, line_id, role, is_public, created_at, @updated_at)
SET updated_at = NULL;

LOAD DATA LOCAL INFILE '23_permission_line.csv'
INTO TABLE PERMISSION_LINE
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(line_id, permission_id, is_enable, created_at, @updated_at)
SET updated_at = NULL;

LOAD DATA LOCAL INFILE '24_line_limit.csv'
INTO TABLE LINE_LIMIT
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(limit_id, line_id, daily_data_limit, is_daily_limit_active, shared_data_limit, is_shared_limit_active, created_at, @deleted_at, @updated_at)
SET deleted_at = NULL, updated_at = NULL;

LOAD DATA LOCAL INFILE '25_app_policy.csv'
INTO TABLE APP_POLICY
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(app_policy_id, line_id, application_id, data_limit, speed_limit, is_active, created_at, @deleted_at, @updated_at, is_whitelist)
SET deleted_at = NULL, updated_at = NULL;

LOAD DATA LOCAL INFILE '30_daily_total_data.csv'
INTO TABLE DAILY_TOTAL_DATA
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(usage_date, line_id, total_usage_data, created_at, @deleted_at, @updated_at)
SET deleted_at = NULL, updated_at = NULL;

LOAD DATA LOCAL INFILE '31_family_shared_usage_daily.csv'
INTO TABLE FAMILY_SHARED_USAGE_DAILY
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(usage_date, family_id, line_id, usage_amount, contribution_amount, created_at, @deleted_at, @updated_at)
SET deleted_at = NULL, updated_at = NULL;

LOAD DATA LOCAL INFILE '32_daily_app_total_data.csv'
INTO TABLE DAILY_APP_TOTAL_DATA
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(usage_date, line_id, application_id, total_usage_data, created_at, @deleted_at, @updated_at)
SET deleted_at = NULL, updated_at = NULL;

COMMIT;

SET AUTOCOMMIT = 1;
SET UNIQUE_CHECKS = 1;
SET FOREIGN_KEY_CHECKS = 1;
"""
    (out_dir / "load_data.sql").write_text(load_sql, encoding="utf-8")


def write_verify_sql(out_dir: Path, n: int, now: datetime) -> None:
    current_month = now.strftime("%Y-%m")
    verify_sql = f"""SET @N := {n};
SET @TOTAL_USERS := @N + 5;
SET @TOTAL_LINES := @N * 14 / 10;
SET @ALL_ON_EXPECTED := FLOOR(@TOTAL_USERS * 0.3);
SET @CURRENT_MONTH := '{current_month}';

SELECT 'users_total' AS check_name, COUNT(*) AS actual, @TOTAL_USERS AS expected FROM USERS;
SELECT 'admins' AS check_name, COUNT(*) AS actual, 5 AS expected FROM USER_ROLE ur JOIN ROLE r ON r.role_id = ur.role_id WHERE r.role_name = 'ROLE_ADMIN';
SELECT 'general_users' AS check_name, COUNT(*) AS actual, @N AS expected FROM USER_ROLE ur JOIN ROLE r ON r.role_id = ur.role_id WHERE r.role_name = 'ROLE_USER';
SELECT 'families' AS check_name, COUNT(*) AS actual, @N / 4 AS expected FROM FAMILY;
SELECT 'lines_total' AS check_name, COUNT(*) AS actual, @TOTAL_LINES AS expected FROM LINE;

SELECT 'admin_with_lines' AS check_name, COUNT(*) AS violations
FROM LINE l JOIN USER_ROLE ur ON ur.user_id = l.user_id JOIN ROLE r ON r.role_id = ur.role_id
WHERE r.role_name = 'ROLE_ADMIN';

WITH line_count_per_user AS (
    SELECT l.user_id, COUNT(*) AS line_cnt
    FROM LINE l
    GROUP BY l.user_id
)
SELECT line_cnt, COUNT(*) AS users
FROM line_count_per_user
GROUP BY line_cnt
ORDER BY line_cnt;

SELECT 'is_main_exactly_one' AS check_name, COUNT(*) AS violations
FROM (
    SELECT user_id
    FROM LINE
    GROUP BY user_id
    HAVING SUM(CASE WHEN is_main = 1 THEN 1 ELSE 0 END) <> 1
) x;

SELECT 'duplicate_phone' AS check_name, COUNT(*) AS violations
FROM (
    SELECT phone
    FROM LINE
    GROUP BY phone
    HAVING COUNT(*) > 1
) x;

SELECT 'owner_per_family' AS check_name, COUNT(*) AS violations
FROM (
    SELECT family_id
    FROM FAMILY_LINE
    GROUP BY family_id
    HAVING SUM(CASE WHEN role = 'OWNER' THEN 1 ELSE 0 END) <> 1
) x;

SELECT 'is_public_without_permission2' AS check_name, COUNT(*) AS violations
FROM FAMILY_LINE fl
LEFT JOIN PERMISSION_LINE pl ON pl.line_id = fl.line_id AND pl.permission_id = 2 AND pl.is_enable = 1
WHERE fl.is_public = 0 AND pl.line_id IS NULL;

SELECT 'family_shared_without_mapping' AS check_name, COUNT(*) AS violations
FROM FAMILY_SHARED_USAGE_DAILY fsu
LEFT JOIN FAMILY_LINE fl ON fl.family_id = fsu.family_id AND fl.line_id = fsu.line_id
WHERE fl.line_id IS NULL;

SELECT 'daily_limit_violation' AS check_name, COUNT(*) AS violations
FROM DAILY_TOTAL_DATA dtd
JOIN LINE_LIMIT ll ON ll.line_id = dtd.line_id
WHERE ll.deleted_at IS NULL
  AND ll.is_daily_limit_active = 1
  AND ll.daily_data_limit >= 0
  AND dtd.total_usage_data > ll.daily_data_limit;

SELECT 'shared_limit_violation' AS check_name, COUNT(*) AS violations
FROM FAMILY_SHARED_USAGE_DAILY fsu
JOIN LINE_LIMIT ll ON ll.line_id = fsu.line_id
WHERE ll.deleted_at IS NULL
  AND ll.is_shared_limit_active = 1
  AND ll.shared_data_limit >= 0
  AND fsu.usage_amount > ll.shared_data_limit;

SELECT 'app_data_limit_violation' AS check_name, COUNT(*) AS violations
FROM DAILY_APP_TOTAL_DATA datd
JOIN APP_POLICY ap ON ap.line_id = datd.line_id AND ap.application_id = datd.application_id
WHERE ap.deleted_at IS NULL
  AND ap.is_active = 1
  AND ap.is_whitelist = 0
  AND ap.data_limit >= 0
  AND datd.total_usage_data > ap.data_limit;

SELECT 'line_without_policy_pair' AS check_name, COUNT(*) AS violations
FROM (
  SELECT l.line_id,
         CASE WHEN ll.line_id IS NULL THEN 0 ELSE 1 END AS has_ll,
         CASE WHEN ap.line_id IS NULL THEN 0 ELSE 1 END AS has_ap
  FROM LINE l
  LEFT JOIN (SELECT DISTINCT line_id FROM LINE_LIMIT) ll ON ll.line_id = l.line_id
  LEFT JOIN (SELECT DISTINCT line_id FROM APP_POLICY) ap ON ap.line_id = l.line_id
) t
WHERE has_ll <> has_ap;

SELECT 'line_policy_pair_ratio' AS check_name,
       SUM(CASE WHEN has_ll = 1 AND has_ap = 1 THEN 1 ELSE 0 END) AS both_present,
       SUM(CASE WHEN has_ll = 0 AND has_ap = 0 THEN 1 ELSE 0 END) AS both_missing,
       @TOTAL_LINES / 2 AS expected_each
FROM (
  SELECT l.line_id,
         CASE WHEN ll.line_id IS NULL THEN 0 ELSE 1 END AS has_ll,
         CASE WHEN ap.line_id IS NULL THEN 0 ELSE 1 END AS has_ap
  FROM LINE l
  LEFT JOIN (SELECT DISTINCT line_id FROM LINE_LIMIT) ll ON ll.line_id = l.line_id
  LEFT JOIN (SELECT DISTINCT line_id FROM APP_POLICY) ap ON ap.line_id = l.line_id
) t;

SELECT 'alarm_all_on_count' AS check_name,
       COUNT(*) AS actual,
       @ALL_ON_EXPECTED AS expected
FROM ALARM_SETTING
WHERE family_alarm=1 AND user_alarm=1 AND policy_change_alarm=1 AND policy_limit_alarm=1 AND permission_alarm=1 AND question_alarm=1;

SELECT 'alarm_non_all_on_has_mixed' AS check_name,
       COUNT(*) AS violations
FROM ALARM_SETTING
WHERE NOT (family_alarm=1 AND user_alarm=1 AND policy_change_alarm=1 AND policy_limit_alarm=1 AND permission_alarm=1 AND question_alarm=1)
  AND (family_alarm + user_alarm + policy_change_alarm + policy_limit_alarm + permission_alarm + question_alarm IN (0,6));

SELECT 'family_pool_base_violation' AS check_name, COUNT(*) AS violations
FROM FAMILY f
LEFT JOIN (
  SELECT fl.family_id,
         SUM(p.shared_pool_amount + {ONE_GB_BYTE}) AS expected_base
  FROM FAMILY_LINE fl
  JOIN LINE l ON l.line_id = fl.line_id
  JOIN PLAN p ON p.plan_id = l.plan_id
  GROUP BY fl.family_id
) x ON x.family_id = f.family_id
WHERE f.pool_base_data <> COALESCE(x.expected_base, 0);

SELECT 'family_pool_total_violation' AS check_name, COUNT(*) AS violations
FROM FAMILY f
LEFT JOIN (
  SELECT family_id,
         SUM(contribution_amount) AS contrib_sum
  FROM FAMILY_SHARED_USAGE_DAILY
  WHERE DATE_FORMAT(usage_date, '%Y-%m') = @CURRENT_MONTH
  GROUP BY family_id
) c ON c.family_id = f.family_id
WHERE f.pool_total_data <> (f.pool_base_data + COALESCE(c.contrib_sum, 0));

SELECT 'family_pool_remaining_violation' AS check_name, COUNT(*) AS violations
FROM FAMILY f
LEFT JOIN (
  SELECT family_id,
         SUM(usage_amount) AS usage_sum
  FROM FAMILY_SHARED_USAGE_DAILY
  WHERE DATE_FORMAT(usage_date, '%Y-%m') = @CURRENT_MONTH
  GROUP BY family_id
) u ON u.family_id = f.family_id
WHERE f.pool_remaining_data <> (f.pool_total_data - COALESCE(u.usage_sum, 0));

SELECT 'family_pool_remaining_negative' AS check_name, COUNT(*) AS violations
FROM FAMILY
WHERE pool_remaining_data < 0;

SELECT 'line_remaining_rule_violation' AS check_name, COUNT(*) AS violations
FROM LINE l
JOIN PLAN p ON p.plan_id = l.plan_id
WHERE (p.is_unlimited = 1 AND l.remaining_data <> -1)
   OR (p.is_unlimited = 0 AND l.remaining_data < 0);

SELECT 'family_threshold_zero_when_inactive' AS check_name, COUNT(*) AS violations
FROM FAMILY
WHERE is_threshold_active = 0
  AND family_threshold <> 0;

SELECT 'line_threshold_zero_when_inactive' AS check_name, COUNT(*) AS violations
FROM LINE
WHERE is_threshold_active = 0
  AND individual_threshold <> 0;

SELECT 'family_threshold_positive_when_active' AS check_name, COUNT(*) AS violations
FROM FAMILY
WHERE is_threshold_active = 1
  AND family_threshold <= 0;

SELECT 'line_threshold_positive_when_active' AS check_name, COUNT(*) AS violations
FROM LINE
WHERE is_threshold_active = 1
  AND individual_threshold <= 0;
"""
    (out_dir / "verify_data.sql").write_text(verify_sql, encoding="utf-8")


def generate(n: int, output_root: Path, seed: int, now: datetime, start_usage_date: date, usage_days: int) -> None:
    if n % 100 != 0:
        raise ValueError("n must be a multiple of 100")

    out_dir = output_root / f"n{n}"
    out_dir.mkdir(parents=True, exist_ok=True)

    rng = random.Random(seed + n)
    now_str = ts(now)

    app_list = applications()
    plan_list = plans()
    app_ids = list(range(1, len(app_list) + 1))
    plan_ids = list(range(1, len(plan_list) + 1))
    plan_shared_pool_amount = {idx: row[3] for idx, row in enumerate(plan_list, start=1)}
    plan_basic_amount = {idx: row[2] for idx, row in enumerate(plan_list, start=1)}
    plan_is_unlimited = {idx: row[6] for idx, row in enumerate(plan_list, start=1)}
    usage_dates_with_month_flag: list[tuple[str, bool]] = []
    for i in range(usage_days):
        ud = start_usage_date + timedelta(days=i)
        usage_dates_with_month_flag.append((ud.isoformat(), ud.year == now.year and ud.month == now.month))

    total_users = n + ADMIN_COUNT
    all_on_count = int(total_users * 0.3)
    all_on_user_ids = set(rng.sample(range(1, total_users + 1), k=all_on_count))

    csvs = CsvBundle(out_dir)
    definitions = {
        "01_role.csv": ["role_id", "role_name"],
        "02_permission.csv": ["permission_id", "permission_title", "created_at", "deleted_at", "updated_at"],
        "03_policy_category.csv": ["policy_category_id", "policy_category_name", "created_at", "deleted_at", "updated_at"],
        "04_policy.csv": ["policy_id", "policy_category_id", "policy_name", "is_active", "is_new", "created_at", "deleted_at", "updated_at"],
        "05_question_category.csv": ["question_category_id", "question_category_name", "created_at", "deleted_at"],
        "06_application.csv": ["application_id", "application_name", "category"],
        "07_plan.csv": ["plan_id", "plan_category", "plan_name", "basic_data_amount", "shared_pool_amount", "network_type", "qos_speed_limit", "is_unlimited", "created_at", "deleted_at", "updated_at"],
        "10_users.csv": ["user_id", "user_name", "email", "password", "age", "created_at", "deleted_at", "updated_at"],
        "11_user_role.csv": ["user_id", "role_id", "created_at"],
        "12_alarm_setting.csv": ["user_id", "family_alarm", "user_alarm", "policy_change_alarm", "policy_limit_alarm", "permission_alarm", "question_alarm", "created_at", "deleted_at", "updated_at"],
        "20_family.csv": ["family_id", "pool_base_data", "pool_total_data", "pool_remaining_data", "created_at", "deleted_at", "updated_at", "family_threshold", "is_threshold_active"],
        "21_line.csv": ["line_id", "user_id", "plan_id", "phone", "block_end_at", "remaining_data", "is_main", "created_at", "deleted_at", "updated_at", "individual_threshold", "is_threshold_active"],
        "22_family_line.csv": ["family_id", "line_id", "role", "is_public", "created_at", "updated_at"],
        "23_permission_line.csv": ["line_id", "permission_id", "is_enable", "created_at", "updated_at"],
        "24_line_limit.csv": ["limit_id", "line_id", "daily_data_limit", "is_daily_limit_active", "shared_data_limit", "is_shared_limit_active", "created_at", "deleted_at", "updated_at"],
        "25_app_policy.csv": ["app_policy_id", "line_id", "application_id", "data_limit", "speed_limit", "is_active", "created_at", "deleted_at", "updated_at", "is_whitelist"],
        "30_daily_total_data.csv": ["usage_date", "line_id", "total_usage_data", "created_at", "deleted_at", "updated_at"],
        "31_family_shared_usage_daily.csv": ["usage_date", "family_id", "line_id", "usage_amount", "contribution_amount", "created_at", "deleted_at", "updated_at"],
        "32_daily_app_total_data.csv": ["usage_date", "line_id", "application_id", "total_usage_data", "created_at", "deleted_at", "updated_at"],
    }
    for filename, headers in definitions.items():
        csvs.open(filename, headers)

    for row in [[1, "ROLE_ADMIN"], [2, "ROLE_USER"]]:
        csvs.write("01_role.csv", row)

    for row in [[1, "상세페이지 열람 권한", now_str, null(), null()], [2, "앱 사용량 비공개 허용 권한", now_str, null(), null()]]:
        csvs.write("02_permission.csv", row)

    policy_categories = ["반복 차단", "즉시 차단", "공유 데이터 사용 제한", "개인 데이터 사용 제한", "앱 데이터 사용 제한", "앱 데이터 속도 제한"]
    for idx, name in enumerate(policy_categories, start=1):
        csvs.write("03_policy_category.csv", [idx, name, now_str, null(), null()])

    policy_rows = [
        [1, 1, "REPEAT_BLOCK_POLICY", 1, 1, now_str, null(), null()],
        [2, 2, "LINE_BLOCK_END_POLICY", 1, 1, now_str, null(), null()],
        [3, 3, "LINE_LIMIT_SHARED_POLICY", 1, 1, now_str, null(), null()],
        [4, 4, "LINE_LIMIT_DAILY_POLICY", 1, 1, now_str, null(), null()],
        [5, 5, "APP_POLICY_DATA_POLICY", 1, 1, now_str, null(), null()],
        [6, 6, "APP_POLICY_SPEED_POLICY", 1, 1, now_str, null(), null()],
    ]
    for row in policy_rows:
        csvs.write("04_policy.csv", row)

    for row in [[1, "policy_inquiry", now_str, null()], [2, "bug_report", now_str, null()], [3, "others", now_str, null()]]:
        csvs.write("05_question_category.csv", row)

    for idx, (cat, name) in enumerate(app_list, start=1):
        csvs.write("06_application.csv", [idx, name, cat])

    for idx, (cat, name, basic, shared, network, qos, unlimited) in enumerate(plan_list, start=1):
        csvs.write("07_plan.csv", [idx, cat, name, basic, shared, network, qos, unlimited, now_str, null(), null()])

    # Families and line policy flags
    family_count = n // 4
    family_user_max_created = {fid: USER_CREATED_AT_START for fid in range(1, family_count + 1)}
    family_base_sum = {fid: 0 for fid in range(1, family_count + 1)}
    family_contrib_sum = {fid: 0 for fid in range(1, family_count + 1)}
    family_usage_sum = {fid: 0 for fid in range(1, family_count + 1)}
    family_net_spend = {fid: 0 for fid in range(1, family_count + 1)}

    distribution = [1] * int(n * 0.7) + [2] * int(n * 0.2) + [3] * int(n * 0.1)
    total_lines = sum(distribution)

    line_id = 1
    phone_seq = 90000000
    app_policy_id = 1

    def write_alarm_setting(uid: int, created_at: datetime) -> None:
        if uid in all_on_user_ids:
            flags = [1, 1, 1, 1, 1, 1]
        else:
            flags = mixed_alarm_flags(rng)
        csvs.write("12_alarm_setting.csv", [uid, *flags, ts(created_at), null(), null()])

    # Admin users
    for uid in range(1, ADMIN_COUNT + 1):
        name = f"admin_front_{uid:02d}" if uid <= 2 else f"admin_back_{uid - 2:02d}"
        user_created = random_dt_between(rng, USER_CREATED_AT_START, USER_CREATED_AT_END)
        csvs.write("10_users.csv", [uid, name, f"{name}@gmail.com", PASSWORD_HASH, rng.randint(25, 45), ts(user_created), null(), null()])
        csvs.write("11_user_role.csv", [uid, 1, ts(random_dt_after(rng, user_created, now))])
        write_alarm_setting(uid, random_dt_after(rng, user_created, now))

    # General users and all dependent records
    for user_idx, line_count in enumerate(distribution, start=1):
        uid = ADMIN_COUNT + user_idx
        family_id = ((user_idx - 1) // 4) + 1
        pos_in_family = (user_idx - 1) % 4

        user_created = random_dt_between(rng, USER_CREATED_AT_START, USER_CREATED_AT_END)
        family_user_max_created[family_id] = max(family_user_max_created[family_id], user_created)

        csvs.write("10_users.csv", [uid, f"user_{user_idx:07d}", f"user_{user_idx:07d}@gmail.com", PASSWORD_HASH, rng.randint(8, 75), ts(user_created), null(), null()])
        csvs.write("11_user_role.csv", [uid, 2, ts(random_dt_after(rng, user_created, now))])
        write_alarm_setting(uid, random_dt_after(rng, user_created, now))

        for line_seq in range(line_count):
            plan_id = rng.choice(plan_ids)
            basic_amount = plan_basic_amount[plan_id]
            shared_amount = plan_shared_pool_amount[plan_id]

            line_created = random_dt_after(rng, user_created, now)
            is_main = 1 if line_seq == 0 else 0
            line_threshold_active = rng.choice([0, 1])
            if line_threshold_active == 1:
                individual_threshold = rng.choice([3000000000, 5000000000, 7000000000])
            else:
                individual_threshold = 0

            if plan_is_unlimited[plan_id] == 1 or basic_amount < 0:
                line_remaining_data = -1
            else:
                line_remaining_upper = max(0, basic_amount)
                line_remaining_data = rng.randint(0, line_remaining_upper) if line_remaining_upper > 0 else 0

            csvs.write(
                "21_line.csv",
                [
                    line_id,
                    uid,
                    plan_id,
                    f"010{phone_seq:08d}",
                    null(),
                    line_remaining_data,
                    is_main,
                    ts(line_created),
                    null(),
                    null(),
                    individual_threshold,
                    line_threshold_active,
                ],
            )

            # Family base = sum(plan shared pool + 1GB) over member lines.
            family_base_sum[family_id] += shared_amount + ONE_GB_BYTE

            permission_created = random_dt_after(rng, line_created, now)
            p2_enabled = 1 if rng.random() < 0.35 else 0
            csvs.write("23_permission_line.csv", [line_id, 1, 1, ts(permission_created), null()])
            csvs.write("23_permission_line.csv", [line_id, 2, p2_enabled, ts(permission_created), null()])

            role = "OWNER" if (pos_in_family == 0 and line_seq == 0) else "MEMBER"
            is_public = 0 if (p2_enabled == 1 and rng.random() < 0.4) else 1
            family_line_created = random_dt_after(rng, line_created, now)
            csvs.write("22_family_line.csv", [family_id, line_id, role, is_public, ts(family_line_created), null()])

            has_policy_records = (line_id % 2 == 0)

            is_daily_active = 1 if rng.random() < 0.8 else 0
            is_shared_active = 1 if rng.random() < 0.75 else 0
            daily_limit = -1 if rng.random() < 0.2 else rng.randint(500_000_000, 20_000_000_000)
            shared_limit = -1 if rng.random() < 0.3 else rng.randint(300_000_000, 10_000_000_000)

            if has_policy_records:
                csvs.write(
                    "24_line_limit.csv",
                    [line_id, line_id, daily_limit, is_daily_active, shared_limit, is_shared_active, ts(random_dt_after(rng, line_created, now)), null(), null()],
                )
            else:
                is_daily_active = 0
                is_shared_active = 0
                daily_limit = -1
                shared_limit = -1

            for ud, is_current_month in usage_dates_with_month_flag:
                if is_daily_active == 1 and daily_limit >= 0:
                    daily_upper = daily_limit
                elif basic_amount > 0:
                    daily_upper = min(basic_amount, 20_000_000_000)
                else:
                    daily_upper = 20_000_000_000

                total_usage = rng.randint(max(0, daily_upper // 20), max(1, daily_upper))
                csvs.write("30_daily_total_data.csv", [ud, line_id, total_usage, ts(random_dt_after(rng, line_created, now)), null(), null()])

                if is_shared_active == 1 and shared_limit >= 0:
                    shared_upper = shared_limit
                else:
                    shared_upper = 8_000_000_000

                usage_amount = rng.randint(max(0, shared_upper // 30), max(1, shared_upper))
                net_allowance = max(0, family_base_sum[family_id] - family_net_spend[family_id])
                min_contribution = max(0, usage_amount - net_allowance)
                contribution_amount = rng.randint(min_contribution, usage_amount)
                family_net_spend[family_id] += usage_amount - contribution_amount

                if is_current_month:
                    family_usage_sum[family_id] += usage_amount
                    family_contrib_sum[family_id] += contribution_amount

                csvs.write(
                    "31_family_shared_usage_daily.csv",
                    [ud, family_id, line_id, usage_amount, contribution_amount, ts(random_dt_after(rng, line_created, now)), null(), null()],
                )

            if has_policy_records:
                policy_count = rng.randint(3, 5)
                chosen_apps = rng.sample(app_ids, k=policy_count)
                for app_id in chosen_apps:
                    is_active = 1 if rng.random() < 0.85 else 0
                    is_whitelist = 1 if rng.random() < 0.2 else 0
                    data_limit = -1 if rng.random() < 0.25 else rng.randint(50_000_000, 3_000_000_000)
                    speed_limit = -1 if rng.random() < 0.35 else rng.choice([400, 1000, 3000, 5000])
                    app_policy_created = random_dt_after(rng, line_created, now)

                    csvs.write(
                        "25_app_policy.csv",
                        [app_policy_id, line_id, app_id, data_limit, speed_limit, is_active, ts(app_policy_created), null(), null(), is_whitelist],
                    )

                    for ud, _ in usage_dates_with_month_flag:
                        if is_active == 1 and is_whitelist == 0 and data_limit >= 0:
                            app_upper = data_limit
                        else:
                            app_upper = 2_500_000_000
                        app_usage = rng.randint(max(0, app_upper // 25), max(1, app_upper))
                        csvs.write(
                            "32_daily_app_total_data.csv",
                            [ud, line_id, app_id, app_usage, ts(random_dt_after(rng, app_policy_created, now)), null(), null()],
                        )

                    app_policy_id += 1

            line_id += 1
            phone_seq += 1

    # Family rows are written after all line usage records are generated.
    for family_id in range(1, family_count + 1):
        base_data = family_base_sum[family_id]
        total_data = base_data + family_contrib_sum[family_id]
        remaining_data = total_data - family_usage_sum[family_id]
        family_created = random_dt_after(rng, family_user_max_created[family_id], now)
        family_threshold_active = rng.choice([0, 1])
        if family_threshold_active == 1:
            family_threshold = rng.choice([3_000_000_000, 5_000_000_000, 7_000_000_000, 10_000_000_000])
        else:
            family_threshold = 0

        csvs.write(
            "20_family.csv",
            [
                family_id,
                base_data,
                total_data,
                remaining_data,
                ts(family_created),
                null(),
                null(),
                family_threshold,
                family_threshold_active,
            ],
        )

    csvs.close()
    write_load_sql(out_dir)
    write_verify_sql(out_dir, n, now)

    print(f"Generated profile directory: {out_dir}")
    print(f"Users: {n + ADMIN_COUNT} (admins={ADMIN_COUNT}, general={n})")
    print(f"Lines: {line_id - 1}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate test-data CSV files")
    parser.add_argument("--n", type=int, required=True, help="general user count (multiple of 100)")
    parser.add_argument("--output-root", type=Path, default=Path("scripts/test-data/output"), help="output root directory")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED, help="random seed")
    parser.add_argument("--usage-days", type=int, default=DEFAULT_USAGE_DAYS, help="number of usage days")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    generate(
        n=args.n,
        output_root=args.output_root,
        seed=args.seed,
        now=DEFAULT_NOW,
        start_usage_date=DEFAULT_START_USAGE_DATE,
        usage_days=args.usage_days,
    )


if __name__ == "__main__":
    main()
