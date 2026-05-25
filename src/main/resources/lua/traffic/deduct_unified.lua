-- deduct_unified.lua
-- 개인풀 -> 공유풀 -> QoS 순서의 단일 차감 Lua.
--
-- 핵심 계약:
-- 1) 개인/공유 잔량 차감, QoS 처리량 산정, usage counter, dedupe 갱신을 한 원자 구간에서 수행한다.
-- 2) QoS 처리량은 총/앱별 일일 사용량에는 포함하지만 개인/공유 잔량과 월별 공유 사용량에는 반영하지 않는다.
-- 3) 정책 제한으로 target이 0이 된 경우는 HIT_*를, 정책 영향 없이 차감할 수 없는 경우는 NO_BALANCE를 반환한다.
--
-- 전체 흐름:
-- 1) Redis TIME 기준 현재 시각과 응답 JSON 생성 도우미를 준비한다.
-- 2) KEYS/ARGV를 Lua 로컬 변수에 매핑하고 입력값을 검증한다.
-- 3) dedupe에 이미 기록된 처리량을 읽어 이번 호출에서 더 처리할 수 있는 상한을 구한다.
-- 4) 전역 정책/잔량 snapshot이 준비되어 있는지 확인한다.
-- 5) 일일 총량, 앱 일일 한도, 앱 속도 제한 정책을 읽어 처리 대상량과 속도 예약 대상 여부를 정한다.
-- 6) 개인풀 -> 공유풀 -> QoS 순서로 이번 호출의 출처별 처리량을 계산한다.
-- 7) 앱 속도 제한 또는 QoS가 적용되면 회선+앱 단일 예약 키로 처리 완료 시각을 계산한다.
-- 8) 5초 안에 완료될 수 없으면 Redis 상태를 바꾸지 않고 SPEED_LIMIT_TIMEOUT을 반환한다.
-- 9) 예약 가능하면 dedupe/잔량/usage/예약 키를 같은 Lua 원자 구간에서 갱신하고 최종 상태를 반환한다.

-- Redis TIME은 {seconds, microseconds}를 반환한다.
-- Java 시스템 시각과 Redis 시각이 어긋나도 예약 판단이 Redis 내부 기준으로 일관되도록 epoch millis로 변환한다.
local function redis_epoch_millis()
  local redis_time = redis.call("TIME")
  return tonumber(redis_time[1]) * 1000 + math.floor(tonumber(redis_time[2]) / 1000)
end

-- Redis Lua가 Java DTO로 역직렬화할 JSON 응답을 만든다.
-- finishedAtEpochMillis가 명시되지 않은 조기 반환 경로도 null을 반환하지 않도록 Redis 현재 시각을 기본값으로 사용한다.
local function as_json(indiv_deducted, shared_deducted, qos_deducted, status, finished_at_epoch_millis)
  return cjson.encode({
    indivDeducted = indiv_deducted,
    sharedDeducted = shared_deducted,
    qosDeducted = qos_deducted,
    finishedAtEpochMillis = finished_at_epoch_millis or redis_epoch_millis(),
    status = status
  })
end

-- 전역 정책 활성화 hash의 value 필드가 1인지 확인한다.
-- policy key가 비어 있으면 비활성으로 취급해 잘못된 정책 적용을 막는다.
local function is_policy_enabled(policy_key)
  if not policy_key or policy_key == "" then
    return false
  end
  return tonumber(redis.call("HGET", policy_key, "value") or "0") == 1
end

-- 차감 Lua가 자체적으로 검사하는 전역 정책 활성화 key가 모두 존재하는지 확인한다.
-- 하나라도 없으면 정책 스냅샷 유실로 보고 Java hydrate 단계가 복구하도록 GLOBAL_POLICY_HYDRATE를 반환한다.
local function has_missing_global_policy_key(...)
  local policy_keys = { ... }
  local idx = 1
  while idx <= #policy_keys do
    local policy_key = policy_keys[idx]
    if not policy_key or policy_key == "" then
      return true
    end
    if redis.call("EXISTS", policy_key) == 0 then
      return true
    end
    idx = idx + 1
  end
  return false
end

-- Redis hash 숫자 필드를 counter 성격의 0 이상 값으로 읽는다.
-- 누락/음수/비숫자는 dedupe/QoS counter 계약에 맞춰 0으로 취급한다.
local function read_non_negative_counter(key, field)
  local value = tonumber(redis.call("HGET", key, field) or "0")
  if not value or value < 0 then
    return 0
  end
  return value
