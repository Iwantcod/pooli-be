-- refill_gate.lua
-- 반환 계약: FAIL / SKIP / OK / WAIT
-- KEYS[1]: lock 키
-- KEYS[2]: 잔량 hash 키(remaining_*_amount)
-- ARGV[1]: traceId
-- ARGV[2]: lock TTL(ms)
-- ARGV[3]: 현재 잔량
-- ARGV[4]: 임계치

-- 단계 1) 입력값을 읽습니다.
local lock_key = KEYS[1]
local balance_key = KEYS[2]
local trace_id = ARGV[1]
local lock_ttl_ms = tonumber(ARGV[2])
local current_amount = tonumber(ARGV[3])
local threshold = tonumber(ARGV[4])

-- 단계 2) 필수 인자를 검증합니다.
-- 인자가 잘못되면 리필 진입/락 소유권을 안전하게 판정할 수 없습니다.
if not lock_key or lock_key == "" then
  return "FAIL"
end

if not balance_key or balance_key == "" then
  return "FAIL"
end

if not trace_id or trace_id == "" then
  return "FAIL"
end

if not lock_ttl_ms or lock_ttl_ms <= 0 then
  return "FAIL"
end

if not current_amount or not threshold then
  return "FAIL"
end

local is_empty = tonumber(redis.call("HGET", balance_key, "is_empty") or "0")
if is_empty == nil then
  return "FAIL"
end

-- 단계 3) DB 고갈 플래그 기반 스킵 분기입니다.
-- DB 원천 잔량이 이미 고갈된 것으로 확정된 경우에는 리필을 시도하지 않습니다.
if is_empty == 1 then
  return "SKIP"
end

-- 단계 4) 빠른 스킵 분기입니다.
-- 현재 잔량이 임계치보다 크면 지금은 리필할 필요가 없습니다.
if current_amount > threshold then
  return "SKIP"
end

-- 단계 5) 현재 lock 소유자를 조회합니다.
local lock_owner = redis.call("GET", lock_key)

-- 단계 6) 소유자가 없으면 lock 획득을 시도합니다.
if not lock_owner then
  local acquired = redis.call("SET", lock_key, trace_id, "NX", "PX", lock_ttl_ms)
  if acquired then
    return "OK"
  end
  return "WAIT"
end

-- 단계 7) 호출자가 이미 lock 소유자라면 TTL을 연장하고 계속 진행합니다.
if lock_owner == trace_id then
  redis.call("PEXPIRE", lock_key, lock_ttl_ms)
  return "OK"
end

-- 단계 8) 다른 소유자가 리필 중이므로 현재 호출자는 대기해야 합니다.
return "WAIT"
