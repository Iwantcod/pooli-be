#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# Scenario B verifier: G5 speed exact consistency (line 77~88)
# -----------------------------------------------------------------------------
# Data source: MongoDB traffic_deduct_done_log
# Checks:
# - per-line request count is deterministic
# - final_status=FAILED is zero
# - per-line deducted_sum == sum(min(chunk_i, 125000))
# -----------------------------------------------------------------------------

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
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
SPEED_LIMIT_BYTES_PER_SECOND=125000
LINE_START=77
LINE_END=88
VERIFY_WAIT_TIMEOUT_SECONDS="${VERIFY_WAIT_TIMEOUT_SECONDS:-180}"

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

calc_speed_exact_deduct_for_line() {
  local line_id="$1"
  local state remaining next_chunk_mb chunk_bytes deducted_sum

  state=$(( (line_id * 1103515245 + 12345) & 0xFFFFFFFF ))
  remaining=$TOTAL_ATTEMPT_MB_PER_LINE
  deducted_sum=0

  while (( remaining > 0 )); do
    if (( remaining <= 3 )); then
      next_chunk_mb=$remaining
      remaining=0
    else
      state=$(( (1664525 * state + 1013904223) & 0xFFFFFFFF ))
      next_chunk_mb=$(( (state * 3) / 4294967296 + 1 ))
      remaining=$((remaining - next_chunk_mb))
    fi

    chunk_bytes=$((next_chunk_mb * ONE_MB))
    if (( chunk_bytes > SPEED_LIMIT_BYTES_PER_SECOND )); then
      deducted_sum=$((deducted_sum + SPEED_LIMIT_BYTES_PER_SECOND))
    else
      deducted_sum=$((deducted_sum + chunk_bytes))
    fi
  done

  echo "$deducted_sum"
}

echo "==============================================="
echo "Scenario B Verifier (Speed Exact)"
echo "mongo_db                : $MONGO_DB_NAME"
echo "line_scope              : $LINE_START~$LINE_END"
echo "speed_limit             : ${SPEED_LIMIT_BYTES_PER_SECOND}B/s"
echo "==============================================="

declare -a MONGO_REQ_CNT
declare -a MONGO_DEDUCTED_SUM

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
app_id_mismatch_count="$(mongo_eval "const d=db.getSiblingDB('${MONGO_DB_NAME}'); print(d.traffic_deduct_done_log.countDocuments({ line_id: { \$gte: ${LINE_START}, \$lte: ${LINE_END} }, app_id: { \$ne: 2 } }));")"

total_count="${total_count//[[:space:]]/}"
failed_count="${failed_count//[[:space:]]/}"
app_id_mismatch_count="${app_id_mismatch_count//[[:space:]]/}"

# done-log 비동기 반영 지연으로 stale 집계를 읽지 않도록,
# 대기 루프 이후에 라인별 집계를 다시 조회합니다.
while IFS=$'\t' read -r agg_line_id agg_req_cnt agg_deducted_sum; do
  [[ -z "${agg_line_id:-}" ]] && continue
  MONGO_REQ_CNT[$agg_line_id]="${agg_req_cnt:-0}"
  MONGO_DEDUCTED_SUM[$agg_line_id]="${agg_deducted_sum:-0}"
done < <(
  mongo_eval "const d=db.getSiblingDB('${MONGO_DB_NAME}'); d.traffic_deduct_done_log.aggregate([{ \$match: { line_id: { \$gte: ${LINE_START}, \$lte: ${LINE_END} } } }, { \$group: { _id: '\$line_id', req_cnt: { \$sum: 1 }, deducted_sum: { \$sum: '\$deducted_total_bytes' } } }, { \$sort: { _id: 1 } }]).forEach(doc => print(Number(doc._id) + '\\t' + Number(doc.req_cnt) + '\\t' + Number(doc.deducted_sum)));"
)

fail_count=0

echo "- done-log total_count   : $total_count (expected=$TOTAL_EXPECTED_REQUESTS)"
echo "- done-log failed_count  : $failed_count (expected=0)"
echo "- app_id mismatch count  : $app_id_mismatch_count (expected=0)"
echo "- verify wait timeout(s) : $VERIFY_WAIT_TIMEOUT_SECONDS"

if [[ "$total_count" != "$TOTAL_EXPECTED_REQUESTS" ]]; then
  echo "  result: FAIL (done-log total_count mismatch)"
  fail_count=$((fail_count + 1))
fi

if [[ "$failed_count" != "0" ]]; then
  echo "  result: FAIL (FAILED done-log exists)"
  fail_count=$((fail_count + 1))
fi

if [[ "$app_id_mismatch_count" != "0" ]]; then
  echo "  result: FAIL (unexpected app_id detected in done-log)"
  fail_count=$((fail_count + 1))
fi

echo
echo "[per-line] req_cnt / deducted_sum"
printf "%-6s %-10s %-10s %-16s %-16s %-8s\n" \
  "line" "req_exp" "req_act" "deduct_act" "deduct_exp" "result"

for line_id in $(seq "$LINE_START" "$LINE_END"); do
  expected_req="$(calc_request_count_for_line "$line_id")"
  expected_deduct="$(calc_speed_exact_deduct_for_line "$line_id")"
  actual_req="${MONGO_REQ_CNT[$line_id]:-0}"
  actual_deduct="${MONGO_DEDUCTED_SUM[$line_id]:-0}"

  if [[ "$actual_req" == "$expected_req" ]] && (( actual_deduct == expected_deduct )); then
    printf "%-6s %-10s %-10s %-16s %-16s %-8s\n" \
      "$line_id" "$expected_req" "$actual_req" "$actual_deduct" "$expected_deduct" "PASS"
  else
    printf "%-6s %-10s %-10s %-16s %-16s %-8s\n" \
      "$line_id" "$expected_req" "$actual_req" "$actual_deduct" "$expected_deduct" "FAIL"
    fail_count=$((fail_count + 1))
  fi
done

echo
echo "-----------------------------------------------"
if (( fail_count == 0 )); then
  echo "SCENARIO B RESULT: PASS"
else
  echo "SCENARIO B RESULT: FAIL (${fail_count} mismatches)"
fi
echo "-----------------------------------------------"

if (( fail_count > 0 )); then
  exit 1
fi
