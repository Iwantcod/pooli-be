-- 차감 preflight에서 필요한 Redis key 존재 여부를 한 번의 Lua 호출로 확인한다.
-- KEYS: 호출자가 전달한 preflight 대상 key 목록
-- 반환: KEYS 순서와 같은 1/0 존재 여부 목록

local result = {}
local idx = 1

while idx <= #KEYS do
    result[idx] = redis.call('EXISTS', KEYS[idx])
    idx = idx + 1
end

return result
