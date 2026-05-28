#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# Scenario A verifier: 100-line burst stress consistency
# -----------------------------------------------------------------------------
# Data source: MySQL TRAFFIC_DEDUCT_DONE
# Checks:
# - per-line request count is deterministic
# - final_status=FAILED is zero
# - G1/G2/G3/G6 per-line deducted_sum is exact
# - G4 per-family deducted_sum is exact (50MB)
# - G5 has HIT_APP_SPEED occurrence and no over-deduct
# -----------------------------------------------------------------------------

ROOT_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: env file not found: $ENV_FILE"
  exit 1
fi

if ! command -v mysql >/dev/null 2>&1; then
  echo "ERROR: mysql client is required."
  exit 1
fi

load_env_file() {
  local env_path="$1"
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "${line//[[:space:]]/}" ]] && continue
    [[ "${line:0:1}" == "#" ]] && continue

    if [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      local key="${BASH_REMATCH[1]}"
      local value="${BASH_REMATCH[2]}"
      value="${value%$'\r'}"

      if [[ "$value" =~ ^\"(.*)\"$ ]]; then
        value="${BASH_REMATCH[1]}"
      elif [[ "$value" =~ ^\'(.*)\'$ ]]; then
        value="${BASH_REMATCH[1]}"
      fi

      export "$key=$value"
    fi
  done < "$env_path"
}

load_env_file "$ENV_FILE"

if [[ -z "${DB_URL:-}" || -z "${DB_USERNAME:-}" || -z "${DB_PASSWORD:-}" ]]; then
  echo "ERROR: DB_URL / DB_USERNAME / DB_PASSWORD must be set in $ENV_FILE"
  exit 1
fi

DB_URL_NO_PREFIX="${DB_URL#jdbc:mysql://}"
DB_HOST_PORT_DB="${DB_URL_NO_PREFIX%%\?*}"
DB_HOST_PORT="${DB_HOST_PORT_DB%%/*}"
DB_NAME="${DB_HOST_PORT_DB#*/}"
DB_HOST="${DB_HOST_PORT%%:*}"
DB_PORT_PART="${DB_HOST_PORT#*:}"
DB_PORT="${DB_PORT_PART:-3306}"
if [[ "$DB_HOST_PORT" == "$DB_PORT_PART" ]]; then
  DB_PORT="3306"
fi

mysql_eval() {
  local sql="$1"
  MYSQL_PWD="$DB_PASSWORD" mysql \
    --default-character-set=utf8mb4 \
    --batch --skip-column-names \
    -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" "$DB_NAME" \
    -e "$sql"
}

ONE_MB=1048576
TOTAL_ATTEMPT_MB_PER_LINE=50
TOTAL_ATTEMPT_BYTES=$((TOTAL_ATTEMPT_MB_PER_LINE * ONE_MB))

LINE_START=1
LINE_END=100
G4_FAMILY_START=16
G4_FAMILY_END=19
G5_LINE_START=77
G5_LINE_END=88
VERIFY_WAIT_TIMEOUT_SECONDS="${VERIFY_WAIT_TIMEOUT_SECONDS:-180}"

line_group_name() {
  local line_id="$1"
  if (( line_id <= 20 )); then
    echo "G1_NO_RESTRICTION"
    return
  fi
  if (( line_id <= 40 )); then
    echo "G2_LINE_DAILY_20MB"
    return
  fi
  if (( line_id <= 60 )); then
    echo "G3_APP2_DAILY_5MB"
    return
  fi
  if (( line_id <= 76 )); then
    echo "G4_SHARED_ONLY_APP3"
    return
  fi
  if (( line_id <= 88 )); then
    echo "G5_APP2_SPEED_1MBPS"
    return
  fi
  echo "G6_APP4_DAILY_8MB"
}

family_id_of_line() {
  local line_id="$1"
  echo $(( (line_id - 1) / 4 + 1 ))
}

calc_request_count_for_line() {
  local line_id="$1"
  local state remaining req next_chunk

  state=$(( (line_id * 1103515245 + 12345) & 0xFFFFFFFF ))
  remaining=$TOTAL_ATTEMPT_MB_PER_LINE
  req=0

  while (( remaining > 0 )); do
    if (( remaining <= 3 )); then
      req=$((req + 1))
      remaining=0
      continue
    fi

    state=$(( (1664525 * state + 1013904223) & 0xFFFFFFFF ))
    next_chunk=$(( (state * 3) / 4294967296 + 1 ))

    req=$((req + 1))
    remaining=$((remaining - next_chunk))
  done

  echo "$req"
}

expected_exact_deduct_for_group() {
  local group_name="$1"
  case "$group_name" in
    G1_NO_RESTRICTION)
      echo $((50 * ONE_MB))
      ;;
    G2_LINE_DAILY_20MB)
      echo $((20 * ONE_MB))
      ;;
    G3_APP2_DAILY_5MB)
      echo $((5 * ONE_MB))
      ;;
    G6_APP4_DAILY_8MB)
      echo $((8 * ONE_MB))
      ;;
    *)
      echo -1
      ;;
  esac
}