end

local function read_daily_app_usage(key, individual_field, shared_field, qos_field)
  return read_non_negative_counter(key, individual_field)
      + read_non_negative_counter(key, shared_field)
      + read_non_negative_counter(key, qos_field)
end

-- hydrate는 월별 snapshot 준비 여부로 판단한다.
local function is_hash_snapshot_ready(key, required_fields)
  if redis.call("EXISTS", key) == 0 then
    return false
  end
  local idx = 1
  while idx <= #required_fields do
    local field = required_fields[idx]
    local value = redis.call("HGET", key, field)
    if value == false or value == nil then
      return false
    end
    idx = idx + 1
  end
  return true
end

local SPEED_LIMIT_TIMEOUT_MS = 5000
local DEDUPE_PROCESSED_INDIVIDUAL_FIELD = "processed_individual_data"
local DEDUPE_PROCESSED_SHARED_FIELD = "processed_shared_data"
local DEDUPE_PROCESSED_QOS_FIELD = "processed_qos_data"
local DEDUPE_RETRY_FIELD = "retry_count"

-- KEYS
-- 1~2: 잔량 hash, 3~6: 전역 정책 활성화 hash, 7~13: 제한/사용량, 14: 속도/QoS 예약, 15: in-flight dedupe hash, 16: 일별 공유풀 사용량 hash.
-- KEYS[14]는 기존 초 단위 speed bucket이 아니라 lineId+appId 단일 예약 키다.
-- 이 키의 value는 "다음 요청이 시작 기준으로 삼아야 할 완료 가능 시각(epoch millis)"이다.
local individual_remaining_key = KEYS[1]
local shared_remaining_key = KEYS[2]
local policy_shared_key = KEYS[3]
local policy_daily_key = KEYS[4]
local policy_app_data_key = KEYS[5]
local policy_app_speed_key = KEYS[6]
local daily_total_limit_key = KEYS[7]
local daily_total_usage_key = KEYS[8]
local monthly_shared_limit_key = KEYS[9]
local monthly_shared_usage_key = KEYS[10]
local app_data_daily_limit_key = KEYS[11]
local daily_app_usage_key = KEYS[12]
local app_speed_limit_key = KEYS[13]
local qos_speed_limit_next_available_key = KEYS[14]
local dedupe_key = KEYS[15]
local daily_shared_usage_key = KEYS[16]

-- ARGV
-- target_data: 이번 Lua 호출에서 추가 처리할 목표량.
-- api_total_data: 전체 원본 요청량. dedupe 누적량과 비교해 재처리 시 초과 차감을 방지한다.
local target_data = tonumber(ARGV[1])
local app_id = tonumber(ARGV[2])
local daily_expire_at = tonumber(ARGV[3])
local monthly_expire_at = tonumber(ARGV[4])
local whitelist_bypass_flag = tonumber(ARGV[5] or "0")
local api_total_data = tonumber(ARGV[6] or "-1")
local family_id = tonumber(ARGV[7] or "-1")

-- ===== 입력 검증 =====
-- 필수 key/argument가 누락되거나 음수이면 Redis 상태를 변경하지 않고 ERROR를 반환한다.
if not individual_remaining_key or individual_remaining_key == "" then
  return as_json(0, 0, 0, "ERROR")
end
if not shared_remaining_key or shared_remaining_key == "" then
  return as_json(0, 0, 0, "ERROR")
end
if not daily_shared_usage_key or daily_shared_usage_key == "" then
  return as_json(0, 0, 0, "ERROR")
end
if not target_data or target_data < 0 then
  return as_json(0, 0, 0, "ERROR")
end
if not app_id or app_id < 0 then
  return as_json(0, 0, 0, "ERROR")
end
if not family_id or family_id <= 0 then
  return as_json(0, 0, 0, "ERROR")
end
if not daily_expire_at or daily_expire_at <= 0 then
  return as_json(0, 0, 0, "ERROR")
end
if not monthly_expire_at or monthly_expire_at <= 0 then
  return as_json(0, 0, 0, "ERROR")
end
if not qos_speed_limit_next_available_key or qos_speed_limit_next_available_key == "" then
  return as_json(0, 0, 0, "ERROR")
end
if not dedupe_key or dedupe_key == "" then
  return as_json(0, 0, 0, "ERROR")
