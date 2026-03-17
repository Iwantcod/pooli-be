-- deduct_indiv.lua
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

-- KEYS (기존 시그니처를 최대한 유지하면서 필요한 값만 사용한다)
local remaining_key = KEYS[1]
local policy_daily_key = KEYS[4]
local policy_app_data_key = KEYS[5]
local policy_app_speed_key = KEYS[6]
local daily_total_limit_key = KEYS[11]
local daily_total_usage_key = KEYS[12]
local app_data_daily_limit_key = KEYS[13]
local daily_app_usage_key = KEYS[14]
local app_speed_limit_key = KEYS[15]
local speed_bucket_key = KEYS[16]
local refill_idempotency_key = KEYS[17]

-- ARGV
local target_data = tonumber(ARGV[1])
local app_id = tonumber(ARGV[2])
local now_epoch_second = tonumber(ARGV[5])
local daily_expire_at = tonumber(ARGV[6])
local whitelist_bypass_flag = tonumber(ARGV[7] or "0")
local refill_amount = tonumber(ARGV[8] or "0")
local refill_uuid = ARGV[9]
local refill_idempotency_ttl_seconds = tonumber(ARGV[10] or "0")
local refill_db_empty_flag = ARGV[11] or "0"

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

if has_missing_global_policy_key(
  policy_daily_key,
  policy_app_data_key,
  policy_app_speed_key
) then
  return as_json(0, "GLOBAL_POLICY_HYDRATE")
end

-- 리필 반영 + 멱등키 등록을 동일 Lua에서 처리해 경쟁 구간을 제거한다.
if refill_amount and refill_amount > 0 then
  if not refill_idempotency_key or refill_idempotency_key == "" then
    return as_json(-1, "ERROR")
  end
  -- 멱등 TTL이 유효하지 않으면 리필 차감/멱등키 등록을 시작하지 않는다.
  if not refill_idempotency_ttl_seconds
    or refill_idempotency_ttl_seconds <= 0
    or refill_idempotency_ttl_seconds ~= math.floor(refill_idempotency_ttl_seconds) then
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
      refill_idempotency_ttl_seconds,
      "NX"
    )
  end
end

local current_amount = tonumber(redis.call("HGET", remaining_key, "amount") or "-1")
if current_amount < 0 then
  return as_json(0, "HYDRATE")
end

local whitelist_bypass = whitelist_bypass_flag == 1
local final_status = "OK"
-- app speed가 실제로 요청량을 줄였는지 기록해, 차감 성공 후에만 HIT_APP_SPEED를 확정한다.
local app_speed_capped = false
-- 정책 판정은 "요청량 전체"를 기준으로 먼저 수행하고,
-- 마지막에 잔량과 비교해 실제 차감량을 결정한다.
local policy_capped_target = target_data

local app_member = tostring(math.floor(app_id))
local app_usage_field = "app:" .. app_member
local app_limit_field = "limit:" .. app_member
local app_speed_field = "speed:" .. app_member

if not whitelist_bypass then
  if is_policy_enabled(policy_daily_key) then
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

  if is_policy_enabled(policy_app_data_key) then
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

  if is_policy_enabled(policy_app_speed_key) then
    local app_speed_limit = tonumber(redis.call("HGET", app_speed_limit_key, app_speed_field) or "-1")
    if app_speed_limit >= 0 then
      local speed_used = tonumber(redis.call("GET", speed_bucket_key) or "0")
      local speed_remaining = math.max(0, app_speed_limit - speed_used)
      -- 이번 1초 윈도우에서 이미 속도 예산이 0이면 즉시 차단한다.
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
  return as_json(0, "NO_BALANCE")
end

redis.call("HINCRBY", remaining_key, "amount", -answer)

redis.call("INCRBY", daily_total_usage_key, answer)
redis.call("EXPIREAT", daily_total_usage_key, daily_expire_at)
redis.call("HINCRBY", daily_app_usage_key, app_usage_field, answer)
redis.call("EXPIREAT", daily_app_usage_key, daily_expire_at)

-- 앱 속도 제한용 버킷을 차감과 같은 Lua에서 즉시 갱신해 동시성 우회 가능성을 줄인다.
redis.call("INCRBY", speed_bucket_key, answer)
redis.call("EXPIRE", speed_bucket_key, SPEED_BUCKET_TTL_SECONDS)

-- 잔량 부족 없이 차감이 완료된 경우에만 app speed 제약 상태를 노출한다.
if app_speed_capped and not insufficient_balance then
  final_status = "HIT_APP_SPEED"
end

return as_json(answer, final_status)
