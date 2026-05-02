-- in_flight_increment_processed_with_init.lua
-- 목적:
-- - processedData를 delta만큼 증가시켜 "현재까지 처리한 데이터량"을 누적 기록한다.
-- - 키가 없을 경우 기본 필드를 초기화한 뒤 증가한다.
--
-- 플로우:
-- 1) KEYS[1] 존재 여부 확인
-- 2) 미존재면 HSET(processedData=0, retryCount=0)으로 기본값 초기화
-- 3) HINCRBY processedData delta 수행
-- 4) 증가 후 processedData를 반환
--
-- 주의:
-- - 이 스크립트는 processedData 증가 자체의 원자성만 보장한다.
-- - "차감 + processedData 갱신"을 동일 원자구간으로 보장하려면
--   차감 Lua 내부에 본 갱신을 포함하거나, 차감/갱신을 하나의 Lua로 통합해야 한다.
-- KEYS[1]: dedupe key
-- ARGV[1]: processedData field name
-- ARGV[2]: processedData initial value
-- ARGV[3]: retryCount field name
-- ARGV[4]: retryCount initial value
-- ARGV[5]: processedData delta
if redis.call('EXISTS', KEYS[1]) == 0 then
  redis.call('HSET', KEYS[1], ARGV[1], ARGV[2], ARGV[3], ARGV[4])
end
return redis.call('HINCRBY', KEYS[1], ARGV[1], ARGV[5])