end
if not api_total_data or api_total_data < 0 then
  return as_json(0, 0, 0, "ERROR")
end

-- ===== 1단계: dedupe 누적 처리량을 기준으로 이번 호출의 처리 상한 결정 =====
-- 이미 처리한 개인/공유/QoS 합계를 원본 api_total_data에서 차감해 이번 호출의 상한을 정한다.
-- reclaim 재처리 중에도 총 처리량이 원본 요청량을 넘지 않게 하는 방어선이다.
local processed_individual = read_non_negative_counter(dedupe_key, DEDUPE_PROCESSED_INDIVIDUAL_FIELD)
local processed_shared = read_non_negative_counter(dedupe_key, DEDUPE_PROCESSED_SHARED_FIELD)
local processed_qos = read_non_negative_counter(dedupe_key, DEDUPE_PROCESSED_QOS_FIELD)
local processed_data = processed_individual + processed_shared + processed_qos
local remaining_quota = math.max(0, api_total_data - processed_data)
if remaining_quota <= 0 then
  return as_json(0, 0, 0, "OK")
end
target_data = math.min(target_data, remaining_quota)

-- ===== 2단계: 정책 snapshot 준비 여부 확인 =====
-- 정책 활성화 여부 key가 유실되면 제한값을 임의로 우회하지 않고 Java hydrate 단계로 넘긴다.
if has_missing_global_policy_key(
  policy_shared_key,
  policy_daily_key,
  policy_app_data_key,
  policy_app_speed_key
) then
  return as_json(0, 0, 0, "GLOBAL_POLICY_HYDRATE")
end

-- ===== 3단계: 개인 잔량 snapshot 준비 여부 확인 =====
-- 개인 snapshot은 amount와 qos가 함께 준비되어야 한다.
if not is_hash_snapshot_ready(individual_remaining_key, { "amount", "qos" }) then
  return as_json(0, 0, 0, "HYDRATE_INDIVIDUAL")
end
local raw_individual_amount = redis.call("HGET", individual_remaining_key, "amount")
local individual_amount = tonumber(raw_individual_amount)
if not individual_amount or individual_amount < -1 then
  return as_json(0, 0, 0, "ERROR")
end
local individual_unlimited = individual_amount == -1

local whitelist_bypass = whitelist_bypass_flag == 1
local app_member = tostring(math.floor(app_id))
local app_usage_individual_field = "app:" .. app_member .. ":individual"
local app_usage_shared_field = "app:" .. app_member .. ":shared"
local app_usage_qos_field = "app:" .. app_member .. ":qos"
local app_limit_field = "limit:" .. app_member
local app_speed_field = "speed:" .. app_member

-- ===== 4단계: 정책 한도를 반영해 처리 대상량 계산 =====
-- policy_target: 총 사용량/앱별 일일 사용량/QoS까지 포함해 처리 가능한 목표량.
-- pool_target: 개인/공유 잔량에서 실제로 차감할 수 있는 목표량. 앱 속도 제한은 pool_target에만 적용한다.
-- final_status: 정책 cap 또는 QoS/잔량 부족 상태를 Java에 전달하기 위한 Lua 상태.
local final_status = "OK"
local policy_target = target_data
local pool_target = target_data
local policy_affected = false
local app_speed_limited = false
local app_speed_bytes_per_sec = nil

