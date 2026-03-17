-- deduct_shared.lua
-- 2차 차감 Lua: 한도/속도/차감/usage/speed 갱신을 원자 처리한다.

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

-- 공유 잔량이 0인 경우 QoS 보정식을 적용해 대체 차감량/상태를 계산합니다.
local function resolve_qos_fallback(
  target_data,
  final_status,
  policy_app_speed_key,
  app_speed_limit_key,
  app_speed_field,
  individual_remaining_key
)
  if not target_data or target_data <= 0 then
    return 0, final_status
  end

  if final_status ~= "NO_BALANCE" then
    return 0, final_status
  end

  if not individual_remaining_key or individual_remaining_key == "" then
    return 0, "NO_BALANCE"
  end

  local raw_qos = tonumber(redis.call("HGET", individual_remaining_key, "qos") or "0")
  local normalized_qos = math.max(0, raw_qos or 0)

  local fallback_answer = normalized_qos
  local app_speed_policy_enabled = is_policy_enabled(policy_app_speed_key)
  if not app_speed_policy_enabled then
    fallback_answer = math.min(fallback_answer, target_data)
    if fallback_answer <= 0 then
      return 0, "NO_BALANCE"
    end
    return fallback_answer, "QOS"
  end

  local normalized_app_speed_limit = nil
  if app_speed_limit_key and app_speed_limit_key ~= "" then
    local raw_app_speed_limit = tonumber(redis.call("HGET", app_speed_limit_key, app_speed_field) or "-1")
    normalized_app_speed_limit = math.max(0, raw_app_speed_limit or -1)
    fallback_answer = math.min(fallback_answer, normalized_app_speed_limit)
  end

  fallback_answer = math.min(fallback_answer, target_data)

  if fallback_answer <= 0 then
    return 0, "NO_BALANCE"
  end

  if normalized_app_speed_limit ~= nil
      and fallback_answer == normalized_app_speed_limit
      and normalized_app_speed_limit < normalized_qos then
    return fallback_answer, "HIT_APP_SPEED"
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

  if redis.call("EXISTS", refill_idempotency_key) == 0 then
    local normalized_refill_uuid = "1"
    if refill_uuid and refill_uuid ~= "" then
      normalized_refill_uuid = refill_uuid
    end
    redis.call("HINCRBY", remaining_key, "amount", refill_amount)
    redis.call("HSET", remaining_key, "is_empty", refill_db_empty_flag)
    redis.call(
      "SET",
      refill_idempotency_key,
      normalized_refill_uuid,
      "EX",
      math.max(1, refill_idempotency_ttl_seconds),
      "NX"
    )
  end
end

local current_amount = tonumber(redis.call("HGET", remaining_key, "amount") or "-1")
if current_amount < 0 then
  return as_json(0, "HYDRATE")
end

local final_status = "OK"
-- 정책 판정은 요청량 전체 기준으로 먼저 수행한다.
local policy_capped_target = target_data
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
      if policy_capped_target <= 0 then
        return as_json(0, "HIT_DAILY_LIMIT")
      end
      if policy_capped_target < before_daily_cap then
        final_status = "HIT_DAILY_LIMIT"
      end
    end
  end

  if policy_capped_target > 0 and is_policy_enabled(policy_shared_key) then
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
      if policy_capped_target <= 0 then
        return as_json(0, "HIT_APP_DAILY_LIMIT")
      end
      if policy_capped_target < before_app_daily_cap then
        final_status = "HIT_APP_DAILY_LIMIT"
      end
    end
  end

  if policy_capped_target > 0 and is_policy_enabled(policy_app_speed_key) then
    local app_speed_limit = tonumber(redis.call("HGET", app_speed_limit_key, app_speed_field) or "-1")
    if app_speed_limit >= 0 then
      local speed_used = tonumber(redis.call("GET", speed_bucket_key) or "0")
      local speed_remaining = math.max(0, app_speed_limit - speed_used)
      local before_speed_cap = policy_capped_target
      policy_capped_target = math.min(policy_capped_target, speed_remaining)
      if policy_capped_target <= 0 then
        return as_json(0, "HIT_APP_SPEED")
      end
      if policy_capped_target < before_speed_cap then
        final_status = "HIT_APP_SPEED"
      end
    end
  end
end

local answer = math.min(current_amount, policy_capped_target)
local insufficient_balance = current_amount < policy_capped_target
-- 정책 제한이 이미 적용되었는지 기록해, answer==0일 때 QoS fallback 적용 여부를 결정한다.
local was_policy_limited = final_status ~= "OK"

-- 정책 허용량이 남아 있는데 잔량이 부족하면(NO_BALANCE) 리필 경로로 연결한다.
-- 단, HIT_APP_SPEED는 우선순위가 더 높으므로 그대로 유지한다.
if insufficient_balance and final_status ~= "HIT_APP_SPEED" then
  final_status = "NO_BALANCE"
end

if answer <= 0 then
  if final_status == "HIT_APP_SPEED" then
    return as_json(0, "HIT_APP_SPEED")
  end

  -- 정책 제한이 동반된 answer==0 케이스는 QoS로 우회하지 않고 NO_BALANCE를 유지한다.
  if was_policy_limited then
    return as_json(0, "NO_BALANCE")
  end

  local qos_answer, qos_status = resolve_qos_fallback(
    target_data,
    final_status,
    policy_app_speed_key,
    app_speed_limit_key,
    app_speed_field,
    individual_remaining_key
  )
  if qos_answer <= 0 then
    return as_json(0, qos_status)
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

return as_json(answer, final_status)
