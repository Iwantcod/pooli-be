-- deduct_shared_tick.lua
-- 최소 계약 스캐폴드:
--   반환 형식: {"answer":number,"status":"..."} JSON 문자열
-- 현재 버전은 공유풀 잔량 키 기준으로 개인풀 로직을 동일하게 적용합니다.

-- 단계 0) 보조 함수: 모든 분기에서 동일한 반환 계약을 유지합니다.
local function as_json(answer, status)
  return cjson.encode({ answer = answer, status = status })
end

-- 단계 1) 입력값을 읽습니다.
local remaining_key = KEYS[1]
local target_data = tonumber(ARGV[1])

-- 단계 2) 필수 키를 검증합니다.
-- 잔량 해시 키가 없으면 잘못된 호출로 판단합니다.
if not remaining_key or remaining_key == "" then
  return as_json(-1, "ERROR")
end

-- 단계 3) 차감 목표량을 검증합니다.
-- 음수이거나 숫자로 해석되지 않으면 잘못된 입력입니다.
if not target_data or target_data < 0 then
  return as_json(-1, "ERROR")
end

-- 단계 4) 현재 공유풀 잔량을 조회합니다.
-- amount가 없으면 아직 Redis hydrate가 되지 않은 상태입니다.
local current_amount = tonumber(redis.call("HGET", remaining_key, "amount") or "-1")
if current_amount < 0 then
  return as_json(0, "HYDRATE")
end

-- 단계 5) 현재 tick에서 실제 차감 가능한 바이트를 계산합니다.
local answer = math.min(current_amount, target_data)
if answer <= 0 then
  -- 현재 풀에서 차감 가능한 잔량이 없습니다.
  redis.call("HSET", remaining_key, "is_empty", "1")
  return as_json(0, "NO_BALANCE")
end

-- 단계 6) Redis에서 잔량을 원자적으로 차감합니다.
redis.call("HINCRBY", remaining_key, "amount", -answer)

-- 단계 7) 차감 후 잔량 기준으로 is_empty 플래그를 갱신합니다.
local remaining_after = current_amount - answer
if remaining_after <= 0 then
  redis.call("HSET", remaining_key, "is_empty", "1")
else
  redis.call("HSET", remaining_key, "is_empty", "0")
end

-- 단계 8) 정상 차감 결과를 반환합니다.
return as_json(answer, "OK")
