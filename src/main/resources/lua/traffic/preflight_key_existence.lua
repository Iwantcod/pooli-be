-- 차감 preflight에서 필요한 Redis key 존재 여부를 한 번의 Lua 호출로 확인한다.
-- KEYS[1]: line policy ready key
-- KEYS[2]: individual remaining balance key
-- KEYS[3]: shared remaining balance key
-- 반환: {policy_ready, individual_balance_exists, shared_balance_exists}

return {
    redis.call('EXISTS', KEYS[1]),
    redis.call('EXISTS', KEYS[2]),
    redis.call('EXISTS', KEYS[3])
}