if not whitelist_bypass then
  -- 일일 총량 제한: 개인/공유/QoS 전체 처리량에 적용한다.
  if is_policy_enabled(policy_daily_key) then
    local daily_limit = tonumber(redis.call("HGET", daily_total_limit_key, "value") or "-1")
    if daily_limit >= 0 then
      local daily_used = tonumber(redis.call("GET", daily_total_usage_key) or "0")
      local daily_remaining = math.max(0, daily_limit - daily_used)
      local before_daily = policy_target
      policy_target = math.min(policy_target, daily_remaining)
      pool_target = math.min(pool_target, daily_remaining)
      if policy_target <= 0 then
        return as_json(0, 0, 0, "HIT_DAILY_LIMIT")
      end
      if policy_target < before_daily then
        final_status = "HIT_DAILY_LIMIT"
        policy_affected = true
      end
    end
  end

  -- 앱별 일일 데이터 제한: 개인/공유/QoS 전체 처리량에 적용한다.
  if is_policy_enabled(policy_app_data_key) then
    local app_daily_limit = tonumber(redis.call("HGET", app_data_daily_limit_key, app_limit_field) or "-1")
    if app_daily_limit >= 0 then
      local app_daily_used = read_daily_app_usage(
        daily_app_usage_key,
        app_usage_individual_field,
        app_usage_shared_field,
        app_usage_qos_field
      )
      local app_daily_remaining = math.max(0, app_daily_limit - app_daily_used)
      local before_app_daily = policy_target
      policy_target = math.min(policy_target, app_daily_remaining)
      pool_target = math.min(pool_target, app_daily_remaining)
      if policy_target <= 0 then
        return as_json(0, 0, 0, "HIT_APP_DAILY_LIMIT")
      end
      if policy_target < before_app_daily then
        final_status = "HIT_APP_DAILY_LIMIT"
        policy_affected = true
      end
    end
  end

  -- 앱 속도 제한 정책은 처리량을 즉시 깎지 않는다.
  -- 필드가 존재하고 값이 0 이상이면 "이번 요청 전체가 몇 ms 뒤 완료될 수 있는지" 예약 계산 대상으로 표시한다.
  -- 값 의미:
  -- - 필드 없음: 이 앱에는 속도 정책 없음
  -- - -1: 무제한
  -- - 0: 처리 불가이므로 예약 단계에서 timeout
  -- - 양수: Bytes/sec
  if is_policy_enabled(policy_app_speed_key) then
    local raw_app_speed_limit = redis.call("HGET", app_speed_limit_key, app_speed_field)
    if raw_app_speed_limit ~= false and raw_app_speed_limit ~= nil then
      local parsed_app_speed_limit = tonumber(raw_app_speed_limit)
      if not parsed_app_speed_limit or parsed_app_speed_limit < -1 then
        return as_json(0, 0, 0, "ERROR")
      end
      if parsed_app_speed_limit >= 0 then
        app_speed_limited = true
        app_speed_bytes_per_sec = parsed_app_speed_limit
      end
    end
  end
end

-- ===== 5단계: 개인풀 처리량 계산 =====
-- 개인풀은 pool_target 범위 안에서 먼저 차감한다. amount=-1이면 잔량 감소 없이 전체 pool_target을 처리한다.
local indiv_deducted = individual_unlimited and pool_target or math.min(individual_amount, pool_target)
local remaining_pool_target = math.max(0, pool_target - indiv_deducted)

-- ===== 6단계: 공유풀 처리량 계산 =====
-- 개인풀 처리 후 남은 pool_target이 있을 때만 공유풀 제한/잔량을 확인한다.
local shared_target = remaining_pool_target
if not whitelist_bypass and shared_target > 0 and is_policy_enabled(policy_shared_key) then
  -- 월별 공유풀 제한은 공유풀 차감량에만 적용한다. QoS 처리량에는 적용하지 않는다.
  local monthly_limit = tonumber(redis.call("HGET", monthly_shared_limit_key, "value") or "-1")
  if monthly_limit >= 0 then
    local monthly_used = tonumber(redis.call("GET", monthly_shared_usage_key) or "0")
    local monthly_remaining = math.max(0, monthly_limit - monthly_used)
    local before_monthly = shared_target
    shared_target = math.min(shared_target, monthly_remaining)
    if shared_target < before_monthly then
      final_status = "HIT_MONTHLY_SHARED_LIMIT"
      policy_affected = true
    end
  end
end

local shared_amount = 0
local shared_unlimited = false
if shared_target > 0 then
  if not is_hash_snapshot_ready(shared_remaining_key, { "amount" }) then
    return as_json(0, 0, 0, "HYDRATE_SHARED")
  end
  local raw_shared_amount = redis.call("HGET", shared_remaining_key, "amount")
  shared_amount = tonumber(raw_shared_amount)
  if not shared_amount or shared_amount < -1 then
    return as_json(0, 0, 0, "ERROR")
  end
  shared_unlimited = shared_amount == -1
end
local shared_deducted = shared_unlimited and shared_target or math.min(shared_amount, shared_target)

