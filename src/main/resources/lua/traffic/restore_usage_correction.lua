-- Redis 장애 복구 검증 후 보정 Lua.
-- KEYS[1]: 보정할 Redis key
-- ARGV[1]: hash field
-- ARGV[2]: 기준값
-- ARGV[3]: expire epoch seconds

redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])

local expire_at = tonumber(ARGV[3]) or 0
if expire_at > 0 then
    redis.call('EXPIREAT', KEYS[1], expire_at)
end

return { 'CORRECTED' }
