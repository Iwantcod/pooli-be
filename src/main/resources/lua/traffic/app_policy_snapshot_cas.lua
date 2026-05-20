local incoming = tonumber(ARGV[1])
if not incoming then
  return -1
end

local currentRaw = redis.call('HGET', KEYS[1], '__version')
if currentRaw then
  local current = tonumber(currentRaw)
  if current and incoming <= current then
    return 0
  end
end

local validKeys = {}
for i = 1, #KEYS do
  if KEYS[i] and KEYS[i] ~= '' then
    validKeys[#validKeys + 1] = KEYS[i]
  end
end
if #validKeys > 0 then
  redis.call('DEL', unpack(validKeys))
end

local dataPayload = cjson.decode(ARGV[2])
for field, value in pairs(dataPayload) do
  redis.call('HSET', KEYS[1], field, tostring(value))
end
redis.call('HSET', KEYS[1], '__version', ARGV[1])

local speedPayload = cjson.decode(ARGV[3])
for field, value in pairs(speedPayload) do
  redis.call('HSET', KEYS[2], field, tostring(value))
end

local whitelistPayload = cjson.decode(ARGV[4])
for _, member in ipairs(whitelistPayload) do
  redis.call('SADD', KEYS[3], tostring(member))
end

return 1
