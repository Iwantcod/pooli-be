#!/usr/bin/env python3
from __future__ import annotations

import argparse
import calendar
import csv
import random
from datetime import date, datetime, timedelta
from pathlib import Path

ADMIN_COUNT = 5
USERS_PER_FAMILY = 4
SPECIAL_FAMILY_COUNT = 10_000

DEFAULT_N = 1_000_000
DEFAULT_SEED = 20260318
DEFAULT_NOW = datetime(2026, 3, 18, 9, 0, 0, 0)
DEFAULT_USAGE_DAYS = 7

USER_CREATED_AT_START = datetime(2010, 1, 1, 0, 0, 0, 0)
USER_CREATED_AT_END = datetime(2025, 12, 31, 23, 59, 59, 999999)

ONE_GB_BYTE = 1_000_000_000
PASSWORD_HASH = "$2b$12$tz4Zk2GGvyHGk1JKMdeYtOfZ/orlRdOD1DtWmlfabvR/F2rAq9hiG"

# 120개의 한글 음절을 준비해 3글자 조합으로 1,728,000개 이름을 만들 수 있다.
# 요구사항(모든 user_name은 3글자 한글)을 충족하기 위해 인덱스 기반으로 생성한다.
NAME_SYLLABLES = [chr(code) for code in range(0xAC00, 0xAC00 + 120)]


DAY_OF_WEEK_ORDER = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
WEEKDAY_SET = {"MON", "TUE", "WED", "THU", "FRI"}
NEXT_DAY_OF_WEEK = {
    day: DAY_OF_WEEK_ORDER[(idx + 1) % len(DAY_OF_WEEK_ORDER)]
    for idx, day in enumerate(DAY_OF_WEEK_ORDER)
}


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


def scale_bytes(value: int, value_scale: float) -> int:
    if value < 0:
        return value
    scaled = int(value * value_scale)
    if value > 0 and scaled == 0:
        return 1
    return scaled


