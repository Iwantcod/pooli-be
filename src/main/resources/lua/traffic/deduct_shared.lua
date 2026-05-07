-- deduct_shared.lua
-- 차감 단계 Lua: 한도/속도/차감/usage/speed 갱신을 원자 처리한다.

local function as_json(answer, status)
  return cjson.encode({ answer = answer, status = status })
end

local function is_policy_enabled(policy_key)
  if not policy_key or policy_key == "" then
    return false
  end
  return tonumber(redis.call("HGET", policy_key, "value") or "0") == 1
end

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

local SPEED_BUCKET_TTL_SECONDS = 15
local DEDUPE_PROCESSED_INDIVIDUAL_FIELD = "processed_individual_data"
local DEDUPE_PROCESSED_SHARED_FIELD = "processed_shared_data"
local DEDUPE_RETRY_FIELD = "retry_count"

-- 공유 잔량이 부족한 경우 QoS 보정식을 적용해 대체 차감량/상태를 계산합니다.
-- daily/app 정책은 호출부에서 반영된 cap(qos_capped_target)을 입력으로 받고,
-- app speed 정책은 화이트리스트 우회가 비활성인 경우에만 여기서 적용합니다.
local function resolve_qos_fallback(
  target_data,
  policy_status,
  whitelist_bypass,
  policy_app_speed_key,
  app_speed_limit_key,
  app_speed_field,
  speed_bucket_key,
  individual_remaining_key
)
  if not target_data or target_data <= 0 then
    return 0, policy_status
  end

  if not individual_remaining_key or individual_remaining_key == "" then
    return 0, "NO_BALANCE"
  end

  local raw_qos = tonumber(redis.call("HGET", individual_remaining_key, "qos") or "0")
  local normalized_qos = math.max(0, raw_qos or 0)
  local fallback_answer = math.min(normalized_qos, target_data)

  if fallback_answer <= 0 then
    if policy_status and policy_status ~= "OK" then
      return 0, policy_status
    end
    return 0, "NO_BALANCE"
  end

  -- 화이트리스트 우회가 활성화된 요청은 QoS fallback에서도 app speed 정책을 적용하지 않는다.
  if (not whitelist_bypass) and is_policy_enabled(policy_app_speed_key) then
    local raw_app_speed_limit = tonumber(redis.call("HGET", app_speed_limit_key, app_speed_field) or "-1")
    -- -1(또는 미존재)은 무제한으로 간주해 speed cap을 적용하지 않는다.
    if raw_app_speed_limit and raw_app_speed_limit >= 0 then
      local speed_used = tonumber(redis.call("GET", speed_bucket_key) or "0")
      local speed_remaining = math.max(0, raw_app_speed_limit - speed_used)
      if speed_remaining <= 0 then
        return 0, "HIT_APP_SPEED"
      end

      local before_speed_cap = fallback_answer
      fallback_answer = math.min(fallback_answer, speed_remaining)
      if fallback_answer <= 0 then
        return 0, "HIT_APP_SPEED"
      end

      -- 실제로 speed cap으로 차감량이 줄어든 경우에만 HIT_APP_SPEED를 반영한다.
      if fallback_answer < before_speed_cap then
        if policy_status and policy_status ~= "OK" then
          return fallback_answer, policy_status
        end
        return fallback_answer, "HIT_APP_SPEED"
      end
    end
  end

  if policy_status and policy_status ~= "OK" then
    return fallback_answer, policy_status
  end
  return fallback_answer, "QOS"
end

-- KEYS (기존 시그니처를 최대한 유지하면서 필요한 값만 사용한다)
local remaining_key = KEYS[1]
local policy_shared_key = KEYS[4]
local policy_daily_key = KEYS[5]
local policy_app_data_key = KEYS[6]
local policy_app_speed_key = KEYS[7]
local daily_total_limit_key = KEYS[12]
local daily_total_usage_key = KEYS[13]
local monthly_shared_limit_key = KEYS[14]
local monthly_shared_usage_key = KEYS[15]
local app_data_daily_limit_key = KEYS[16]
local daily_app_usage_key = KEYS[17]
local app_speed_limit_key = KEYS[18]
local speed_bucket_key = KEYS[19]
local individual_remaining_key = KEYS[20]
local refill_idempotency_key = KEYS[21]
local dedupe_key = KEYS[22]

-- ARGV
local target_data = tonumber(ARGV[1])
local app_id = tonumber(ARGV[2])
local now_epoch_second = tonumber(ARGV[5])
local daily_expire_at = tonumber(ARGV[6])
local monthly_expire_at = tonumber(ARGV[7])
local whitelist_bypass_flag = tonumber(ARGV[8] or "0")
local refill_amount = tonumber(ARGV[9] or "0")
local refill_uuid = ARGV[10]
local refill_idempotency_ttl_seconds = tonumber(ARGV[11] or "0")
local refill_db_empty_flag = ARGV[12] or "0"
local allow_qos_fallback = tonumber(ARGV[13] or "0")
local api_total_data = tonumber(ARGV[14] or "-1")