echo "==============================================="
echo "Scenario A Verifier (Burst Stress)"
echo "mysql_db                : $DB_NAME"
echo "line_scope              : $LINE_START~$LINE_END"
echo "==============================================="

declare -a MONGO_REQ_CNT
declare -a MONGO_DEDUCTED_SUM
declare -a G4_FAMILY_DEDUCTED_SUM

TOTAL_EXPECTED_REQUESTS=0
for line_id in $(seq "$LINE_START" "$LINE_END"); do
  TOTAL_EXPECTED_REQUESTS=$((TOTAL_EXPECTED_REQUESTS + $(calc_request_count_for_line "$line_id")))
done

# done-log는 비동기 소비 완료 후 기록되므로 기대 건수까지 잠시 대기합니다.
wait_deadline=$(( $(date +%s) + VERIFY_WAIT_TIMEOUT_SECONDS ))
while true; do
  current_count="$(mysql_eval "SELECT COUNT(*) FROM TRAFFIC_DEDUCT_DONE WHERE line_id BETWEEN ${LINE_START} AND ${LINE_END};")"
  current_count="${current_count//[[:space:]]/}"
  if [[ "$current_count" =~ ^[0-9]+$ ]] && (( current_count >= TOTAL_EXPECTED_REQUESTS )); then
    break
  fi
  if (( $(date +%s) >= wait_deadline )); then
    break
  fi
  sleep 1
done

total_count="$(mysql_eval "SELECT COUNT(*) FROM TRAFFIC_DEDUCT_DONE WHERE line_id BETWEEN ${LINE_START} AND ${LINE_END};")"
failed_count="$(mysql_eval "SELECT COUNT(*) FROM TRAFFIC_DEDUCT_DONE WHERE line_id BETWEEN ${LINE_START} AND ${LINE_END} AND final_status = 'FAILED';")"
g5_hit_app_speed_count="$(mysql_eval "SELECT COUNT(*) FROM TRAFFIC_DEDUCT_DONE WHERE line_id BETWEEN ${G5_LINE_START} AND ${G5_LINE_END} AND last_lua_status = 'HIT_APP_SPEED';")"
app_id_mismatch_count="$(mysql_eval "SELECT COUNT(*) FROM TRAFFIC_DEDUCT_DONE WHERE line_id BETWEEN ${LINE_START} AND ${LINE_END} AND app_id != CASE WHEN line_id <= 40 THEN 1 WHEN line_id <= 60 THEN 2 WHEN line_id <= 76 THEN 3 WHEN line_id <= 88 THEN 2 ELSE 4 END;")"

total_count="${total_count//[[:space:]]/}"
failed_count="${failed_count//[[:space:]]/}"
g5_hit_app_speed_count="${g5_hit_app_speed_count//[[:space:]]/}"
app_id_mismatch_count="${app_id_mismatch_count//[[:space:]]/}"

# done-log은 비동기 소비 완료 이후에도 마지막 레코드가 늦게 반영될 수 있으므로,
# 대기 루프가 끝난 뒤 최신 스냅샷으로 라인별 집계를 다시 읽습니다.
while IFS=$'\t' read -r agg_line_id agg_req_cnt agg_deducted_sum; do
  [[ -z "${agg_line_id:-}" ]] && continue
  MONGO_REQ_CNT[$agg_line_id]="${agg_req_cnt:-0}"
  MONGO_DEDUCTED_SUM[$agg_line_id]="${agg_deducted_sum:-0}"
done < <(
  mysql_eval "SELECT line_id, COUNT(*), CAST(IFNULL(SUM(deducted_individual_bytes + deducted_shared_bytes + deducted_qos_bytes), 0) AS SIGNED) FROM TRAFFIC_DEDUCT_DONE WHERE line_id BETWEEN ${LINE_START} AND ${LINE_END} GROUP BY line_id ORDER BY line_id;"
)

fail_count=0

echo "- done-log total_count    : $total_count (expected=$TOTAL_EXPECTED_REQUESTS)"
echo "- done-log failed_count   : $failed_count (expected=0)"
echo "- G5 HIT_APP_SPEED count  : $g5_hit_app_speed_count (expected>0)"
echo "- app_id mismatch count   : $app_id_mismatch_count (expected=0)"
echo "- verify wait timeout(s)  : $VERIFY_WAIT_TIMEOUT_SECONDS"

if [[ "$total_count" != "$TOTAL_EXPECTED_REQUESTS" ]]; then
  echo "  result: FAIL (done-log total_count mismatch)"
  fail_count=$((fail_count + 1))
fi

