-- in_flight_create_if_absent.lua
-- 목적:
-- - in-flight 멱등 hash를 "키가 없을 때만" 생성한다.
-- - created 여부(1/0)를 반환해 호출부가 최초 생성인지 판단할 수 있게 한다.
--
-- 플로우:
-- 1) KEYS[1] 존재 여부 확인
-- 2) 이미 존재하면 아무 변경 없이 0 반환
-- 3) 미존재면 HSET으로 processed_individual_data/processed_shared_data/retry_count를 한 번에 초기화
-- 4) 생성 완료를 의미하는 1 반환
--
-- 주의:
-- - TTL 설정은 하지 않는다(요구사항: TTL 없음).
-- KEYS[1]: dedupe key
-- ARGV[1]: processed_individual_data field name
-- ARGV[2]: processed_shared_data field name
-- ARGV[3]: retry_count field name
-- ARGV[4]: initial value
if redis.call('EXISTS', KEYS[1]) == 1 then
  return 0
end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[4], ARGV[2], ARGV[4], ARGV[3], ARGV[4])
return 1
