-- KEYS[1]: remaining_* balance hash key
-- KEYS[2]: refill idempotency key
-- ARGV[1]: refill amount
-- ARGV[2]: balance key expireAt epoch seconds
-- ARGV[3]: refill uuid value to store in idempotency key
-- ARGV[4]: idempotency key ttl seconds

if redis.call('EXISTS', KEYS[2]) == 1 then
    return 0
end

local refillAmount = tonumber(ARGV[1])
if not refillAmount or refillAmount <= 0 then
    return -1
end

redis.call('HINCRBY', KEYS[1], 'amount', refillAmount)
redis.call('EXPIREAT', KEYS[1], ARGV[2])
redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4], 'NX')
return 1
