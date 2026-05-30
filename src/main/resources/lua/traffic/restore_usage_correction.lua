-- Redis 장애 복구 검증 후 보정 Lua.
-- KEYS[1]: 보정할 Redis key
-- ARGV[1]: value kind ('string' 또는 'hash')
-- ARGV[2]: hash field 또는 string sentinel
-- ARGV[3]: 기준값
-- ARGV[4]: expire epoch seconds

local value_kind = ARGV[1]
local field = ARGV[2]
local expected_value = ARGV[3]
local expire_at = tonumber(ARGV[4]) or 0

if value_kind == 'string' then
    local key_type = redis.call('TYPE', KEYS[1]).ok
    if key_type ~= 'none' and key_type ~= 'string' then
        redis.call('DEL', KEYS[1])
    end
    redis.call('SET', KEYS[1], ARGV[3])
elseif value_kind ~= 'hash' then
    return { 'ERROR', 'INVALID_VALUE_KIND' }
end

if value_kind == 'hash' then
    if not field or field == '' or field == '__value__' then
        return { 'ERROR', 'INVALID_HASH_FIELD' }
    end
    local key_type = redis.call('TYPE', KEYS[1]).ok
    if key_type ~= 'none' and key_type ~= 'hash' then
        redis.call('DEL', KEYS[1])
    end
    redis.call('HSET', KEYS[1], ARGV[2], ARGV[3])
end

if expire_at > 0 then
    redis.call('EXPIREAT', KEYS[1], expire_at)
end

return { 'CORRECTED' }
