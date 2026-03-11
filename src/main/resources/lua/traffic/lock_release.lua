-- lock_release.lua
-- 반환 계약: 1(해제) / 0(무시)
-- KEYS[1]: lock 키
-- ARGV[1]: traceId

-- 단계 1) 입력값을 읽습니다.
local lock_key = KEYS[1]
local trace_id = ARGV[1]

-- 단계 2) 필수 인자를 검증합니다.
if not lock_key or lock_key == "" then
  return 0
end

if not trace_id or trace_id == "" then
  return 0
end

-- 단계 3) 현재 lock 소유자만 해제할 수 있습니다.
local lock_owner = redis.call("GET", lock_key)
if lock_owner == trace_id then
  return redis.call("DEL", lock_key)
end

-- 단계 4) 비소유자의 해제 요청은 무시합니다.
return 0
