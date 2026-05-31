local amount = tonumber(ARGV[1])
local qos = tonumber(ARGV[2])
local expireAt = tonumber(ARGV[3])

if not amount or amount < -1 then
  return -1
end
if not qos or qos < 0 then
  return -1
end
if not expireAt or expireAt <= 0 then
  return -1
end

redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], 'amount', ARGV[1], 'qos', ARGV[2])
redis.call('EXPIREAT', KEYS[1], expireAt)
return 1