if [[ "$failed_count" != "0" ]]; then
  echo "  result: FAIL (FAILED done-log exists)"
  fail_count=$((fail_count + 1))
fi

if ! [[ "$g5_hit_app_speed_count" =~ ^[0-9]+$ ]] || (( g5_hit_app_speed_count <= 0 )); then
  echo "  result: FAIL (HIT_APP_SPEED not observed in G5)"
  fail_count=$((fail_count + 1))
fi

if [[ "$app_id_mismatch_count" != "0" ]]; then
  echo "  result: FAIL (unexpected app_id detected in done-log)"
  fail_count=$((fail_count + 1))
fi

echo
echo "[per-line] req_cnt / deducted_sum"
printf "%-6s %-20s %-10s %-10s %-16s %-16s %-8s\n" \
  "line" "group" "req_exp" "req_act" "deduct_act" "expected" "result"

for line_id in $(seq "$LINE_START" "$LINE_END"); do
  expected_req="$(calc_request_count_for_line "$line_id")"
  actual_req="${MONGO_REQ_CNT[$line_id]:-0}"
  actual_deduct="${MONGO_DEDUCTED_SUM[$line_id]:-0}"
  group_name="$(line_group_name "$line_id")"

  req_ok=1
  if [[ "$actual_req" != "$expected_req" ]]; then
    req_ok=0
  fi

  if [[ "$group_name" == "G4_SHARED_ONLY_APP3" ]]; then
    family_id="$(family_id_of_line "$line_id")"
    G4_FAMILY_DEDUCTED_SUM[$family_id]=$(( ${G4_FAMILY_DEDUCTED_SUM[$family_id]:-0} + actual_deduct ))

    if (( req_ok == 1 )); then
      printf "%-6s %-20s %-10s %-10s %-16s %-16s %-8s\n" \
        "$line_id" "$group_name" "$expected_req" "$actual_req" "$actual_deduct" "family_total_only" "PASS"
    else
      printf "%-6s %-20s %-10s %-10s %-16s %-16s %-8s\n" \
        "$line_id" "$group_name" "$expected_req" "$actual_req" "$actual_deduct" "family_total_only" "FAIL"
      fail_count=$((fail_count + 1))
    fi
    continue
  fi

  if [[ "$group_name" == "G5_APP2_SPEED_1MBPS" ]]; then
    deduct_ok=1
    if (( actual_deduct < 0 || actual_deduct > TOTAL_ATTEMPT_BYTES )); then
      deduct_ok=0
    fi

    if (( req_ok == 1 && deduct_ok == 1 )); then
      printf "%-6s %-20s %-10s %-10s %-16s %-16s %-8s\n" \
        "$line_id" "$group_name" "$expected_req" "$actual_req" "$actual_deduct" "0..$TOTAL_ATTEMPT_BYTES" "PASS"
    else
      printf "%-6s %-20s %-10s %-10s %-16s %-16s %-8s\n" \
        "$line_id" "$group_name" "$expected_req" "$actual_req" "$actual_deduct" "0..$TOTAL_ATTEMPT_BYTES" "FAIL"
      fail_count=$((fail_count + 1))
    fi
    continue
  fi

  expected_exact="$(expected_exact_deduct_for_group "$group_name")"
  if (( req_ok == 1 )) && (( actual_deduct == expected_exact )); then
    printf "%-6s %-20s %-10s %-10s %-16s %-16s %-8s\n" \
      "$line_id" "$group_name" "$expected_req" "$actual_req" "$actual_deduct" "$expected_exact" "PASS"
  else
    printf "%-6s %-20s %-10s %-10s %-16s %-16s %-8s\n" \
      "$line_id" "$group_name" "$expected_req" "$actual_req" "$actual_deduct" "$expected_exact" "FAIL"
    fail_count=$((fail_count + 1))
  fi
done

echo
echo "[group-level] G4 family aggregated deducted_sum"
printf "%-8s %-14s %-14s %-8s\n" "family" "deducted_sum" "expected" "result"
for family_id in $(seq "$G4_FAMILY_START" "$G4_FAMILY_END"); do
  family_deduct="${G4_FAMILY_DEDUCTED_SUM[$family_id]:-0}"
  expected_family=$((50 * ONE_MB))
  if (( family_deduct == expected_family )); then
    printf "%-8s %-14s %-14s %-8s\n" "$family_id" "$family_deduct" "$expected_family" "PASS"
  else
    printf "%-8s %-14s %-14s %-8s\n" "$family_id" "$family_deduct" "$expected_family" "FAIL"
    fail_count=$((fail_count + 1))
  fi
done

echo
echo "-----------------------------------------------"
if (( fail_count == 0 )); then
  echo "SCENARIO A RESULT: PASS"
else
  echo "SCENARIO A RESULT: FAIL (${fail_count} mismatches)"
fi
echo "-----------------------------------------------"

if (( fail_count > 0 )); then
  exit 1
fi