if not remaining_key or remaining_key == "" then
  return as_json(-1, "ERROR")
end
if not target_data or target_data < 0 then
  return as_json(-1, "ERROR")
end
if not app_id or app_id < 0 then
  return as_json(-1, "ERROR")
end
if not now_epoch_second or now_epoch_second <= 0 then
  return as_json(-1, "ERROR")
end
if not daily_expire_at or daily_expire_at <= 0 then
  return as_json(-1, "ERROR")
end
if not monthly_expire_at or monthly_expire_at <= 0 then
  return as_json(-1, "ERROR")
end
if not dedupe_key or dedupe_key == "" then
  return as_json(-1, "ERROR")
end
if not api_total_data or api_total_data < 0 then
  return as_json(-1, "ERROR")
end

if redis.call("EXISTS", dedupe_key) == 0 then
  redis.call(
    "HSET",
    dedupe_key,
    DEDUPE_PROCESSED_INDIVIDUAL_FIELD, 0,
    DEDUPE_PROCESSED_SHARED_FIELD, 0,
    DEDUPE_RETRY_FIELD, 0
  )
end
local processed_individual = tonumber(redis.call("HGET", dedupe_key, DEDUPE_PROCESSED_INDIVIDUAL_FIELD) or "0")
if not processed_individual or processed_individual < 0 then
  processed_individual = 0
end
local processed_shared = tonumber(redis.call("HGET", dedupe_key, DEDUPE_PROCESSED_SHARED_FIELD) or "0")
if not processed_shared or processed_shared < 0 then
  processed_shared = 0
end
local processed_data = processed_individual + processed_shared
local remaining_quota = math.max(0, api_total_data - processed_data)
if remaining_quota <= 0 then
  return as_json(0, "OK")
end
target_data = math.min(target_data, remaining_quota)

if has_missing_global_policy_key(
  policy_shared_key,
  policy_daily_key,
  policy_app_data_key,
  policy_app_speed_key
) then
  return as_json(0, "GLOBAL_POLICY_HYDRATE")
end

if refill_amount and refill_amount > 0 then
  if not refill_idempotency_key or refill_idempotency_key == "" then
    return as_json(-1, "ERROR")
  end

  local normalized_refill_uuid = "1"
  if refill_uuid and refill_uuid ~= "" then
    normalized_refill_uuid = refill_uuid
  end

  -- SET NX를 먼저 수행해 idempotency 키 획득에 성공한 경우에만 refill을 반환한다.
  local refill_guard_result = redis.call(
    "SET",
    refill_idempotency_key,
    normalized_refill_uuid,
    "EX",
    math.max(1, refill_idempotency_ttl_seconds),
    "NX"
  )
  local refill_guard_acquired = refill_guard_result == "OK"
  if (not refill_guard_acquired) and type(refill_guard_result) == "table" then
    refill_guard_acquired = refill_guard_result.ok == "OK"
  end
  if refill_guard_acquired then
    redis.call("HINCRBY", remaining_key, "amount", refill_amount)
    redis.call("HSET", remaining_key, "is_empty", refill_db_empty_flag)
  end
end

local current_amount = tonumber(redis.call("HGET", remaining_key, "amount") or "-1")
if current_amount < 0 then
  return as_json(0, "HYDRATE")
end

local final_status = "OK"
local qos_policy_status = "OK"
-- app speed가 실제로 요청량을 줄였는지 추적해 차감 성공 후 상태를 확정한다.
local app_speed_capped = false
-- 정책 판정은 요청량 전체 기준으로 먼저 수행한다.
local policy_capped_target = target_data
-- QoS 경로는 daily/app 정책만 반영하고 shared monthly 정책은 제외한다.
local qos_capped_target = target_data
local used_qos_fallback = false
local whitelist_bypass = whitelist_bypass_flag == 1

local app_member = tostring(math.floor(app_id))
local app_usage_field = "app:" .. app_member
local app_limit_field = "limit:" .. app_member
local app_speed_field = "speed:" .. app_member

