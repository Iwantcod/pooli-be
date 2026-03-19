#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# Scenario A verifier: 100-line burst stress consistency
# -----------------------------------------------------------------------------
# Data source: MongoDB traffic_deduct_done_log
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

if ! command -v mongosh >/dev/null 2>&1; then
  echo "ERROR: mongosh is required."
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

MONGO_URI_EFFECTIVE="${LOCAL_MONGO_URI:-${MONGO_URI:-}}"
MONGO_DB_NAME="${MONGO_DB_NAME:-pooli}"

mongo_eval() {
  local js="$1"
  if [[ -n "$MONGO_URI_EFFECTIVE" ]]; then
    mongosh "$MONGO_URI_EFFECTIVE" --quiet --eval "$js"
  else
    mongosh --quiet --eval "$js"
  fi
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
echo "mongo_db                : $MONGO_DB_NAME"
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
  current_count="$(mongo_eval "const d=db.getSiblingDB('${MONGO_DB_NAME}'); print(d.traffic_deduct_done_log.countDocuments({ line_id: { \$gte: ${LINE_START}, \$lte: ${LINE_END} } }));")"
  current_count="${current_count//[[:space:]]/}"
  if [[ "$current_count" =~ ^[0-9]+$ ]] && (( current_count >= TOTAL_EXPECTED_REQUESTS )); then
    break
  fi
  if (( $(date +%s) >= wait_deadline )); then
    break
  fi
  sleep 1
done

total_count="$(mongo_eval "const d=db.getSiblingDB('${MONGO_DB_NAME}'); print(d.traffic_deduct_done_log.countDocuments({ line_id: { \$gte: ${LINE_START}, \$lte: ${LINE_END} } }));")"
failed_count="$(mongo_eval "const d=db.getSiblingDB('${MONGO_DB_NAME}'); print(d.traffic_deduct_done_log.countDocuments({ line_id: { \$gte: ${LINE_START}, \$lte: ${LINE_END} }, final_status: 'FAILED' }));")"
g5_hit_app_speed_count="$(mongo_eval "const d=db.getSiblingDB('${MONGO_DB_NAME}'); print(d.traffic_deduct_done_log.countDocuments({ line_id: { \$gte: ${G5_LINE_START}, \$lte: ${G5_LINE_END} }, last_lua_status: 'HIT_APP_SPEED' }));")"
app_id_mismatch_count="$(mongo_eval "const d=db.getSiblingDB('${MONGO_DB_NAME}'); const r=d.traffic_deduct_done_log.aggregate([{ \$match: { line_id: { \$gte: ${LINE_START}, \$lte: ${LINE_END} } } }, { \$project: { app_id: 1, expected_app: { \$switch: { branches: [ { case: { \$lte: ['\$line_id', 40] }, then: 1 }, { case: { \$lte: ['\$line_id', 60] }, then: 2 }, { case: { \$lte: ['\$line_id', 76] }, then: 3 }, { case: { \$lte: ['\$line_id', 88] }, then: 2 } ], default: 4 } } } }, { \$match: { \$expr: { \$ne: ['\$app_id', '\$expected_app'] } } }, { \$count: 'cnt' }]).toArray(); print(r.length === 0 ? 0 : Number(r[0].cnt));")"

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
  mongo_eval "const d=db.getSiblingDB('${MONGO_DB_NAME}'); d.traffic_deduct_done_log.aggregate([{ \$match: { line_id: { \$gte: ${LINE_START}, \$lte: ${LINE_END} } } }, { \$group: { _id: '\$line_id', req_cnt: { \$sum: 1 }, deducted_sum: { \$sum: '\$deducted_total_bytes' } } }, { \$sort: { _id: 1 } }]).forEach(doc => print(Number(doc._id) + '\\t' + Number(doc.req_cnt) + '\\t' + Number(doc.deducted_sum)));"
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
