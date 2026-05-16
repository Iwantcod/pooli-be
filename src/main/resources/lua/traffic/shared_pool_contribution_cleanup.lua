-- shared_pool_contribution_cleanup.lua
-- KEYS[1]: metadata hash key
-- KEYS[2]: individual hydrate lock key
-- KEYS[3]: shared hydrate lock key
-- ARGV[1]: individual lock owner
-- ARGV[2]: shared lock owner

local deleted_count = 0

-- 단계 1) MySQL 반영과 outbox 완료 처리 이후 더 이상 필요 없는 metadata hash를 삭제합니다.
if KEYS[1] and KEYS[1] ~= "" then
  deleted_count = deleted_count + redis.call("DEL", KEYS[1])
end

-- 단계 2) 개인 hydrate lock은 현재 owner token과 일치할 때만 삭제합니다.
-- TTL 만료 후 다른 worker가 잡은 lock을 지우지 않기 위한 compare-and-delete입니다.
if KEYS[2] and KEYS[2] ~= "" and ARGV[1] and ARGV[1] ~= "" then
  if redis.call("GET", KEYS[2]) == ARGV[1] then
    deleted_count = deleted_count + redis.call("DEL", KEYS[2])
  end
end

-- 단계 3) 공유 hydrate lock도 같은 owner token 검증 후 삭제합니다.
if KEYS[3] and KEYS[3] ~= "" and ARGV[2] and ARGV[2] ~= "" then
  if redis.call("GET", KEYS[3]) == ARGV[2] then
    deleted_count = deleted_count + redis.call("DEL", KEYS[3])
  end
end

-- 단계 4) 삭제된 metadata/lock key 수를 반환해 호출자가 cleanup 결과를 로깅할 수 있게 합니다.
return deleted_count