if not whitelist_bypass then
  if policy_capped_target > 0 and is_policy_enabled(policy_daily_key) then
    local daily_limit = tonumber(redis.call("HGET", daily_total_limit_key, "value") or "-1")
    if daily_limit >= 0 then
      local daily_used = tonumber(redis.call("GET", daily_total_usage_key) or "0")
      local daily_remaining = math.max(0, daily_limit - daily_used)
      local before_daily_cap = policy_capped_target
      policy_capped_target = math.min(policy_capped_target, daily_remaining)
      local before_daily_qos_cap = qos_capped_target
      qos_capped_target = math.min(qos_capped_target, daily_remaining)
      if policy_capped_target <= 0 then
        return as_json(0, "HIT_DAILY_LIMIT")
      end
      if policy_capped_target < before_daily_cap then
        final_status = "HIT_DAILY_LIMIT"
      end
      if qos_capped_target < before_daily_qos_cap then
        qos_policy_status = "HIT_DAILY_LIMIT"
      end
    end
  end

  if policy_capped_target > 0 and allow_qos_fallback ~= 1 and is_policy_enabled(policy_shared_key) then
    local monthly_limit = tonumber(redis.call("HGET", monthly_shared_limit_key, "value") or "-1")
    if monthly_limit >= 0 then
      local monthly_used = tonumber(redis.call("GET", monthly_shared_usage_key) or "0")
      local monthly_remaining = math.max(0, monthly_limit - monthly_used)
      local before_monthly_cap = policy_capped_target
      policy_capped_target = math.min(policy_capped_target, monthly_remaining)
      if policy_capped_target <= 0 then
        return as_json(0, "HIT_MONTHLY_SHARED_LIMIT")
      end
      if policy_capped_target < before_monthly_cap then
        final_status = "HIT_MONTHLY_SHARED_LIMIT"
      end
    end
  end

  if policy_capped_target > 0 and is_policy_enabled(policy_app_data_key) then
    local app_daily_limit = tonumber(redis.call("HGET", app_data_daily_limit_key, app_limit_field) or "-1")
    if app_daily_limit >= 0 then
      local app_daily_used = tonumber(redis.call("HGET", daily_app_usage_key, app_usage_field) or "0")
      local app_daily_remaining = math.max(0, app_daily_limit - app_daily_used)
      local before_app_daily_cap = policy_capped_target
      policy_capped_target = math.min(policy_capped_target, app_daily_remaining)
      local before_app_daily_qos_cap = qos_capped_target
      qos_capped_target = math.min(qos_capped_target, app_daily_remaining)
      if policy_capped_target <= 0 then
        return as_json(0, "HIT_APP_DAILY_LIMIT")
      end
      if policy_capped_target < before_app_daily_cap then
        final_status = "HIT_APP_DAILY_LIMIT"
      end
      if qos_capped_target < before_app_daily_qos_cap then
        qos_policy_status = "HIT_APP_DAILY_LIMIT"
      end
    end
  end

  if policy_capped_target > 0 and is_policy_enabled(policy_app_speed_key) then
    local app_speed_limit = tonumber(redis.call("HGET", app_speed_limit_key, app_speed_field) or "-1")
    if app_speed_limit >= 0 then
      local speed_used = tonumber(redis.call("GET", speed_bucket_key) or "0")
      local speed_remaining = math.max(0, app_speed_limit - speed_used)
      -- 이번 1초 윈도우 예산이 0이면 공유/개인 경로와 무관하게 즉시 app speed로 종료한다.
      if speed_remaining <= 0 then
        return as_json(0, "HIT_APP_SPEED")
      end
      local before_speed_cap = policy_capped_target
      policy_capped_target = math.min(policy_capped_target, speed_remaining)
      if policy_capped_target < before_speed_cap then
        app_speed_capped = true
      end
    end
  end
end

local answer = math.min(current_amount, policy_capped_target)
local insufficient_balance = current_amount < policy_capped_target

-- 정책으로 줄인 목표량 대비 잔량이 부족하면 NO_BALANCE로 리필 경로를 연다.
if insufficient_balance then
  final_status = "NO_BALANCE"
end

if answer <= 0 then
  -- 공유 DB 리필 시도 이전에는 QOS로 즉시 우회하지 않고 NO_BALANCE를 유지한다.
  if allow_qos_fallback ~= 1 then
    return as_json(0, final_status)
  end

  local qos_answer, qos_status = resolve_qos_fallback(
    qos_capped_target,
    qos_policy_status,
    whitelist_bypass,
    policy_app_speed_key,
    app_speed_limit_key,
    app_speed_field,
    speed_bucket_key,
    individual_remaining_key
  )
  if qos_answer <= 0 then
    if qos_status and qos_status ~= "OK" then
      return as_json(0, qos_status)
    end
    return as_json(0, final_status)
  end
  answer = qos_answer
  final_status = qos_status
  used_qos_fallback = true
end

redis.call("INCRBY", daily_total_usage_key, answer)
redis.call("EXPIREAT", daily_total_usage_key, daily_expire_at)
redis.call("HINCRBY", daily_app_usage_key, app_usage_field, answer)
redis.call("EXPIREAT", daily_app_usage_key, daily_expire_at)

if not used_qos_fallback then
  redis.call("HINCRBY", remaining_key, "amount", -answer)
  redis.call("INCRBY", monthly_shared_usage_key, answer)
  redis.call("EXPIREAT", monthly_shared_usage_key, monthly_expire_at)
end

redis.call("INCRBY", speed_bucket_key, answer)
redis.call("EXPIRE", speed_bucket_key, SPEED_BUCKET_TTL_SECONDS)
if used_qos_fallback then
  redis.call("HINCRBY", dedupe_key, DEDUPE_PROCESSED_INDIVIDUAL_FIELD, answer)
else
  redis.call("HINCRBY", dedupe_key, DEDUPE_PROCESSED_SHARED_FIELD, answer)
end

-- 잔량 부족 없이 차감이 완료된 경우에만 app speed 제한 상태를 최종 상태로 확정한다.
if app_speed_capped and not insufficient_balance then
  final_status = "HIT_APP_SPEED"
end

return as_json(answer, final_status)