-- ===== 7단계: QoS 처리량 계산 =====
-- QoS는 개인/공유 잔량으로 처리하지 못한 policy_target 잔여량을 대상으로 한다.
-- `qos` 필드는 잔량이 아니라 Redis 저장 규격(Bytes/sec)의 QoS 처리 속도로 해석한다.
-- QoS 속도가 0이거나 없으면 QoS로는 처리하지 않는다.
local qos_target = math.max(0, policy_target - indiv_deducted - shared_deducted)
local qos_bytes_per_sec = read_non_negative_counter(individual_remaining_key, "qos")
local qos_deducted = 0
if qos_target > 0 and qos_bytes_per_sec > 0 then
  qos_deducted = qos_target
end

-- ===== 8단계: 실제 처리량이 없는 조기 종료 상태 결정 =====
-- 실제 차감/처리량이 0이면 Redis 쓰기 없이 종료한다.
-- 정책 cap이 target을 줄인 결과라면 HIT_*를 유지하고, 정책 영향이 없다면 잔량 부족으로 NO_BALANCE를 반환한다.
local total_deducted = indiv_deducted + shared_deducted + qos_deducted
if total_deducted <= 0 then
  if policy_affected and final_status ~= "OK" then
    return as_json(0, 0, 0, final_status)
  end
  return as_json(0, 0, 0, "NO_BALANCE")
end

-- ===== 9단계: 속도 제한/QoS 처리 완료 시각 예약 =====
-- 예약 계산은 잔량/usage/dedupe를 쓰기 전에 끝낸다.
-- 그래야 timeout인 경우 이 요청이 Redis 상태에 어떤 흔적도 남기지 않는다.
local speed_limited_bytes = 0
local effective_bytes_per_sec = nil
-- 앱 속도 제한과 QoS가 동시에 적용되면 개인/공유/QoS 처리량 전체를 더 작은 속도값으로 직렬화한다.
if app_speed_limited and qos_deducted > 0 then
  speed_limited_bytes = total_deducted
  effective_bytes_per_sec = math.min(app_speed_bytes_per_sec, qos_bytes_per_sec)
elseif app_speed_limited then
  -- 앱 속도 제한만 있으면 이번 요청의 전체 처리량이 앱 속도 제한 대상이다.
  speed_limited_bytes = total_deducted
  effective_bytes_per_sec = app_speed_bytes_per_sec
elseif qos_deducted > 0 then
  -- QoS만 있으면 QoS로 처리되는 바이트만 예약 대상이다.
  speed_limited_bytes = qos_deducted
  effective_bytes_per_sec = qos_bytes_per_sec
end

local finished_at_epoch_millis = redis_epoch_millis()
if speed_limited_bytes > 0 then
  -- 속도값이 0이면 duration을 계산할 수 없으므로 즉시 timeout으로 기각한다.
  if not effective_bytes_per_sec or effective_bytes_per_sec <= 0 then
    return as_json(0, 0, 0, "SPEED_LIMIT_TIMEOUT", finished_at_epoch_millis)
  end

  -- 예약 키가 이미 있으면 이전 요청의 완료 예정 시각 이후에 이번 요청을 이어 붙인다.
  -- 예약 키가 없거나 과거 시각이면 Redis 현재 시각부터 계산한다.
  local reservation_raw = redis.call("GET", qos_speed_limit_next_available_key)
  local reservation_base = finished_at_epoch_millis
  if reservation_raw ~= false and reservation_raw ~= nil then
    local parsed_reservation = tonumber(reservation_raw)
    if not parsed_reservation or parsed_reservation < 0 then
      return as_json(0, 0, 0, "ERROR", finished_at_epoch_millis)
    end
    reservation_base = math.max(parsed_reservation, finished_at_epoch_millis)
  end

  -- durationMs = ceil(requestBytes * 1000 / bytesPerSec)
  -- finishedAtMs = max(nextAvailableAtMs, nowMs) + durationMs
  local duration_ms = math.ceil(speed_limited_bytes * 1000 / effective_bytes_per_sec)
  local reserved_finished_at_epoch_millis = reservation_base + duration_ms
  -- finishedAtMs가 Redis 현재 시각 기준 5초를 넘으면 전체 요청을 처리하지 않는다.
  -- 이 return은 dedupe 생성, 잔량 차감, usage 증가, 예약 키 갱신보다 앞에 있다.
  if reserved_finished_at_epoch_millis > finished_at_epoch_millis + SPEED_LIMIT_TIMEOUT_MS then
    return as_json(0, 0, 0, "SPEED_LIMIT_TIMEOUT", finished_at_epoch_millis)
  end

  -- 5초 안에 끝날 수 있으면 예약 키를 새 완료 예정 시각으로 갱신한다.
  -- 이후 같은 lineId+appId 요청은 이 시각을 기준으로 다시 이어 붙는다.
  finished_at_epoch_millis = reserved_finished_at_epoch_millis
  redis.call("SET", qos_speed_limit_next_available_key, finished_at_epoch_millis)
  if app_speed_limited then
    final_status = "HIT_APP_SPEED"
  end
