-- deduct_unified.lua
-- 개인풀 -> 공유풀 -> QoS 순서의 단일 차감 Lua.
--
-- 핵심 계약:
-- 1) 개인/공유 잔량 차감, QoS 처리량 산정, usage counter, dedupe 갱신을 한 원자 구간에서 수행한다.
-- 2) QoS 처리량은 총/앱별 일일 사용량에는 포함하지만 개인/공유 잔량과 월별 공유 사용량에는 반영하지 않는다.
-- 3) 정책 제한으로 target이 0이 된 경우는 HIT_*를, 정책 영향 없이 차감할 수 없는 경우는 NO_BALANCE를 반환한다.

-- Redis Lua가 Java DTO로 역직렬화할 JSON 응답을 만든다.
local function as_json(indiv_deducted, shared_deducted, qos_deducted, status)
  return cjson.encode({
    indivDeducted = indiv_deducted,
    sharedDeducted = shared_deducted,
    qosDeducted = qos_deducted,
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

-- Redis hash 숫자 필드를 0 이상 값으로 읽는다.
-- 누락/음수/비숫자는 정책적으로 0으로 보정한다.
local function read_non_negative_hash_number(key, field)
  local value = tonumber(redis.call("HGET", key, field) or "0")
  if not value or value < 0 then
    return 0
  end
  return value
end

local SPEED_BUCKET_TTL_SECONDS = 15
local DEDUPE_PROCESSED_INDIVIDUAL_FIELD = "processed_individual_data"
local DEDUPE_PROCESSED_SHARED_FIELD = "processed_shared_data"
local DEDUPE_PROCESSED_QOS_FIELD = "processed_qos_data"
local DEDUPE_RETRY_FIELD = "retry_count"

-- KEYS
-- 1~2: 잔량 hash, 3~6: 전역 정책 활성화 hash, 7~14: 제한/사용량/속도 버킷, 15: in-flight dedupe hash.
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
local speed_bucket_key = KEYS[14]
local dedupe_key = KEYS[15]

-- ARGV
-- target_data: 이번 Lua 호출에서 추가 처리할 목표량.
-- api_total_data: 전체 원본 요청량. dedupe 누적량과 비교해 재처리 시 초과 차감을 방지한다.
local target_data = tonumber(ARGV[1])
local app_id = tonumber(ARGV[2])
local now_epoch_second = tonumber(ARGV[3])
local daily_expire_at = tonumber(ARGV[4])
local monthly_expire_at = tonumber(ARGV[5])
local whitelist_bypass_flag = tonumber(ARGV[6] or "0")
local api_total_data = tonumber(ARGV[7] or "-1")

-- ===== 입력 검증 =====
-- 필수 key/argument가 누락되거나 음수이면 Redis 상태를 변경하지 않고 ERROR를 반환한다.
if not individual_remaining_key or individual_remaining_key == "" then
  return as_json(0, 0, 0, "ERROR")
end
if not shared_remaining_key or shared_remaining_key == "" then
  return as_json(0, 0, 0, "ERROR")
end
if not target_data or target_data < 0 then
  return as_json(0, 0, 0, "ERROR")
end
if not app_id or app_id < 0 then
  return as_json(0, 0, 0, "ERROR")
end
if not now_epoch_second or now_epoch_second <= 0 then
  return as_json(0, 0, 0, "ERROR")
end
if not daily_expire_at or daily_expire_at <= 0 then
  return as_json(0, 0, 0, "ERROR")
end
if not monthly_expire_at or monthly_expire_at <= 0 then
  return as_json(0, 0, 0, "ERROR")
end
if not dedupe_key or dedupe_key == "" then
  return as_json(0, 0, 0, "ERROR")
end
if not api_total_data or api_total_data < 0 then
  return as_json(0, 0, 0, "ERROR")
end

-- ===== in-flight dedupe 초기화 및 재처리 보정 =====
-- dedupe key가 없으면 신규 처리로 보고 세 차감 출처와 retry_count를 0으로 초기화한다.
-- 기존 key에 processed_qos_data만 없으면 구버전 처리 중 생성된 key로 보고 0을 보강한다.
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

-- 이미 처리한 개인/공유/QoS 합계를 원본 api_total_data에서 차감해 이번 호출의 상한을 정한다.
-- reclaim 재처리 중에도 총 처리량이 원본 요청량을 넘지 않게 하는 방어선이다.
local processed_individual = read_non_negative_hash_number(dedupe_key, DEDUPE_PROCESSED_INDIVIDUAL_FIELD)
local processed_shared = read_non_negative_hash_number(dedupe_key, DEDUPE_PROCESSED_SHARED_FIELD)
local processed_qos = read_non_negative_hash_number(dedupe_key, DEDUPE_PROCESSED_QOS_FIELD)
local processed_data = processed_individual + processed_shared + processed_qos
local remaining_quota = math.max(0, api_total_data - processed_data)
if remaining_quota <= 0 then
  return as_json(0, 0, 0, "OK")
end
target_data = math.min(target_data, remaining_quota)

-- ===== 전역 정책 스냅샷 존재성 검증 =====
-- 정책 활성화 여부 key가 유실되면 제한값을 임의로 우회하지 않고 Java hydrate 단계로 넘긴다.
if has_missing_global_policy_key(
  policy_shared_key,
  policy_daily_key,
  policy_app_data_key,
  policy_app_speed_key
) then
  return as_json(0, 0, 0, "GLOBAL_POLICY_HYDRATE")
end

-- ===== 잔량 cache 존재성 검증 =====
-- 개인풀 잔량은 항상 첫 번째 차감 대상이므로 먼저 확인한다.
-- 공유풀 잔량은 실제 공유 차감 대상이 생겼을 때 아래에서 지연 확인한다.
local individual_amount = tonumber(redis.call("HGET", individual_remaining_key, "amount") or "-1")
if individual_amount < 0 then
  return as_json(0, 0, 0, "HYDRATE")
end

local whitelist_bypass = whitelist_bypass_flag == 1
local app_member = tostring(math.floor(app_id))
local app_usage_field = "app:" .. app_member
local app_limit_field = "limit:" .. app_member
local app_speed_field = "speed:" .. app_member

-- ===== 정책 적용 대상량 계산 =====
-- policy_target: 총 사용량/앱별 일일 사용량/QoS까지 포함해 처리 가능한 목표량.
-- pool_target: 개인/공유 잔량에서 실제로 차감할 수 있는 목표량. 앱 속도 제한은 pool_target에만 적용한다.
-- final_status: 정책 cap 또는 QoS/잔량 부족 상태를 Java에 전달하기 위한 Lua 상태.
local final_status = "OK"
local policy_target = target_data
local pool_target = target_data
local policy_affected = false

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
      local app_daily_used = tonumber(redis.call("HGET", daily_app_usage_key, app_usage_field) or "0")
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

  -- 앱 속도 제한: 잔량 차감 경로(개인/공유)에만 적용한다.
  -- QoS는 데이터 잔량 소진 이후 별도 속도 정책으로 동작하므로 이 speed bucket에 합산하지 않는다.
  if is_policy_enabled(policy_app_speed_key) then
    local app_speed_limit = tonumber(redis.call("HGET", app_speed_limit_key, app_speed_field) or "-1")
    if app_speed_limit >= 0 then
      local speed_used = tonumber(redis.call("GET", speed_bucket_key) or "0")
      local speed_remaining = math.max(0, app_speed_limit - speed_used)
      local before_speed = pool_target
      pool_target = math.min(pool_target, speed_remaining)
      if pool_target < before_speed then
        final_status = "HIT_APP_SPEED"
        policy_affected = true
      end
    end
  end
end

-- ===== 개인풀 차감량 계산 =====
-- 개인풀은 pool_target 범위 안에서 현재 Redis 잔량만큼 먼저 차감한다.
local indiv_deducted = math.min(individual_amount, pool_target)
local remaining_pool_target = math.max(0, pool_target - indiv_deducted)

-- ===== 공유풀 차감량 계산 =====
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
if shared_target > 0 then
  shared_amount = tonumber(redis.call("HGET", shared_remaining_key, "amount") or "-1")
  if shared_amount < 0 then
    return as_json(0, 0, 0, "HYDRATE")
  end
end
local shared_deducted = math.min(shared_amount, shared_target)

-- ===== QoS 처리량 계산 =====
-- QoS는 개인/공유 잔량으로 처리하지 못한 policy_target 잔여량을 대상으로 한다.
-- `qos` 필드는 잔량이 아니라 이번 요청에서 QoS로 처리 가능한 한도로 해석한다.
local qos_target = math.max(0, policy_target - indiv_deducted - shared_deducted)
local qos_limit = read_non_negative_hash_number(individual_remaining_key, "qos")
local qos_deducted = math.min(qos_limit, qos_target)

-- ===== 상태 우선순위 결정 =====
-- 실제 차감/처리량이 0이면 Redis 쓰기 없이 종료한다.
-- 정책 cap이 target을 줄인 결과라면 HIT_*를 유지하고, 정책 영향이 없다면 잔량 부족으로 NO_BALANCE를 반환한다.
local total_deducted = indiv_deducted + shared_deducted + qos_deducted
if total_deducted <= 0 then
  if policy_affected and final_status ~= "OK" then
    return as_json(0, 0, 0, final_status)
  end
  return as_json(0, 0, 0, "NO_BALANCE")
end

-- ===== 원자적 상태 갱신 =====
-- 개인풀 잔량 차감량은 개인 잔량과 processed_individual_data에만 반영한다.
if indiv_deducted > 0 then
  redis.call("HINCRBY", individual_remaining_key, "amount", -indiv_deducted)
  redis.call("HINCRBY", dedupe_key, DEDUPE_PROCESSED_INDIVIDUAL_FIELD, indiv_deducted)
end

-- 공유풀 잔량 차감량은 공유 잔량, 월별 공유 사용량, processed_shared_data에만 반영한다.
if shared_deducted > 0 then
  redis.call("HINCRBY", shared_remaining_key, "amount", -shared_deducted)
  redis.call("INCRBY", monthly_shared_usage_key, shared_deducted)
  redis.call("EXPIREAT", monthly_shared_usage_key, monthly_expire_at)
  redis.call("HINCRBY", dedupe_key, DEDUPE_PROCESSED_SHARED_FIELD, shared_deducted)
end

-- QoS 처리량은 잔량 차감 없이 processed_qos_data에만 출처별로 기록한다.
if qos_deducted > 0 then
  redis.call("HINCRBY", dedupe_key, DEDUPE_PROCESSED_QOS_FIELD, qos_deducted)
end

-- 일일 총 사용량과 앱별 일일 사용량은 개인/공유/QoS 전체 처리량을 합산한다.
redis.call("INCRBY", daily_total_usage_key, total_deducted)
redis.call("EXPIREAT", daily_total_usage_key, daily_expire_at)
redis.call("HINCRBY", daily_app_usage_key, app_usage_field, total_deducted)
redis.call("EXPIREAT", daily_app_usage_key, daily_expire_at)

-- 앱 속도 버킷은 개인/공유 잔량 차감량만 반영한다. QoS 처리량은 별도 정책이므로 제외한다.
local speed_bucket_deducted = indiv_deducted + shared_deducted
if speed_bucket_deducted > 0 then
  redis.call("INCRBY", speed_bucket_key, speed_bucket_deducted)
  redis.call("EXPIRE", speed_bucket_key, SPEED_BUCKET_TTL_SECONDS)
end

-- 정책 cap 없이 policy_target이 남으면 모든 잔량/QoS 경로를 소진한 것이므로 NO_BALANCE로 마감한다.
-- 정책 cap이 없고 QoS가 실제 적용된 경우에는 QOS 상태를 반환해 처리 출처를 드러낸다.
if policy_target - total_deducted > 0 and not policy_affected then
  final_status = "NO_BALANCE"
elseif final_status == "OK" and qos_deducted > 0 then
  final_status = "QOS"
end

return as_json(indiv_deducted, shared_deducted, qos_deducted, final_status)
