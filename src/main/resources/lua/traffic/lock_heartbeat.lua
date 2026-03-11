-- lock_heartbeat.lua
-- 반환 계약: 1(성공) / 0(실패)
-- KEYS[1]: lock 키
-- ARGV[1]: traceId
-- ARGV[2]: lock TTL(ms)

-- 단계 1) 입력값을 읽습니다.
local lock_key = KEYS[1]
local trace_id = ARGV[1]
local lock_ttl_ms = tonumber(ARGV[2])

-- 단계 2) 필수 인자를 검증합니다.
if not lock_key or lock_key == "" then
  return 0
end

if not trace_id or trace_id == "" then
  return 0
end

if not lock_ttl_ms or lock_ttl_ms <= 0 then
  return 0
end

-- 단계 3) 호출자가 현재 lock 소유자인 경우에만 TTL을 연장합니다.
local lock_owner = redis.call("GET", lock_key)
if lock_owner == trace_id then
  redis.call("PEXPIRE", lock_key, lock_ttl_ms)
  return 1
end

-- 단계 4) 소유권이 바뀌었거나 lock이 사라졌다면 heartbeat는 무시합니다.
return 0
