-- shared_pool_contribution_apply.lua
-- KEYS[1]: metadata hash key
-- KEYS[2]: individual balance hash key
-- KEYS[3]: shared balance hash key
-- ARGV[1]: trace_id
-- ARGV[2]: amount
-- ARGV[3]: individual_unlimited (1/0)

local metadata_key = KEYS[1]
local individual_key = KEYS[2]
local shared_key = KEYS[3]
local trace_id = ARGV[1]
local amount = tonumber(ARGV[2])
local individual_unlimited = ARGV[3] == "1"

-- 단계 1) Java 호출자가 파싱하기 쉬운 고정 JSON 응답을 구성합니다.
local function as_json(status, individual_applied, shared_applied)
  return "{\"status\":\"" .. status .. "\",\"individualApplied\":" .. individual_applied
      .. ",\"sharedApplied\":" .. shared_applied .. "}"
end

-- 단계 2) 필수 key/argument가 비어 있거나 amount가 양수가 아니면 실행하지 않습니다.
local function invalid_input()
  return not metadata_key or metadata_key == ""
      or not individual_key or individual_key == ""
      or not shared_key or shared_key == ""
      or not trace_id or trace_id == ""
      or not amount or amount <= 0
end

-- 단계 3) metadata hash의 applied flag를 읽고, 누락된 flag는 미적용(0)으로 취급합니다.
local function current_applied(field)
  local value = redis.call("HGET", metadata_key, field)
  if value == false or value == nil then
    return "0"
  end
  return value
end

-- 단계 4) 잘못된 입력은 Redis 상태를 변경하지 않고 실패 코드만 반환합니다.
if invalid_input() then
  return as_json("INVALID_ARGUMENT", 0, 0)
end

-- 단계 5) 기존 metadata가 있으면 같은 trace/amount의 즉시 재시도인지 확인하고 applied flag를 재사용합니다.
local metadata_exists = redis.call("EXISTS", metadata_key) == 1
local individual_applied = "0"
local shared_applied = "0"

if metadata_exists then
  local existing_trace_id = redis.call("HGET", metadata_key, "trace_id")
  local existing_amount = tonumber(redis.call("HGET", metadata_key, "amount"))
  if existing_trace_id ~= trace_id or existing_amount ~= amount then
    return as_json("METADATA_CONFLICT", 0, 0)
  end
  individual_applied = current_applied("individual_applied")
  shared_applied = current_applied("shared_applied")
end

-- 단계 6) 개인 Redis balance key가 있을 때만 개인 amount 형식과 잔량 충분 여부를 사전 검증합니다.
-- 이 검증은 metadata 생성 전 수행되어 부족 잔량에서 부분 metadata가 남지 않게 합니다.
if individual_applied == "0" and not individual_unlimited and redis.call("EXISTS", individual_key) == 1 then
  local individual_amount = tonumber(redis.call("HGET", individual_key, "amount"))
  if not individual_amount or individual_amount < -1 then
    return as_json("INVALID_ARGUMENT", 0, 0)
  end
  if individual_amount ~= -1 and individual_amount < amount then
    return as_json("INSUFFICIENT_INDIVIDUAL", 0, 0)
  end
end

-- 단계 7) 공유 Redis balance key가 있을 때만 공유 amount 형식을 사전 검증합니다.
-- 이 검증도 metadata 생성 전 수행되어 개인 차감 후 공유 보충 실패가 남지 않게 합니다.
if shared_applied == "0" and redis.call("EXISTS", shared_key) == 1 then
  local shared_amount = tonumber(redis.call("HGET", shared_key, "amount"))
  if not shared_amount or shared_amount < -1 then
    return as_json("INVALID_ARGUMENT", 0, 0)
  end
end

-- 단계 8) 최초 실행이면 trace/amount와 applied flag 기본값을 metadata hash에 기록합니다.
if not metadata_exists then
  redis.call(
      "HSET",
      metadata_key,
      "trace_id", trace_id,
      "amount", ARGV[2],
      "individual_applied", "0",
      "shared_applied", "0"
  )
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
-- 공유 amount가 -1 sentinel이면 값을 바꾸지 않고 반영 완료로 표시합니다.
if shared_applied == "0" and redis.call("EXISTS", shared_key) == 1 then
  local shared_amount = tonumber(redis.call("HGET", shared_key, "amount"))
  if shared_amount ~= -1 then
    redis.call("HINCRBY", shared_key, "amount", amount)
  end
  redis.call("HSET", metadata_key, "shared_applied", "1")
end

-- 단계 11) 최종 applied flag를 반환해 호출자가 Redis 반영 상태를 판단할 수 있게 합니다.
return as_json("APPLIED", current_applied("individual_applied"), current_applied("shared_applied"))
