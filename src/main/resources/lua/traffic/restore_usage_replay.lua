-- Redis 장애 복구 replay Lua.
-- KEYS[1]: idempotency key
-- KEYS[2]: individual remaining hash key
-- KEYS[3]: shared remaining hash key
-- KEYS[4]: daily total usage hash key
-- KEYS[5]: daily app usage hash key
-- KEYS[6]: daily shared usage hash key
-- KEYS[7]: monthly shared usage hash key
-- ARGV[1]: application id
-- ARGV[2]: individual usage bytes
-- ARGV[3]: shared usage bytes
-- ARGV[4]: qos usage bytes
-- ARGV[5]: expire epoch seconds

local idempotency_key = KEYS[1]

if redis.call('EXISTS', idempotency_key) == 1 then
    return { 'SKIPPED' }
end

local individual_usage = tonumber(ARGV[2]) or 0
local shared_usage = tonumber(ARGV[3]) or 0
local qos_usage = tonumber(ARGV[4]) or 0
local expire_at = tonumber(ARGV[5]) or 0

local function apply_remaining_delta(key, usage)
    if usage <= 0 then
        return nil
    end

    local amount = tonumber(redis.call('HGET', key, 'amount'))
    if amount == nil then
        return 'MISSING_REMAINING'
    end
    if amount == -1 then
        return nil
    end
    if amount < -1 then
        return 'INVALID_REMAINING'
    end

    local next_amount = amount - usage
    if next_amount < 0 then
        return 'NEGATIVE_REMAINING'
    end
    redis.call('HSET', key, 'amount', tostring(next_amount))
    return nil
end

local individual_error = apply_remaining_delta(KEYS[2], individual_usage)
if individual_error ~= nil then
    return { 'ERROR', individual_error }
end

local shared_error = apply_remaining_delta(KEYS[3], shared_usage)
if shared_error ~= nil then
    return { 'ERROR', shared_error }
end

local app_id = ARGV[1]
redis.call('HINCRBY', KEYS[4], 'individual', individual_usage)
redis.call('HINCRBY', KEYS[4], 'shared', shared_usage)
redis.call('HINCRBY', KEYS[4], 'qos', qos_usage)
redis.call('HINCRBY', KEYS[5], 'app:' .. app_id .. ':individual', individual_usage)
redis.call('HINCRBY', KEYS[5], 'app:' .. app_id .. ':shared', shared_usage)
redis.call('HINCRBY', KEYS[5], 'app:' .. app_id .. ':qos', qos_usage)
redis.call('HINCRBY', KEYS[6], 'shared', shared_usage)
redis.call('HINCRBY', KEYS[7], 'shared', shared_usage)

if expire_at > 0 then
    for i = 2, 7 do
        redis.call('EXPIREAT', KEYS[i], expire_at)
    end
end

redis.call('SET', idempotency_key, '1')
return { 'APPLIED' }
