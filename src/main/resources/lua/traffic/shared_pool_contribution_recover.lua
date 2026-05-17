-- shared_pool_contribution_recover.lua
-- KEYS[1]: metadata hash key
-- KEYS[2]: individual balance hash key
-- KEYS[3]: shared balance hash key
-- ARGV[1]: individual_unlimited (1/0)

local metadata_key = KEYS[1]
local individual_key = KEYS[2]
local shared_key = KEYS[3]
local individual_unlimited = ARGV[1] == "1"

-- 단계 1) Java 호출자가 파싱하기 쉬운 고정 JSON 응답을 구성합니다.
local function as_json(status, individual_applied, shared_applied)
  return "{\"status\":\"" .. status .. "\",\"individualApplied\":" .. individual_applied
      .. ",\"sharedApplied\":" .. shared_applied .. "}"
end

-- 단계 2) metadata hash의 applied flag를 읽고, 누락된 flag는 미적용(0)으로 취급합니다.
local function current_applied(field)
  local value = redis.call("HGET", metadata_key, field)
  if value == false or value == nil then
    return "0"
  end
  return value
end

-- 단계 3) 필수 key가 비어 있으면 Redis 상태를 변경하지 않고 실패 코드만 반환합니다.
if not metadata_key or metadata_key == ""
    or not individual_key or individual_key == ""
    or not shared_key or shared_key == "" then
  return as_json("INVALID_ARGUMENT", 0, 0)
end

-- 단계 4) 복구 경로는 metadata가 있을 때만 Redis 보정을 수행합니다.
-- metadata가 없으면 최초 Redis 적용 자체가 없었던 요청으로 해석할 수 있습니다.
if redis.call("EXISTS", metadata_key) == 0 then
  return as_json("METADATA_MISSING", 0, 0)
end

-- 단계 5) 복구 amount는 outbox payload가 아니라 metadata hash의 amount를 기준으로 합니다.
local amount = tonumber(redis.call("HGET", metadata_key, "amount"))
if not amount or amount <= 0 then
  return as_json("INVALID_ARGUMENT", 0, 0)
end

-- 단계 6) 기존 applied flag를 읽어 이미 반영된 Redis 변경을 다시 수행하지 않습니다.
local individual_applied = current_applied("individual_applied")
local shared_applied = current_applied("shared_applied")

-- 단계 7) 개인 Redis balance key가 있고 아직 미반영이면 amount 형식과 잔량 충분 여부를 사전 검증합니다.
if individual_applied == "0" and not individual_unlimited and redis.call("EXISTS", individual_key) == 1 then
  local individual_amount = tonumber(redis.call("HGET", individual_key, "amount"))
  if not individual_amount or individual_amount < -1 then
    return as_json("INVALID_ARGUMENT", 0, 0)
  end
  if individual_amount ~= -1 and individual_amount < amount then
    return as_json("INSUFFICIENT_INDIVIDUAL", 0, 0)
  end
end

-- 단계 8) 공유 Redis balance key가 있고 아직 미반영이면 amount 형식을 사전 검증합니다.
if shared_applied == "0" and redis.call("EXISTS", shared_key) == 1 then
  local shared_amount = tonumber(redis.call("HGET", shared_key, "amount"))
  if not shared_amount or shared_amount < -1 then
    return as_json("INVALID_ARGUMENT", 0, 0)
  end
end

-- 단계 9) 개인풀 Redis key가 있으면 applied flag가 0일 때만 차감합니다.
-- 무제한 회선은 개인 amount를 바꾸지 않고 개인 반영 완료로 표시합니다.
if individual_unlimited then
  redis.call("HSET", metadata_key, "individual_applied", "1")
elseif individual_applied == "0" and redis.call("EXISTS", individual_key) == 1 then
  local individual_amount = tonumber(redis.call("HGET", individual_key, "amount"))
  if individual_amount ~= -1 then
    redis.call("HINCRBY", individual_key, "amount", -amount)
  end
  redis.call("HSET", metadata_key, "individual_applied", "1")
end

-- 단계 10) 공유풀 Redis key가 있으면 applied flag가 0일 때만 보충합니다.
if shared_applied == "0" and redis.call("EXISTS", shared_key) == 1 then
  local shared_amount = tonumber(redis.call("HGET", shared_key, "amount"))
  if shared_amount ~= -1 then
    redis.call("HINCRBY", shared_key, "amount", amount)
  end
  redis.call("HSET", metadata_key, "shared_applied", "1")
end

-- 단계 11) 최종 applied flag를 반환해 scheduler가 MySQL 반영 가능 여부를 판단할 수 있게 합니다.
return as_json("APPLIED", current_applied("individual_applied"), current_applied("shared_applied"))
