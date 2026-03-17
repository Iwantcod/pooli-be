-- policy_check_indiv.lua
-- 차단성 정책(즉시/반복/화이트리스트)만 검증한다.
-- 반환 계약: {"answer": 1|0, "status": "..."}
-- answer=1: 화이트리스트 우회 활성

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

local function is_in_repeat_block(repeat_block_key, day_num, sec_of_day)
  if not repeat_block_key or repeat_block_key == "" then
    return false
  end
  if redis.call("EXISTS", repeat_block_key) == 0 then
    return false
  end

  local cursor = "0"
  repeat
    local scan_res = redis.call("HSCAN", repeat_block_key, cursor, "MATCH", "day:" .. day_num .. ":*", "COUNT", 100)
    cursor = scan_res[1]
    local entries = scan_res[2]

    local idx = 1
    while idx <= #entries do
      local range_text = entries[idx + 1]
      if range_text then
        local start_text, end_text = string.match(range_text, "^(%-?%d+):(%-?%d+)$")
        local start_sec = tonumber(start_text)
        local end_sec = tonumber(end_text)
        if start_sec and end_sec and sec_of_day >= start_sec and sec_of_day <= end_sec then
          return true
        end
      end
      idx = idx + 2
    end
  until cursor == "0"

  return false
end

-- KEYS
local policy_repeat_key = KEYS[1]
local policy_immediate_key = KEYS[2]
local policy_whitelist_key = KEYS[3]
local app_whitelist_key = KEYS[4]
local immediately_block_end_key = KEYS[5]
local repeat_block_key = KEYS[6]

-- ARGV
local app_id = tonumber(ARGV[1])
local day_num = tonumber(ARGV[2])
local sec_of_day = tonumber(ARGV[3])
local now_epoch_second = tonumber(ARGV[4])

if not app_id or app_id < 0 then
  return as_json(-1, "ERROR")
end
if not day_num or day_num < 0 or day_num > 6 then
  return as_json(-1, "ERROR")
end
if not sec_of_day or sec_of_day < 0 or sec_of_day > 86399 then
  return as_json(-1, "ERROR")
end
if not now_epoch_second or now_epoch_second <= 0 then
  return as_json(-1, "ERROR")
end

if has_missing_global_policy_key(
  policy_repeat_key,
  policy_immediate_key,
  policy_whitelist_key
) then
  return as_json(0, "GLOBAL_POLICY_HYDRATE")
end

local app_member = tostring(math.floor(app_id))

if is_policy_enabled(policy_whitelist_key) and app_whitelist_key and app_whitelist_key ~= "" then
  local is_whitelisted = redis.call("SISMEMBER", app_whitelist_key, app_member) == 1
  if is_whitelisted then
    return as_json(1, "OK")
  end
end

if is_policy_enabled(policy_immediate_key) then
  local block_end_at = tonumber(redis.call("HGET", immediately_block_end_key, "value") or "0")
  if block_end_at > 0 and now_epoch_second <= block_end_at then
    return as_json(0, "BLOCKED_IMMEDIATE")
  end
end

if is_policy_enabled(policy_repeat_key) then
  if is_in_repeat_block(repeat_block_key, day_num, sec_of_day) then
    return as_json(0, "BLOCKED_REPEAT")
  end
end

return as_json(0, "OK")
