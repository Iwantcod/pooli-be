-- in_flight_increment_retry_with_init.lua
-- 목적:
-- - reclaim 시점의 retryCount를 1 증가한다.
-- - 키가 없을 경우 기본 필드를 먼저 초기화한 뒤 증가한다.
--
-- 플로우:
-- 1) KEYS[1] 존재 여부 확인
-- 2) 미존재면 HSET(processedData=0, retryCount=0)으로 기본값 초기화
-- 3) HINCRBY retryCount 1 수행
-- 4) 증가 후 retryCount를 반환
--
-- 주의:
-- - 이 스크립트는 retryCount 증가 자체의 원자성만 보장한다.
-- - 차감 로직과의 동일 원자구간은 별도 스크립트 통합이 필요하다.
-- KEYS[1]: dedupe key
-- ARGV[1]: processedData field name
-- ARGV[2]: processedData initial value
-- ARGV[3]: retryCount field name
-- ARGV[4]: retryCount initial value
if redis.call('EXISTS', KEYS[1]) == 0 then
  redis.call('HSET', KEYS[1], ARGV[1], ARGV[2], ARGV[3], ARGV[4])
end
return redis.call('HINCRBY', KEYS[1], ARGV[3], 1)
