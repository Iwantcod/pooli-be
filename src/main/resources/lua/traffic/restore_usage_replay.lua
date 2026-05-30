-- Redis 장애 복구 replay Lua.
-- KEYS[1]: idempotency key
-- KEYS[2]: individual remaining hash key
-- KEYS[3]: shared remaining hash key
-- KEYS[4]: daily total usage string key
-- KEYS[5]: daily app usage hash key
-- KEYS[6]: daily shared usage hash key
-- KEYS[7]: monthly shared usage hash key
-- ARGV[1]: application id
-- ARGV[2]: individual usage bytes
-- ARGV[3]: shared usage bytes
-- ARGV[4]: qos usage bytes
-- ARGV[5]: expire epoch seconds
-- ARGV[6]: family id

local idempotency_key = KEYS[1]

if redis.call('EXISTS', idempotency_key) == 1 then
    return { 'SKIPPED' }
end

local app_id = ARGV[1]
local individual_usage = tonumber(ARGV[2]) or 0
local shared_usage = tonumber(ARGV[3]) or 0
local qos_usage = tonumber(ARGV[4]) or 0
local expire_at = tonumber(ARGV[5]) or 0
local family_id = tonumber(ARGV[6]) or 0
local total_usage = individual_usage + shared_usage + qos_usage

if individual_usage < 0 or shared_usage < 0 or qos_usage < 0 then
    return { 'ERROR', 'NEGATIVE_USAGE' }
end
if shared_usage > 0 and family_id <= 0 then
    return { 'ERROR', 'MISSING_FAMILY_ID' }
end

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

if total_usage > 0 then
    redis.call('INCRBY', KEYS[4], total_usage)
end
if individual_usage > 0 then
    redis.call('HINCRBY', KEYS[5], 'app:' .. app_id .. ':individual', individual_usage)
end
if shared_usage > 0 then
    redis.call('HINCRBY', KEYS[5], 'app:' .. app_id .. ':shared', shared_usage)
    redis.call('HINCRBY', KEYS[6], 'usage_amount', shared_usage)
    redis.call('HSET', KEYS[6], 'family_id', family_id)
    redis.call('HINCRBY', KEYS[7], 'usage_amount', shared_usage)
    redis.call('HSET', KEYS[7], 'family_id', family_id)
end
if qos_usage > 0 then
    redis.call('HINCRBY', KEYS[5], 'app:' .. app_id .. ':qos', qos_usage)
end

if expire_at > 0 then
    redis.call('EXPIREAT', KEYS[2], expire_at)
    redis.call('EXPIREAT', KEYS[3], expire_at)
    if total_usage > 0 then
        redis.call('EXPIREAT', KEYS[4], expire_at)
    end
    if individual_usage > 0 or shared_usage > 0 or qos_usage > 0 then
        redis.call('EXPIREAT', KEYS[5], expire_at)
    end
    if shared_usage > 0 then
        redis.call('EXPIREAT', KEYS[6], expire_at)
        redis.call('EXPIREAT', KEYS[7], expire_at)
    end
end

redis.call('SET', idempotency_key, '1')
return { 'APPLIED' }