def korean_name_from_index(index: int) -> str:
    base = len(NAME_SYLLABLES)
    idx = index - 1
    return (
        NAME_SYLLABLES[(idx // (base * base)) % base]
        + NAME_SYLLABLES[(idx // base) % base]
        + NAME_SYLLABLES[idx % base]
    )


def split_integer_total(total: int, parts: int, rng: random.Random) -> list[int]:
    if parts <= 0:
        return []
    if total <= 0:
        return [0] * parts

    weights = [rng.randint(1, 10_000) for _ in range(parts)]
    weight_sum = sum(weights)
    values = [total * w // weight_sum for w in weights]
    remain = total - sum(values)

    if remain > 0:
        order = list(range(parts))
        rng.shuffle(order)
        for i in range(remain):
            values[order[i % parts]] += 1
    return values


def build_usage_dates(now: datetime, usage_days: int) -> list[date]:
    if usage_days <= 0:
        raise ValueError("usage_days must be positive")

    first_day = date(now.year, now.month, 1)
    _, month_last = calendar.monthrange(now.year, now.month)
    if usage_days > month_last:
        raise ValueError(f"usage_days must be <= {month_last} for current month fixed mode")

    return [first_day + timedelta(days=i) for i in range(usage_days)]


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
    while True:
        vals = [rng.randint(0, 1) for _ in range(6)]
        if any(v == 1 for v in vals) and any(v == 0 for v in vals):
            return vals


def sql_load_block(filename: str, table: str, columns: str, set_clause: str = "") -> str:
    block = f"""LOAD DATA LOCAL INFILE '{filename}'
INTO TABLE {table}
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\\n'
IGNORE 1 LINES
({columns})
"""
    if set_clause:
        block += f"{set_clause}\n"
    block += ";\n"
    return block


def write_load_sql(out_dir: Path) -> None:
    truncates = [
        "ANSWER_ATTACHMENT",
        "QUESTION_ATTACHMENT",
        "ANSWER",
        "QUESTION",
        "DAILY_APP_TOTAL_DATA",
        "FAMILY_SHARED_USAGE_DAILY",
        "DAILY_TOTAL_DATA",
        "REPEAT_BLOCK_DAY",
        "REPEAT_BLOCK",
        "APP_POLICY",
        "LINE_LIMIT",
        "PERMISSION_LINE",
        "FAMILY_LINE",
        "ALARM_HISTORY",
        "TRAFFIC_DEDUCT_DONE",
        "TRAFFIC_REDIS_OUTBOX",
        "TRAFFIC_SHARED_THRESHOLD_ALARM_LOG",
        "LINE",
        "ALARM_SETTING",
        "USER_ROLE",
        "FAMILY",
        "USERS",
        "PLAN",
        "APPLICATION",
        "QUESTION_CATEGORY",
        "POLICY",
        "POLICY_CATEGORY",
        "PERMISSION",
        "ROLE",
    ]

    loads = [
        sql_load_block(
            "01_role.csv",
            "ROLE",
            "role_id, @role_name",
            "SET role_name = REPLACE(@role_name, CHAR(13), '')",
        ),
        sql_load_block(
            "02_permission.csv",
            "PERMISSION",
            "permission_id, permission_title, created_at, @deleted_at, @updated_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "03_policy_category.csv",
            "POLICY_CATEGORY",
            "policy_category_id, policy_category_name, created_at, @deleted_at, @updated_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "04_policy.csv",
            "POLICY",
            "policy_id, policy_category_id, policy_name, is_active, is_new, created_at, @deleted_at, @updated_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "05_question_category.csv",
            "QUESTION_CATEGORY",
            "question_category_id, question_category_name, created_at, @deleted_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "06_application.csv",
            "APPLICATION",
            "application_id, application_name, category",
        ),
        sql_load_block(
            "07_plan.csv",
            "PLAN",
            "plan_id, plan_category, plan_name, basic_data_amount, shared_pool_amount, network_type, qos_speed_limit, is_unlimited, created_at, @deleted_at, @updated_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "10_users.csv",
            "USERS",
            "user_id, user_name, email, password, age, created_at, @deleted_at, @updated_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "11_user_role.csv",
            "USER_ROLE",
            "user_id, role_id, created_at",
        ),
        sql_load_block(
            "12_alarm_setting.csv",
            "ALARM_SETTING",
            "user_id, family_alarm, user_alarm, policy_change_alarm, policy_limit_alarm, permission_alarm, question_alarm, created_at, @deleted_at, @updated_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "20_family.csv",
            "FAMILY",
            "family_id, pool_base_data, pool_total_data, pool_remaining_data, created_at, @deleted_at, @updated_at, family_threshold, is_threshold_active",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "21_line.csv",
            "LINE",
            "line_id, user_id, plan_id, phone, @block_end_at, remaining_data, is_main, created_at, @deleted_at, @updated_at, individual_threshold, is_threshold_active",
            "SET block_end_at = NULLIF(NULLIF(@block_end_at, '\\N'), 'NULL'), deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "22_family_line.csv",
            "FAMILY_LINE",
            "family_id, line_id, role, is_public, created_at, @updated_at",
            "SET updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "23_permission_line.csv",
            "PERMISSION_LINE",
            "line_id, permission_id, is_enable, created_at, @updated_at",
            "SET updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "24_line_limit.csv",
            "LINE_LIMIT",
            "limit_id, line_id, daily_data_limit, is_daily_limit_active, shared_data_limit, is_shared_limit_active, created_at, @deleted_at, @updated_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "25_app_policy.csv",
            "APP_POLICY",
            "app_policy_id, line_id, application_id, data_limit, speed_limit, is_active, created_at, @deleted_at, @updated_at, is_whitelist",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "26_repeat_block.csv",
            "REPEAT_BLOCK",
            "repeat_block_id, line_id, is_active, created_at, @deleted_at, @updated_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "27_repeat_block_day.csv",
            "REPEAT_BLOCK_DAY",
            "repeat_block_day_id, repeat_block_id, day_of_week, start_at, end_at, created_at, @deleted_at, @updated_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "30_daily_total_data.csv",
            "DAILY_TOTAL_DATA",
            "usage_date, line_id, total_usage_data, created_at, @deleted_at, @updated_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "31_family_shared_usage_daily.csv",
            "FAMILY_SHARED_USAGE_DAILY",
            "usage_date, family_id, line_id, usage_amount, contribution_amount, created_at, @deleted_at, @updated_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "32_daily_app_total_data.csv",
            "DAILY_APP_TOTAL_DATA",
            "usage_date, line_id, application_id, total_usage_data, created_at, @deleted_at, @updated_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), updated_at = NULLIF(NULLIF(@updated_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "40_question.csv",
            "QUESTION",
            "question_id, question_category_id, line_id, title, content, is_answer, created_at, @deleted_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "41_answer.csv",
            "ANSWER",
            "answer_id, user_id, question_id, content, created_at, @deleted_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "42_question_attachment.csv",
            "QUESTION_ATTACHMENT",
            "question_attachment_id, question_id, s3_key, file_size, created_at, @deleted_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "43_answer_attachment.csv",
            "ANSWER_ATTACHMENT",
            "answer_attachment_id, answer_id, s3_key, file_size, created_at, @deleted_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "44_alarm_history.csv",
            "ALARM_HISTORY",
            "alarm_history_id, line_id, alarm_code, value, created_at, @deleted_at, @read_at",
            "SET deleted_at = NULLIF(NULLIF(@deleted_at, '\\N'), 'NULL'), read_at = NULLIF(NULLIF(@read_at, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "50_traffic_deduct_done.csv",
            "TRAFFIC_DEDUCT_DONE",
            "traffic_deduct_done_id, trace_id, line_id, family_id, app_id, api_total_data, deducted_total_bytes, api_remaining_data, final_status, @last_lua_status, created_at, finished_at, persisted_at",
            "SET last_lua_status = NULLIF(NULLIF(@last_lua_status, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "51_traffic_redis_outbox.csv",
            "TRAFFIC_REDIS_OUTBOX",
            "id, event_type, payload, @uuid, status, retry_count, created_at, status_updated_at",
            "SET uuid = NULLIF(NULLIF(@uuid, '\\N'), 'NULL')",
        ),
        sql_load_block(
            "54_traffic_shared_threshold_alarm_log.csv",
            "TRAFFIC_SHARED_THRESHOLD_ALARM_LOG",
            "family_id, target_month, threshold_pct, created_at",
        ),
    ]

    sql = [
        "-- Run from profile directory:",
        "--   mysql --local-infile=1 -u <user> -p <db_name> < load_data.sql",
        "",
        "SET NAMES utf8mb4;",
        "SET FOREIGN_KEY_CHECKS = 0;",
        "SET UNIQUE_CHECKS = 0;",
        "SET AUTOCOMMIT = 0;",
        "",
        "START TRANSACTION;",
        "",
    ]

    for table in truncates:
        sql.append(f"TRUNCATE TABLE {table};")

    sql.append("")
    sql.extend(loads)

    sql.extend(
        [
            "",
            "COMMIT;",
            "",
            "SET AUTOCOMMIT = 1;",
            "SET UNIQUE_CHECKS = 1;",
            "SET FOREIGN_KEY_CHECKS = 1;",
            "",
        ]
    )

    (out_dir / "load_data.sql").write_text("\n".join(sql), encoding="utf-8")


def write_verify_sql(out_dir: Path, n: int, now: datetime, one_gb_scaled: int) -> None:
    current_month = now.strftime("%Y-%m")

    sql = f"""SET @N := {n};
SET @TOTAL_USERS := @N + {ADMIN_COUNT};
SET @FAMILY_COUNT := FLOOR(@N / {USERS_PER_FAMILY});
SET @SPECIAL_FAMILY_COUNT := LEAST(@FAMILY_COUNT, {SPECIAL_FAMILY_COUNT});
SET @EXPECTED_LINES := @N + @SPECIAL_FAMILY_COUNT;
SET @CURRENT_MONTH := '{current_month}';
SET @ONE_GB := {one_gb_scaled};

DROP TEMPORARY TABLE IF EXISTS tmp_dtd_line_day;
CREATE TEMPORARY TABLE tmp_dtd_line_day AS
SELECT usage_date,
       line_id,
       COUNT(*) AS dtd_row_count,
       SUM(total_usage_data) AS dtd_total_usage_sum
FROM DAILY_TOTAL_DATA
WHERE DATE_FORMAT(usage_date, '%Y-%m') = @CURRENT_MONTH
GROUP BY usage_date, line_id;

DROP TEMPORARY TABLE IF EXISTS tmp_fsud_line_day;
CREATE TEMPORARY TABLE tmp_fsud_line_day AS
SELECT usage_date,
       line_id,
       COUNT(*) AS fsud_row_count,
       COUNT(DISTINCT family_id) AS fsud_family_count,
       SUM(usage_amount) AS fsud_usage_sum,
       SUM(contribution_amount) AS fsud_contribution_sum
FROM FAMILY_SHARED_USAGE_DAILY
WHERE DATE_FORMAT(usage_date, '%Y-%m') = @CURRENT_MONTH
GROUP BY usage_date, line_id;

DROP TEMPORARY TABLE IF EXISTS tmp_datd_line_day;
CREATE TEMPORARY TABLE tmp_datd_line_day AS
SELECT usage_date,
       line_id,
       COUNT(*) AS datd_row_count,
       SUM(total_usage_data) AS datd_app_usage_sum
FROM DAILY_APP_TOTAL_DATA
WHERE DATE_FORMAT(usage_date, '%Y-%m') = @CURRENT_MONTH
GROUP BY usage_date, line_id;

DROP TEMPORARY TABLE IF EXISTS tmp_all_line_day_keys;
CREATE TEMPORARY TABLE tmp_all_line_day_keys AS
SELECT usage_date, line_id FROM tmp_dtd_line_day
UNION
SELECT usage_date, line_id FROM tmp_fsud_line_day
UNION
SELECT usage_date, line_id FROM tmp_datd_line_day;

DROP TEMPORARY TABLE IF EXISTS tmp_line_monthly_usage;
CREATE TEMPORARY TABLE tmp_line_monthly_usage AS
SELECT l.line_id,
       p.basic_data_amount,
       p.is_unlimited,
       l.remaining_data,
       COALESCE(SUM(d.total_usage_data), 0) AS month_total_usage
FROM LINE l
JOIN PLAN p
  ON p.plan_id = l.plan_id
LEFT JOIN DAILY_TOTAL_DATA d
  ON d.line_id = l.line_id
 AND DATE_FORMAT(d.usage_date, '%Y-%m') = @CURRENT_MONTH
GROUP BY l.line_id, p.basic_data_amount, p.is_unlimited, l.remaining_data;

DROP TEMPORARY TABLE IF EXISTS tmp_dtd_cumulative;
CREATE TEMPORARY TABLE tmp_dtd_cumulative AS
SELECT usage_date,
       line_id,
       SUM(total_usage_data) OVER (
           PARTITION BY line_id
           ORDER BY usage_date
           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
       ) AS cumulative_total_usage
FROM DAILY_TOTAL_DATA
WHERE DATE_FORMAT(usage_date, '%Y-%m') = @CURRENT_MONTH;

DROP TEMPORARY TABLE IF EXISTS tmp_line_policy_presence;
CREATE TEMPORARY TABLE tmp_line_policy_presence AS
SELECT l.line_id,
       CASE WHEN MOD(l.line_id, 4) = 1 THEN 1 ELSE 0 END AS should_have_policy,
       COALESCE(ll.ll_cnt, 0) AS ll_cnt,
       COALESCE(ap.ap_cnt, 0) AS ap_cnt,
       COALESCE(rb.rb_cnt, 0) AS rb_cnt,
       COALESCE(rbd.rbd_cnt, 0) AS rbd_cnt
FROM LINE l
LEFT JOIN (
    SELECT line_id, COUNT(*) AS ll_cnt
    FROM LINE_LIMIT
    GROUP BY line_id
) ll ON ll.line_id = l.line_id
LEFT JOIN (
    SELECT line_id, COUNT(*) AS ap_cnt
    FROM APP_POLICY
    GROUP BY line_id
) ap ON ap.line_id = l.line_id
LEFT JOIN (
    SELECT line_id, COUNT(*) AS rb_cnt
    FROM REPEAT_BLOCK
    GROUP BY line_id
) rb ON rb.line_id = l.line_id
LEFT JOIN (
    SELECT rb.line_id, COUNT(*) AS rbd_cnt
    FROM REPEAT_BLOCK rb
    JOIN REPEAT_BLOCK_DAY rbd ON rbd.repeat_block_id = rb.repeat_block_id
    GROUP BY rb.line_id
) rbd ON rbd.line_id = l.line_id;

DROP TEMPORARY TABLE IF EXISTS tmp_family_owner_user;
CREATE TEMPORARY TABLE tmp_family_owner_user AS
SELECT fl.family_id, l.user_id
FROM FAMILY_LINE fl
JOIN LINE l ON l.line_id = fl.line_id
WHERE fl.role = 'OWNER'
GROUP BY fl.family_id, l.user_id;

DROP TEMPORARY TABLE IF EXISTS tmp_user_line_role_in_family;
CREATE TEMPORARY TABLE tmp_user_line_role_in_family AS
SELECT fl.family_id,
       l.user_id,
       COUNT(*) AS line_cnt,
       SUM(CASE WHEN fl.role = 'OWNER' THEN 1 ELSE 0 END) AS owner_cnt,
       SUM(CASE WHEN fl.role = 'MEMBER' THEN 1 ELSE 0 END) AS member_cnt
FROM FAMILY_LINE fl
JOIN LINE l ON l.line_id = fl.line_id
GROUP BY fl.family_id, l.user_id;

-- C01: DAILY_TOTAL_DATA는 회선별/일별로 정확히 1건이어야 한다.
SELECT 'C01_dtd_exactly_one_row_per_line_day' AS check_name,
       COUNT(*) AS violations
FROM tmp_dtd_line_day
WHERE dtd_row_count <> 1;

-- C02: FAMILY_SHARED_USAGE_DAILY는 회선별/일별로 정확히 1건이어야 한다.
SELECT 'C02_fsud_exactly_one_row_per_line_day' AS check_name,
       COUNT(*) AS violations
FROM tmp_fsud_line_day
WHERE fsud_row_count <> 1;

-- C03: DAILY_APP_TOTAL_DATA는 회선별/일별로 3~5건이어야 한다.
SELECT 'C03_datd_rows_between_3_and_5_per_line_day' AS check_name,
       COUNT(*) AS violations
FROM tmp_datd_line_day
WHERE datd_row_count < 3 OR datd_row_count > 5;

-- C04: DAILY_TOTAL_DATA의 모든 회선/일자 키는 FAMILY_SHARED_USAGE_DAILY에도 존재해야 한다.
SELECT 'C04_dtd_key_missing_in_fsud' AS check_name,
       COUNT(*) AS violations
FROM tmp_dtd_line_day d
LEFT JOIN tmp_fsud_line_day f
  ON f.usage_date = d.usage_date
 AND f.line_id = d.line_id
WHERE f.line_id IS NULL;

-- C05: FAMILY_SHARED_USAGE_DAILY의 모든 회선/일자 키는 DAILY_TOTAL_DATA에도 존재해야 한다.
SELECT 'C05_fsud_key_missing_in_dtd' AS check_name,
       COUNT(*) AS violations
FROM tmp_fsud_line_day f
LEFT JOIN tmp_dtd_line_day d
  ON d.usage_date = f.usage_date
 AND d.line_id = f.line_id
WHERE d.line_id IS NULL;

-- C06: DAILY_TOTAL_DATA의 모든 회선/일자 키는 DAILY_APP_TOTAL_DATA에도 존재해야 한다.
SELECT 'C06_dtd_key_missing_in_datd' AS check_name,
       COUNT(*) AS violations
FROM tmp_dtd_line_day d
LEFT JOIN tmp_datd_line_day a
  ON a.usage_date = d.usage_date
 AND a.line_id = d.line_id
WHERE a.line_id IS NULL;

-- C07: DAILY_APP_TOTAL_DATA의 모든 회선/일자 키는 DAILY_TOTAL_DATA에도 존재해야 한다.
SELECT 'C07_datd_key_missing_in_dtd' AS check_name,
       COUNT(*) AS violations
FROM tmp_datd_line_day a
LEFT JOIN tmp_dtd_line_day d
  ON d.usage_date = a.usage_date
 AND d.line_id = a.line_id
WHERE d.line_id IS NULL;

-- C08: 회선/일자별 DAILY_APP_TOTAL_DATA 합계는 DAILY_TOTAL_DATA 값과 같아야 한다.
SELECT 'C08_daily_app_sum_equals_daily_total' AS check_name,
       COUNT(*) AS violations
FROM tmp_all_line_day_keys k
LEFT JOIN tmp_dtd_line_day d
  ON d.usage_date = k.usage_date
 AND d.line_id = k.line_id
LEFT JOIN tmp_datd_line_day a
  ON a.usage_date = k.usage_date
 AND a.line_id = k.line_id
WHERE COALESCE(d.dtd_total_usage_sum, 0) <> COALESCE(a.datd_app_usage_sum, 0);

-- C09: 비무제한 회선의 remaining_data는 max(0, basic_data_amount - 당월 total_usage)와 같아야 하며 무제한은 -1이어야 한다.
SELECT 'C09_line_remaining_formula' AS check_name,
       COUNT(*) AS violations
FROM tmp_line_monthly_usage m
WHERE
      (m.is_unlimited = 1 AND m.remaining_data <> -1)
   OR (m.is_unlimited = 0
       AND m.remaining_data <> GREATEST(0, m.basic_data_amount - m.month_total_usage));

-- C10: 비무제한 회선의 공유풀 사용은 개인풀 누적 소진 이후에만 발생해야 한다.
SELECT 'C10_shared_used_before_personal_exhausted_by_day' AS check_name,
       COUNT(*) AS violations
FROM tmp_fsud_line_day f
JOIN tmp_dtd_cumulative c
  ON c.usage_date = f.usage_date
 AND c.line_id = f.line_id
JOIN LINE l
  ON l.line_id = f.line_id
 AND l.deleted_at IS NULL
JOIN PLAN p
  ON p.plan_id = l.plan_id
 AND p.deleted_at IS NULL
WHERE p.is_unlimited = 0
  AND f.fsud_usage_sum > 0
  AND c.cumulative_total_usage <= p.basic_data_amount;

-- C11: 무제한 요금제 회선은 공유풀 사용량이 0이어야 한다.
SELECT 'C11_shared_usage_on_unlimited_plan' AS check_name,
       COUNT(*) AS violations
FROM tmp_fsud_line_day f
JOIN LINE l
  ON l.line_id = f.line_id
 AND l.deleted_at IS NULL
JOIN PLAN p
  ON p.plan_id = l.plan_id
 AND p.deleted_at IS NULL
WHERE p.is_unlimited = 1
  AND f.fsud_usage_sum > 0;

-- C12: line_id % 4 == 1 회선만 LINE_LIMIT/APP_POLICY/REPEAT_BLOCK/REPEAT_BLOCK_DAY를 가져야 하며 건수도 규칙에 맞아야 한다.
SELECT 'C12_policy_scope_and_completeness' AS check_name,
       COUNT(*) AS violations
FROM tmp_line_policy_presence p
WHERE
      (p.should_have_policy = 1 AND (p.ll_cnt <> 1 OR p.ap_cnt < 3 OR p.ap_cnt > 5 OR p.rb_cnt <> 1 OR p.rbd_cnt <> 12))
   OR (p.should_have_policy = 0 AND (p.ll_cnt <> 0 OR p.ap_cnt <> 0 OR p.rb_cnt <> 0 OR p.rbd_cnt <> 0));

-- D01: 총 사용자 수는 일반유저 N + 관리자 5명이어야 한다.
SELECT 'D01_users_total' AS check_name,
       (SELECT COUNT(*) FROM USERS) AS actual,
       @TOTAL_USERS AS expected,
       ((SELECT COUNT(*) FROM USERS) - @TOTAL_USERS) AS delta;

-- D02: 관리자 계정은 회선을 가지면 안 된다.
SELECT 'D02_admin_with_lines' AS check_name,
       COUNT(*) AS violations
FROM LINE l
JOIN USER_ROLE ur ON ur.user_id = l.user_id
JOIN ROLE r ON r.role_id = ur.role_id
WHERE r.role_name = 'ROLE_ADMIN';

-- D03: 모든 사용자 이름은 3글자 한글이어야 한다.
SELECT 'D03_user_name_must_be_3_hangul_chars' AS check_name,
       COUNT(*) AS violations
FROM USERS
WHERE CHAR_LENGTH(user_name) <> 3
   OR user_name NOT REGEXP '^[가-힣]{{3}}$';

-- D04: 가족당 OWNER 역할은 정확히 1건이어야 한다.
SELECT 'D04_owner_exactly_one_per_family' AS check_name,
       COUNT(*) AS violations
FROM (
    SELECT family_id
    FROM FAMILY_LINE
    GROUP BY family_id
    HAVING SUM(CASE WHEN role = 'OWNER' THEN 1 ELSE 0 END) <> 1
) x;

-- D05: 첫 1만 가족은 5회선, 나머지 가족은 4회선이어야 한다.
SELECT 'D05_family_line_count_distribution' AS check_name,
       COUNT(*) AS violations
FROM (
    SELECT family_id, COUNT(*) AS line_cnt
    FROM FAMILY_LINE
    GROUP BY family_id
) f
WHERE (f.family_id <= @SPECIAL_FAMILY_COUNT AND f.line_cnt <> 5)
   OR (f.family_id > @SPECIAL_FAMILY_COUNT AND f.line_cnt <> 4);

-- D06: 첫 1만 가족의 OWNER 사용자 1명은 OWNER 1회선 + MEMBER 1회선(총 2회선)을 가져야 한다.
SELECT 'D06_special_family_owner_dual_line_role' AS check_name,
       COUNT(*) AS violations
FROM tmp_family_owner_user o
JOIN tmp_user_line_role_in_family u
  ON u.family_id = o.family_id
 AND u.user_id = o.user_id
WHERE o.family_id <= @SPECIAL_FAMILY_COUNT
  AND (u.line_cnt <> 2 OR u.owner_cnt <> 1 OR u.member_cnt <> 1);

-- D07: FAMILY.pool_base_data는 가족 회선별 (1GB + plan.shared_pool_amount) 합계와 같아야 한다.
SELECT 'D07_family_pool_base_formula' AS check_name,
       COUNT(*) AS violations
FROM FAMILY f
LEFT JOIN (
  SELECT fl.family_id,
         SUM(@ONE_GB + p.shared_pool_amount) AS expected_base
  FROM FAMILY_LINE fl
  JOIN LINE l ON l.line_id = fl.line_id
  JOIN PLAN p ON p.plan_id = l.plan_id
  GROUP BY fl.family_id
) x ON x.family_id = f.family_id
WHERE f.pool_base_data <> COALESCE(x.expected_base, 0);

-- D08: REPEAT_BLOCK_DAY는 자정 경계 분할 규칙(주중 야간 2건 + 주말 1건)을 만족해야 한다.
SELECT 'D08_repeat_block_day_time_window' AS check_name,
       (
         SELECT COUNT(*)
         FROM (
           SELECT rb.repeat_block_id,
                  e.day_of_week,
                  e.start_at,
                  e.end_at,
                  e.expected_cnt,
                  COALESCE(a.actual_cnt, 0) AS actual_cnt
           FROM REPEAT_BLOCK rb
           CROSS JOIN (
             SELECT 'MON' AS day_of_week, '22:00:00' AS start_at, '23:59:59' AS end_at, 1 AS expected_cnt
             UNION ALL SELECT 'TUE', '00:00:00', '07:00:00', 1
             UNION ALL SELECT 'TUE', '22:00:00', '23:59:59', 1
             UNION ALL SELECT 'WED', '00:00:00', '07:00:00', 1
             UNION ALL SELECT 'WED', '22:00:00', '23:59:59', 1
             UNION ALL SELECT 'THU', '00:00:00', '07:00:00', 1
             UNION ALL SELECT 'THU', '22:00:00', '23:59:59', 1
             UNION ALL SELECT 'FRI', '00:00:00', '07:00:00', 1
             UNION ALL SELECT 'FRI', '22:00:00', '23:59:59', 1
             UNION ALL SELECT 'SAT', '00:00:00', '07:00:00', 1
             UNION ALL SELECT 'SAT', '00:00:00', '09:00:00', 1
             UNION ALL SELECT 'SUN', '00:00:00', '09:00:00', 1
           ) e
           LEFT JOIN (
             SELECT repeat_block_id,
                    day_of_week,
                    start_at,
                    end_at,
                    COUNT(*) AS actual_cnt
             FROM REPEAT_BLOCK_DAY
             GROUP BY repeat_block_id, day_of_week, start_at, end_at
           ) a
             ON a.repeat_block_id = rb.repeat_block_id
            AND a.day_of_week = e.day_of_week
            AND a.start_at = e.start_at
            AND a.end_at = e.end_at
         ) x
         WHERE x.actual_cnt <> x.expected_cnt
       )
       +
       (
         SELECT COUNT(*)
         FROM REPEAT_BLOCK_DAY d
         LEFT JOIN (
           SELECT 'MON' AS day_of_week, '22:00:00' AS start_at, '23:59:59' AS end_at
           UNION ALL SELECT 'TUE', '00:00:00', '07:00:00'
           UNION ALL SELECT 'TUE', '22:00:00', '23:59:59'
           UNION ALL SELECT 'WED', '00:00:00', '07:00:00'
           UNION ALL SELECT 'WED', '22:00:00', '23:59:59'
           UNION ALL SELECT 'THU', '00:00:00', '07:00:00'
           UNION ALL SELECT 'THU', '22:00:00', '23:59:59'
           UNION ALL SELECT 'FRI', '00:00:00', '07:00:00'
           UNION ALL SELECT 'FRI', '22:00:00', '23:59:59'
           UNION ALL SELECT 'SAT', '00:00:00', '07:00:00'
           UNION ALL SELECT 'SAT', '00:00:00', '09:00:00'
           UNION ALL SELECT 'SUN', '00:00:00', '09:00:00'
         ) e
           ON e.day_of_week = d.day_of_week
          AND e.start_at = d.start_at
          AND e.end_at = d.end_at
         WHERE e.day_of_week IS NULL
       ) AS violations;

-- D09: 총 회선 수는 일반유저 수 + 특별가족 추가회선 수(최대 1만)와 같아야 한다.
SELECT 'D09_lines_total' AS check_name,
       (SELECT COUNT(*) FROM LINE) AS actual,
       @EXPECTED_LINES AS expected,
       ((SELECT COUNT(*) FROM LINE) - @EXPECTED_LINES) AS delta;
"""

    (out_dir / "verify_data.sql").write_text(sql, encoding="utf-8")


def generate_limited_daily_totals(rng: random.Random, basic_amount: int, usage_days: int) -> list[int]:
    if basic_amount <= 0:
        return [0 for _ in range(usage_days)]

    low = int(basic_amount * 0.6)
    high = int(basic_amount * 1.4)
    if high < low:
        high = low

    target_total = rng.randint(low, high)
    return split_integer_total(target_total, usage_days, rng)


def generate_unlimited_daily_totals(rng: random.Random, usage_days: int, value_scale: float) -> list[int]:
    low = scale_bytes(100_000_000, value_scale)
    high = scale_bytes(2_000_000_000, value_scale)
    if high < low:
        high = low
    return [rng.randint(low, high) for _ in range(usage_days)]


def compute_shared_usage_series(total_series: list[int], basic_amount: int, is_unlimited: int) -> list[int]:
    if is_unlimited == 1:
        return [0 for _ in total_series]

    shared_series: list[int] = []
    cumulative = 0
    for total_usage in total_series:
        prev = cumulative
        cumulative += total_usage
        shared_today = max(0, cumulative - basic_amount) - max(0, prev - basic_amount)
        shared_series.append(shared_today)
    return shared_series


def write_minimal_non_core_rows(
    csvs: CsvBundle,
    rng: random.Random,
    now: datetime,
    usage_dates: list[date],
    total_lines: int,
    family_count: int,
    app_ids: list[int],
    value_scale: float,
) -> None:
    question_count = min(50, total_lines)
    question_attachment_id = 1
    answer_attachment_id = 1

    for qid in range(1, question_count + 1):
        line_id = ((qid - 1) % total_lines) + 1
        created_at = random_dt_between(rng, USER_CREATED_AT_START, now)
        category_id = ((qid - 1) % 3) + 1

        csvs.write(
            "40_question.csv",
            [
                qid,
                category_id,
                line_id,
                f"테스트문의{qid}",
                f"자동 생성 문의 본문 {qid}",
                1,
                ts(created_at),
                null(),
            ],
        )

        answer_created = random_dt_after(rng, created_at, now)
        admin_user_id = ((qid - 1) % ADMIN_COUNT) + 1
        csvs.write(
            "41_answer.csv",
            [
                qid,
                admin_user_id,
                qid,
                f"자동 생성 답변 {qid}",
                ts(answer_created),
                null(),
            ],
        )

        if qid % 2 == 0:
            csvs.write(
                "42_question_attachment.csv",
                [
                    question_attachment_id,
                    qid,
                    f"question/{qid}/image.jpg",
                    scale_bytes(120_000, value_scale),
                    ts(random_dt_after(rng, created_at, now)),
                    null(),
                ],
            )
            question_attachment_id += 1

        if qid % 3 == 0:
            csvs.write(
                "43_answer_attachment.csv",
                [
                    answer_attachment_id,
                    qid,
                    f"answer/{qid}/file.png",
                    scale_bytes(90_000, value_scale),
                    ts(random_dt_after(rng, answer_created, now)),
                    null(),
                ],
            )
            answer_attachment_id += 1

    alarm_codes = ["LIMIT", "POLICY", "PERMISSION", "QUESTION"]
    alarm_rows = min(100, total_lines)
    for i in range(1, alarm_rows + 1):
        line_id = ((i - 1) % total_lines) + 1
        created = random_dt_between(rng, USER_CREATED_AT_START, now)
        if i % 2 == 0:
            read_at = ts(random_dt_after(rng, created, now))
        else:
            read_at = null()

        csvs.write(
            "44_alarm_history.csv",
            [
                i,
                line_id,
                alarm_codes[(i - 1) % len(alarm_codes)],
                f'{{"message":"alarm-{i}"}}',
                ts(created),
                null(),
                read_at,
            ],
        )

    target_month = now.strftime("%Y-%m")

    for i in range(1, 101):
        line_id = ((i - 1) % total_lines) + 1
        family_id = ((i - 1) % family_count) + 1
        app_id = app_ids[(i - 1) % len(app_ids)]

        created = random_dt_between(rng, USER_CREATED_AT_START, now)
        finished = random_dt_after(rng, created, now)
        persisted = random_dt_after(rng, finished, now)

        api_total = scale_bytes(200_000_000 + i * 1_000_000, value_scale)
        deducted = api_total
        remaining = max(0, api_total // 2)

        csvs.write(
            "50_traffic_deduct_done.csv",
            [
                i,
                f"trace-done-{i:08d}",
                line_id,
                family_id,
                app_id,
                api_total,
                deducted,
                remaining,
                "SUCCESS",
                "OK",
                ts(created),
                ts(finished),
                ts(persisted),
            ],
        )

    for i in range(1, 101):
        created = random_dt_between(rng, USER_CREATED_AT_START, now)
        status = "DONE" if i % 3 == 0 else "PENDING"

        csvs.write(
            "51_traffic_redis_outbox.csv",
            [
                i,
                "DEDUCT_APPLIED",
                f'{{"event_no":{i}}}',
                f"outbox-{i:08d}",
                status,
                0 if status == "DONE" else rng.randint(0, 2),
                ts(created),
                ts(random_dt_after(rng, created, now)),
            ],
        )

    for family_id in range(1, min(50, family_count) + 1):
        for threshold_pct in (70, 90):
            csvs.write(
                "54_traffic_shared_threshold_alarm_log.csv",
                [family_id, target_month, threshold_pct, ts(now)],
            )


def generate(
    n: int,
    output_root: Path,
    seed: int,
    now: datetime,
    usage_days: int,
    value_scale: float,
) -> None:
    if n <= 0:
        raise ValueError("n must be positive")
    if n % USERS_PER_FAMILY != 0:
        raise ValueError(f"n must be a multiple of {USERS_PER_FAMILY}")
    if value_scale <= 0:
        raise ValueError("value_scale must be > 0")

    usage_dates = build_usage_dates(now, usage_days)
    usage_date_strings = [d.isoformat() for d in usage_dates]

    out_dir = output_root / f"n{n}"
    out_dir.mkdir(parents=True, exist_ok=True)

    rng = random.Random(seed + n)
    now_str = ts(now)

    app_list = applications()
    app_ids = list(range(1, len(app_list) + 1))

    raw_plan_rows = plans()
    plan_rows: list[tuple[str, str, int, int, str, int, int]] = []
    for cat, name, basic, shared, network, qos, unlimited in raw_plan_rows:
        scaled_basic = scale_bytes(basic, value_scale)
        scaled_shared = scale_bytes(shared, value_scale)
        plan_rows.append((cat, name, scaled_basic, scaled_shared, network, qos, unlimited))

    plan_ids = list(range(1, len(plan_rows) + 1))
    plan_basic_amount = {idx: row[2] for idx, row in enumerate(plan_rows, start=1)}
    plan_shared_pool_amount = {idx: row[3] for idx, row in enumerate(plan_rows, start=1)}
    plan_is_unlimited = {idx: row[6] for idx, row in enumerate(plan_rows, start=1)}

    one_gb_scaled = scale_bytes(ONE_GB_BYTE, value_scale)

    total_users = n + ADMIN_COUNT
    all_on_count = int(total_users * 0.3)
    all_on_user_ids = set(rng.sample(range(1, total_users + 1), k=all_on_count))

    family_count = n // USERS_PER_FAMILY
    special_family_count = min(family_count, SPECIAL_FAMILY_COUNT)

    family_user_max_created = [USER_CREATED_AT_START for _ in range(family_count + 1)]
    family_base_sum = [0 for _ in range(family_count + 1)]
    family_contrib_sum = [0 for _ in range(family_count + 1)]
    family_usage_sum = [0 for _ in range(family_count + 1)]

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
        "26_repeat_block.csv": ["repeat_block_id", "line_id", "is_active", "created_at", "deleted_at", "updated_at"],
        "27_repeat_block_day.csv": ["repeat_block_day_id", "repeat_block_id", "day_of_week", "start_at", "end_at", "created_at", "deleted_at", "updated_at"],
        "30_daily_total_data.csv": ["usage_date", "line_id", "total_usage_data", "created_at", "deleted_at", "updated_at"],
        "31_family_shared_usage_daily.csv": ["usage_date", "family_id", "line_id", "usage_amount", "contribution_amount", "created_at", "deleted_at", "updated_at"],
        "32_daily_app_total_data.csv": ["usage_date", "line_id", "application_id", "total_usage_data", "created_at", "deleted_at", "updated_at"],
        "40_question.csv": ["question_id", "question_category_id", "line_id", "title", "content", "is_answer", "created_at", "deleted_at"],
        "41_answer.csv": ["answer_id", "user_id", "question_id", "content", "created_at", "deleted_at"],
        "42_question_attachment.csv": ["question_attachment_id", "question_id", "s3_key", "file_size", "created_at", "deleted_at"],
        "43_answer_attachment.csv": ["answer_attachment_id", "answer_id", "s3_key", "file_size", "created_at", "deleted_at"],
        "44_alarm_history.csv": ["alarm_history_id", "line_id", "alarm_code", "value", "created_at", "deleted_at", "read_at"],
        "50_traffic_deduct_done.csv": ["traffic_deduct_done_id", "trace_id", "line_id", "family_id", "app_id", "api_total_data", "deducted_total_bytes", "api_remaining_data", "final_status", "last_lua_status", "created_at", "finished_at", "persisted_at"],
        "51_traffic_redis_outbox.csv": ["id", "event_type", "payload", "uuid", "status", "retry_count", "created_at", "status_updated_at"],
        "54_traffic_shared_threshold_alarm_log.csv": ["family_id", "target_month", "threshold_pct", "created_at"],
    }
    for filename, headers in definitions.items():
        csvs.open(filename, headers)

    for row in [[1, "ROLE_ADMIN"], [2, "ROLE_USER"]]:
        csvs.write("01_role.csv", row)

    for row in [
        [1, "상세페이지 열람 권한", now_str, null(), null()],
        [2, "앱 사용량 비공개 허용 권한", now_str, null(), null()],
    ]:
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

    for idx, (cat, app_name) in enumerate(app_list, start=1):
        csvs.write("06_application.csv", [idx, app_name, cat])

    for idx, (cat, plan_name, basic, shared, network, qos, unlimited) in enumerate(plan_rows, start=1):
        csvs.write("07_plan.csv", [idx, cat, plan_name, basic, shared, network, qos, unlimited, now_str, null(), null()])

    line_id = 1
    phone_seq = 10_000_000
    line_limit_id = 1
    app_policy_id = 1
    repeat_block_id = 1
    repeat_block_day_id = 1

    threshold_choices = [
        scale_bytes(3_000_000_000, value_scale),
        scale_bytes(5_000_000_000, value_scale),
        scale_bytes(7_000_000_000, value_scale),
        scale_bytes(10_000_000_000, value_scale),
    ]

    def write_alarm_setting(uid: int, created_at: datetime) -> None:
        if uid in all_on_user_ids:
            flags = [1, 1, 1, 1, 1, 1]
        else:
            flags = mixed_alarm_flags(rng)
        csvs.write("12_alarm_setting.csv", [uid, *flags, ts(created_at), null(), null()])

    for uid in range(1, ADMIN_COUNT + 1):
        user_created = random_dt_between(rng, USER_CREATED_AT_START, USER_CREATED_AT_END)
        user_name = korean_name_from_index(uid)
        email = f"user{uid:07d}@pooli.test"

        csvs.write(
            "10_users.csv",
            [uid, user_name, email, PASSWORD_HASH, rng.randint(25, 45), ts(user_created), null(), null()],
        )
        csvs.write("11_user_role.csv", [uid, 1, ts(random_dt_after(rng, user_created, now))])
        write_alarm_setting(uid, random_dt_after(rng, user_created, now))

    for user_idx in range(1, n + 1):
        uid = ADMIN_COUNT + user_idx
        family_id = ((user_idx - 1) // USERS_PER_FAMILY) + 1
        pos_in_family = (user_idx - 1) % USERS_PER_FAMILY

        user_created = random_dt_between(rng, USER_CREATED_AT_START, USER_CREATED_AT_END)
        family_user_max_created[family_id] = max(family_user_max_created[family_id], user_created)

        user_name = korean_name_from_index(uid)
        email = f"user{uid:07d}@pooli.test"

        csvs.write(
            "10_users.csv",
            [uid, user_name, email, PASSWORD_HASH, rng.randint(8, 75), ts(user_created), null(), null()],
        )
        csvs.write("11_user_role.csv", [uid, 2, ts(random_dt_after(rng, user_created, now))])
        write_alarm_setting(uid, random_dt_after(rng, user_created, now))

        line_count = 2 if (family_id <= special_family_count and pos_in_family == 0) else 1

        for line_seq in range(line_count):
            plan_id = rng.choice(plan_ids)
            basic_amount = plan_basic_amount[plan_id]
            shared_amount = plan_shared_pool_amount[plan_id]
            is_unlimited = plan_is_unlimited[plan_id]

            line_created = random_dt_after(rng, user_created, now)
            is_main = 1 if line_seq == 0 else 0

            threshold_active = 1 if rng.random() < 0.4 else 0
            individual_threshold = rng.choice(threshold_choices) if threshold_active == 1 else 0

            if is_unlimited == 1 or basic_amount < 0:
                daily_totals = generate_unlimited_daily_totals(rng, usage_days, value_scale)
            else:
                daily_totals = generate_limited_daily_totals(rng, basic_amount, usage_days)

            shared_series = compute_shared_usage_series(daily_totals, basic_amount, is_unlimited)
            month_total_usage = sum(daily_totals)

            if is_unlimited == 1 or basic_amount < 0:
                remaining_data = -1
            else:
                remaining_data = max(0, basic_amount - month_total_usage)

            csvs.write(
                "21_line.csv",
                [
                    line_id,
                    uid,
                    plan_id,
                    f"010{phone_seq:08d}",
                    null(),
                    remaining_data,
                    is_main,
                    ts(line_created),
                    null(),
                    null(),
                    individual_threshold,
                    threshold_active,
                ],
            )

            family_base_sum[family_id] += shared_amount + one_gb_scaled

            permission_created = random_dt_after(rng, line_created, now)
            p2_enabled = 1 if rng.random() < 0.35 else 0
            csvs.write("23_permission_line.csv", [line_id, 1, 1, ts(permission_created), null()])
            csvs.write("23_permission_line.csv", [line_id, 2, p2_enabled, ts(permission_created), null()])

            role = "OWNER" if (pos_in_family == 0 and line_seq == 0) else "MEMBER"
            is_public = 0 if (p2_enabled == 1 and rng.random() < 0.4) else 1
            family_line_created = random_dt_after(rng, line_created, now)
            csvs.write("22_family_line.csv", [family_id, line_id, role, is_public, ts(family_line_created), null()])

            has_policy_records = (line_id % 4 == 1)

            if has_policy_records:
                csvs.write(
                    "24_line_limit.csv",
                    [
                        line_limit_id,
                        line_id,
                        -1,
                        1,
                        -1,
                        1,
                        ts(random_dt_after(rng, line_created, now)),
                        null(),
                        null(),
                    ],
                )
                line_limit_id += 1

                repeat_block_created = random_dt_after(rng, line_created, now)
                csvs.write(
                    "26_repeat_block.csv",
                    [repeat_block_id, line_id, 1, ts(repeat_block_created), null(), null()],
                )

                for day_of_week in DAY_OF_WEEK_ORDER:
                    # 자정 경계를 넘는 차단 구간은 단일 레코드로 표현하지 않고, 날짜별로 분할 저장한다.
                    if day_of_week in WEEKDAY_SET:
                        windows = [
                            (day_of_week, "22:00:00", "23:59:59"),
                            (NEXT_DAY_OF_WEEK[day_of_week], "00:00:00", "07:00:00"),
                        ]
                    else:
                        windows = [(day_of_week, "00:00:00", "09:00:00")]

                    for window_day_of_week, start_at, end_at in windows:
                        csvs.write(
                            "27_repeat_block_day.csv",
                            [
                                repeat_block_day_id,
                                repeat_block_id,
                                window_day_of_week,
                                start_at,
                                end_at,
                                ts(random_dt_after(rng, repeat_block_created, now)),
                                null(),
                                null(),
                            ],
                        )
                        repeat_block_day_id += 1

                repeat_block_id += 1

                policy_count = rng.randint(3, 5)
                policy_apps = rng.sample(app_ids, k=policy_count)
                for app_id in policy_apps:
                    is_active = 1 if rng.random() < 0.85 else 0
                    is_whitelist = 1 if rng.random() < 0.2 else 0

                    if rng.random() < 0.25:
                        data_limit = -1
                    else:
                        data_limit = scale_bytes(rng.randint(50_000_000, 3_000_000_000), value_scale)

                    speed_limit = -1 if rng.random() < 0.35 else rng.choice([400, 1000, 3000, 5000])

                    csvs.write(
                        "25_app_policy.csv",
                        [
                            app_policy_id,
                            line_id,
                            app_id,
                            data_limit,
                            speed_limit,
                            is_active,
                            ts(random_dt_after(rng, line_created, now)),
                            null(),
                            null(),
                            is_whitelist,
                        ],
                    )
                    app_policy_id += 1

            for idx, usage_date in enumerate(usage_date_strings):
                total_usage = daily_totals[idx]
                shared_usage = shared_series[idx]

                # 공유풀 사용은 개인풀 소진 이후에만 발생하도록 계산된 값을 그대로 사용한다.
                contribution_amount = shared_usage

                daily_created = ts(random_dt_after(rng, line_created, now))
                csvs.write("30_daily_total_data.csv", [usage_date, line_id, total_usage, daily_created, null(), null()])

                csvs.write(
                    "31_family_shared_usage_daily.csv",
                    [
                        usage_date,
                        family_id,
                        line_id,
                        shared_usage,
                        contribution_amount,
                        ts(random_dt_after(rng, line_created, now)),
                        null(),
                        null(),
                    ],
                )

                app_count = rng.randint(3, 5)
                chosen_apps = rng.sample(app_ids, k=app_count)
                app_usage_parts = split_integer_total(total_usage, app_count, rng)

                for app_id, app_usage in zip(chosen_apps, app_usage_parts):
                    csvs.write(
                        "32_daily_app_total_data.csv",
                        [
                            usage_date,
                            line_id,
                            app_id,
                            app_usage,
                            ts(random_dt_after(rng, line_created, now)),
                            null(),
                            null(),
                        ],
                    )

                family_usage_sum[family_id] += shared_usage
                family_contrib_sum[family_id] += contribution_amount

            line_id += 1
            phone_seq += 1

    for family_id in range(1, family_count + 1):
        base_data = family_base_sum[family_id]
        total_data = base_data + family_contrib_sum[family_id]
        remaining_data = max(0, total_data - family_usage_sum[family_id])

        family_created = random_dt_after(rng, family_user_max_created[family_id], now)
        family_threshold_active = 1 if rng.random() < 0.35 else 0
        if family_threshold_active == 1:
            family_threshold = rng.choice(threshold_choices)
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

    total_lines = line_id - 1
    write_minimal_non_core_rows(
        csvs=csvs,
        rng=rng,
        now=now,
        usage_dates=usage_dates,
        total_lines=total_lines,
        family_count=family_count,
        app_ids=app_ids,
        value_scale=value_scale,
    )

    csvs.close()
    write_load_sql(out_dir)
    write_verify_sql(out_dir, n, now, one_gb_scaled)

    print(f"Generated profile directory: {out_dir}")
    print(f"General users: {n}")
    print(f"Admins: {ADMIN_COUNT}")
    print(f"Families: {family_count}")
    print(f"Lines: {total_lines}")
    print(f"Current month usage days: {usage_days} ({usage_dates[0]} ~ {usage_dates[-1]})")
    print(f"Value scale: {value_scale}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate pooli test-data CSV files")
    parser.add_argument("--n", type=int, default=DEFAULT_N, help="general user count (default: 1000000)")
    parser.add_argument("--output-root", type=Path, default=Path("scripts/test-data/output"), help="output root directory")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED, help="random seed")
    parser.add_argument("--usage-days", type=int, default=DEFAULT_USAGE_DAYS, help="number of usage days in current month")
    parser.add_argument("--value-scale", type=float, default=1.0, help="byte value scale (e.g. 0.5 for half)")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    generate(
        n=args.n,
        output_root=args.output_root,
        seed=args.seed,
        now=DEFAULT_NOW,
        usage_days=args.usage_days,
        value_scale=args.value_scale,
    )


if __name__ == "__main__":
    main()