end

-- ===== 10단계: in-flight dedupe 초기화 =====
-- timeout은 상태 변경 없이 반환해야 하므로 예약 검증이 끝난 뒤에만 dedupe hash를 생성/보강한다.
if redis.call("EXISTS", dedupe_key) == 0 then
  redis.call(
    "HSET",
    dedupe_key,
    DEDUPE_PROCESSED_INDIVIDUAL_FIELD, 0,
    DEDUPE_PROCESSED_SHARED_FIELD, 0,
    DEDUPE_PROCESSED_QOS_FIELD, 0,
    DEDUPE_RETRY_FIELD, 0
  )
else
  redis.call("HSETNX", dedupe_key, DEDUPE_PROCESSED_QOS_FIELD, 0)
end

-- ===== 11단계: 원자적 상태 갱신 =====
-- 개인풀 무제한 sentinel(-1)은 감소시키지 않고 처리량만 dedupe에 기록한다.
if indiv_deducted > 0 and not individual_unlimited then
  redis.call("HINCRBY", individual_remaining_key, "amount", -indiv_deducted)
end
if indiv_deducted > 0 then
  redis.call("HINCRBY", dedupe_key, DEDUPE_PROCESSED_INDIVIDUAL_FIELD, indiv_deducted)
end

-- 공유풀 무제한 sentinel(-1)이 있으면 잔량은 감소시키지 않고 사용량/dedupe만 기록한다.
if shared_deducted > 0 and not shared_unlimited then
  redis.call("HINCRBY", shared_remaining_key, "amount", -shared_deducted)
end
if shared_deducted > 0 then
  redis.call("INCRBY", monthly_shared_usage_key, shared_deducted)
  redis.call("EXPIREAT", monthly_shared_usage_key, monthly_expire_at)
  redis.call("HINCRBY", daily_shared_usage_key, "usage_amount", shared_deducted)
  redis.call("HSET", daily_shared_usage_key, "family_id", family_id)
  redis.call("EXPIREAT", daily_shared_usage_key, daily_expire_at)
  redis.call("HINCRBY", dedupe_key, DEDUPE_PROCESSED_SHARED_FIELD, shared_deducted)
end

-- QoS 처리량은 잔량 차감 없이 processed_qos_data에만 출처별로 기록한다.
if qos_deducted > 0 then
  redis.call("HINCRBY", dedupe_key, DEDUPE_PROCESSED_QOS_FIELD, qos_deducted)
end

-- 일일 총 사용량은 개인/공유/QoS 전체 처리량을 합산하고, 앱별 일일 사용량은 source별 field에 분리 누적한다.
redis.call("INCRBY", daily_total_usage_key, total_deducted)
redis.call("EXPIREAT", daily_total_usage_key, daily_expire_at)
if indiv_deducted > 0 then
  redis.call("HINCRBY", daily_app_usage_key, app_usage_individual_field, indiv_deducted)
end
if shared_deducted > 0 then
  redis.call("HINCRBY", daily_app_usage_key, app_usage_shared_field, shared_deducted)
end
if qos_deducted > 0 then
  redis.call("HINCRBY", daily_app_usage_key, app_usage_qos_field, qos_deducted)
end
redis.call("EXPIREAT", daily_app_usage_key, daily_expire_at)

-- ===== 12단계: 최종 Lua 상태 확정 =====
-- 정책 cap 없이 policy_target이 남으면 모든 잔량/QoS 경로를 소진한 것이므로 NO_BALANCE로 마감한다.
-- 정책 cap이 없고 QoS가 실제 적용된 경우에는 QOS 상태를 반환해 처리 출처를 드러낸다.
if policy_target - total_deducted > 0 and not policy_affected then
  final_status = "NO_BALANCE"
elseif final_status == "OK" and qos_deducted > 0 then
  final_status = "QOS"
end

return as_json(indiv_deducted, shared_deducted, qos_deducted, final_status, finished_at_epoch_millis)
