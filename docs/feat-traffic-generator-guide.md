# **데이터 차감 플로우 최종 명세서 (Final v1.0)**

## **1) 목적**

- 범위:
- 10초 총량 요청 처리 방식
- Redis 데이터 모델/TTL 기준
- Lua 스크립트 종류/반환값/호출 순서
- 트래픽 처리 서버 오케스트레이션
- 실패/재시도/로그/중복 처리 규칙

## **2) 용어**

*델타: 최근 10초 간 발생한 평균 데이터 사용량*

- `tick`: 내부 처리 단위. 1초 고정.
- `api total data`: API 요청 본문에 포함된 향후 10초 총 데이터량(Byte).
- `api remaining data`: API 요청 전체 범위에서 아직 처리되지 않은 데이터량(Byte).
- `remaining_ticks`: 10초 윈도우에서 남아있는 tick 개수.
- `tick residual data`(기존 `remaining tick` 대체): 특정 tick 내부 미처리 데이터량(Byte).
- `traceId`: API 서버가 생성하여 MQ 메시지에 넣는 UUID.
- `refill unit`: 리필 단위(델타 * 10, 0이라면 api total data).
- `redis threshold`: 리필 임계치(델타 * 3, 0이라면 api total data의 30%).

## **3) 아키텍처**

API 서버:

- 요청별 traceId 생성
- MQ 적재 성공 시 200, 실패 시 500/403

트래픽 처리 서버:

- MQ 메시지 소비
- 1초 tick 단위 차감
- 최종 결과 영속 저장 후 XACK

저장소:

- Redis: 실시간 정책/카운터/in-flight
- 영속 저장소: 완료 이력(DONE), 최종 결과
- MongoDB 연동 전 임시 방안: MySQL trace_id UNIQUE

## **4) 입출력 계약**

### **4.1 MQ 메시지 필수 필드**

- traceId (string, uuid)
- lineId (long)
- familyId (long)
- appId (int)
- apiTotalData (long, Byte)
- enqueuedAt (epoch millis)

### **4.2 처리 결과 필드**

- traceId
- apiTotalData
- deductedTotalBytes
- apiRemainingData
- finalStatus (SUCCESS | PARTIAL_SUCCESS | FAILED)
- lastLuaStatus
- createdAt, finishedAt

## **5) Redis 키 구조**

### **5.1 전역 정책 종류 활성화**

- policy:{policyId} (string, ttl 없음)

### **5.2 제한/사용량**

- daily_total_limit:{lineId} (string, ttl 없음)
- app_data_daily_limit:{lineId} (hash, ttl 없음) -> limit:{appId}
- app_speed_limit:{lineId} (hash, ttl 없음) -> speed:{appId}
- app_whitelist:{lineId} (set, ttl 없음) -> {appId}
- monthly_shared_limit:{lineId} (string, ttl 없음)
- daily_total_usage:{lineId}:{yyyymmdd} (string, ttl 일말+12h)
- daily_app_usage:{lineId}:{yyyymmdd} (hash, ttl 일말+12h) -> app:{appId}
- monthly_shared_usage:{lineId}:{yyyymm} (string, ttl 월말+10d)

### **5.3 차단**

- immediately_block_end:{lineId} (string, ttl 없음)
- repeat_block:{lineId} (hash, ttl 없음)
- field: day:{day_num}:{repeat_block_id}
- value: "{start_at_sec}:{end_at_sec}"
- day 매핑: 0=SUN ... 6=SAT
- 구간 경계: [start_sec, end_sec] (양끝 포함)

### **5.4 잔량/리필/속도 누적**

- remaining_indiv_amount:{lineId}:{yyyymm} (hash, ttl 월말+10d)
    - amount
    - is_empty
- remaining_shared_amount:{familyId}:{yyyymm} (hash, ttl 월말+10d)
    - amount
    - is_empty
- indiv_refill_lock:{lineId} (string, ttl 3000ms, value=traceId)
- shared_refill_lock:{familyId} (string, ttl 3000ms, value=traceId)
- qos:{lineId} (string, ttl 없음)

key -> speed_bucket:individual:{lineId}:{saved sec}

value -> {used data}

### 5.4.1 최근 데이터 사용량 1초 단위 기록

### **5.5 in-flight dedupe(중복 처리 방지)**

- dedupe:run:{traceId} (string, ttl 60초)

## **6) 시간/TTL/타임존 규칙**

- 타임존: Asia/Seoul 고정
- 일별 키: EXPIREAT(일말 + 8시간)
- 월별 키: EXPIREAT(월말 + 10일)
- lock TTL: 3000ms
- lock heartbeat: 소유자만 1초 주기로 TTL 연장

상수 고정:

- INFLIGHT_TTL_SEC = 60
- APP_SPEED_USED_TTL_SEC = 3
- LOCK_TTL_MS = 3000
- LOCK_HEARTBEAT_MS = 1000
- HYDRATE_RETRY_MAX = 1

## **7) Lua 반환 계약**

- 모든 차감 Lua는 [answer, status]를 반환한다.
- answer: 현재 tick 실제 차감량(Byte)
- status:

| **코드** | **의미** | **설명** |
| --- | --- | --- |
| **OK** | 정상 처리 | 요청이 정상적으로 처리되었으며, 사용량도 허용 범위 내에 있음 |
| **NO_BALANCE** | 잔량 부족 | 현재 풀(개인 풀 또는 공유 풀)의 잔량이 부족한 상태. 이 경우에만 **리필(refill)** 수행 |
| **BLOCKED_IMMEDIATE** | 즉시 차단 | 즉시 차단 정책이 적용된 상태로 요청이 차단됨 |
| **BLOCKED_REPEAT** | 반복 차단 | 반복 차단 정책의 시간대에 해당하여 요청이 차단됨 |
| **HIT_DAILY_LIMIT** | 일 사용량 제한 도달 | 일일 총 사용량 제한에 도달하여 더 이상 요청 처리 불가 |
| **HIT_MONTHLY_SHARED_LIMIT** | 월 공유풀 제한 도달 | 공유 풀의 월 사용량 제한에 도달하여 요청 처리 불가 |
| **HIT_APP_DAILY_LIMIT** | 앱 일 사용량 제한 도달 | 특정 앱 기준의 일 사용량 제한에 도달 |
| **HIT_APP_SPEED** | 앱 속도 제한 | 앱 속도 제한 정책에 의해 허용량까지만 처리되고 나머지는 제한됨 |
| **HYDRATE** | Redis hydrate 필요 | Redis key가 존재하지 않아 DB에서 데이터를 조회하여 Redis에 hydrate 필요 |
| **ERROR** | 오류 발생 | 입력값 오류, 데이터 오류, 또는 시스템 내부 오류 발생 |

## **8) Lua 스크립트 목록**

| **스크립트** | **목적** | **주요 입력** | **주요 읽기/쓰기** | **반환** |
| --- | --- | --- | --- | --- |
| deduct_indiv_tick.lua | 개인풀 tick 차감 + 정책 검증 + usage 갱신 | lineId, appId, currentTickTargetData, nowSec, traceId | 개인풀 잔량, 차단/제한, 일사용량, 앱사용량, 속도누적 | [answer, status] |
| deduct_shared_tick.lua | 공유풀 tick 차감 + 정책 검증 + usage 갱신 | lineId, familyId, appId, currentTickTargetData, nowSec, traceId | 공유풀 잔량, 차단/제한, 일/월사용량, 앱사용량, 속도누적 | [answer, status] |
| refill_gate.lua | refill 필요 여부 + lock 획득 판정 | poolType, ownerId, threshold, traceId | 잔량, is_empty, lock 키 | FAIL/SKIP/OK/WAIT |
| lock_heartbeat.lua | lock 소유자 TTL 연장 | lockKey, traceId, ttlMs | lock 키 | 1(성공)/0(실패) |
| lock_release.lua | lock 소유자 해제 | lockKey, traceId | lock 키 | 1(해제)/0(무시) |

## **9) Lua 사용 순서**

1. 메시지 소비 후 dedupe:run:{traceId} 선점(SET NX EX).
2. tick 루프 시작 (tick=1..10).
3. tick 목표량 계산 후 deduct_indiv_tick.lua 호출.
4. 개인풀 status가 HYDRATE면 DB hydrate 후 같은 tick에서 개인풀 Lua 1회 재호출.
5. 개인풀 status가 NO_BALANCE면 refill_gate.lua 호출.
6. refill 결과가 OK면 DB refill + lock 유지(heartbeat) + 개인풀 Lua 1회 재호출.
7. 그래도 tick residual data > 0이고 개인풀 status가 NO_BALANCE면 deduct_shared_tick.lua 호출.
8. 공유풀도 HYDRATE/NO_BALANCE/refill 동일 규칙 적용.
9. tick 종료 후 api remaining data 감소.
10. 10 tick 종료 또는 조기 종료 시 최종 상태 저장.
11. 최종 상태 영속 저장 성공 후 XACK.
12. dedupe:run:{traceId} 정리.

## **10) tick 목표량 계산**

- 동적 분배:
- current_tick_target_data = ceil(api_remaining_data / remaining_ticks)
- 예: 103KB는 이상적인 경우 11,11,11,10,10,10,10,10,10,10

## **11) 정책 적용 순서**

### **11.1 공통**

- 화이트리스트면 차단 포함 모든 정책 우회 후 차감/집계만 수행
- 화이트리스트가 아니면 아래 순서대로 첫 제한에서 즉시 반환

### **11.2 개인풀**

- 즉시 차단 -> 반복 차단 -> 일 총 사용량 제한 -> 앱 일 사용량 제한 -> 앱 속도 제한

### **11.3 공유풀**

- 즉시 차단 -> 반복 차단 -> 일 총 사용량 제한 -> 월 공유풀 제한 -> 앱 일 사용량 제한 -> 앱 속도 제한

### **11.4 개인 데이터 차감 가능량 계산 및 차감량 반환 (상세)**

1. `currentTickTargetData < 0`이면 `[-1, ERROR]` 반환
2. `answer = min(개인풀 잔량, currentTickTargetData)`
3. `answer == 0`이면 `[0, NO_BALANCE]` 반환
4. 앱 화이트리스트 여부 확인
5. 화이트리스트 앱이면 11번으로 이동(차단/제한 정책 우회)
6. 즉시 차단 정책 적용 중이면 `[0, BLOCKED_IMMEDIATE]` 반환
7. 반복 차단 상태이면 `[0, BLOCKED_REPEAT]` 반환
8. 일 데이터 사용 제한 검증
- 정책 비활성 또는 제한값 `1`이면 다음 단계
- 아니라면 `answer = min(answer, max(0, 일 제한값 - 일 누적 사용량))`
- 이때 `answer == 0`이면 `[0, HIT_DAILY_LIMIT]` 반환
9. 앱 데이터 사용 제한 검증
- 정책 비활성 또는 제한값 `1`이면 다음 단계
- 아니라면 `answer = min(answer, max(0, 앱 일 제한값 - 앱 일 누적 사용량))`
- 이때 `answer == 0`이면 `[0, HIT_APP_DAILY_LIMIT]` 반환
10. 앱 속도 제한 검증
- 정책 비활성 또는 제한값 `1`이면 다음 단계
- 아니라면 `answer = min(answer, 초당 사용 가능 데이터량(Byte))`
- 이때 `answer == 0`이면 `[0, HIT_APP_SPEED]` 반환
11. `answer`만큼 개인풀 잔량 차감
12. `answer`만큼 `daily_total_usage` 증가
13. `answer`만큼 `daily_app_usage` 증가
14. `[answer, OK]` 반환

### **11.5 공유 데이터 차감 가능량 계산 및 차감량 반환 (상세)**

1. `currentTickTargetData < 0`이면 `[-1, ERROR]` 반환
2. `answer = min(공유풀 잔량, currentTickTargetData)`
3. `answer == 0`이면 `[0, NO_BALANCE]` 반환
4. 앱 화이트리스트 여부 확인
5. 화이트리스트 앱이면 12번으로 이동(차단/제한 정책 우회)
6. 즉시 차단 정책 적용 중이면 `[0, BLOCKED_IMMEDIATE]` 반환
7. 반복 차단 상태이면 `[0, BLOCKED_REPEAT]` 반환
8. 일 데이터 사용 제한 검증
- 정책 비활성 또는 제한값 `1`이면 다음 단계
- 아니라면 `answer = min(answer, max(0, 일 제한값 - 일 누적 사용량))`
- 이때 `answer == 0`이면 `[0, HIT_DAILY_LIMIT]` 반환
9. 월 공유풀 사용 제한 검증
- 정책 비활성 또는 제한값 `1`이면 다음 단계
- 아니라면 `answer = min(answer, max(0, 월 공유 제한값 - 월 공유 누적 사용량))`
- 이때 `answer == 0`이면 `[0, HIT_MONTHLY_SHARED_LIMIT]` 반환
10. 앱 데이터 사용 제한 검증
- 정책 비활성 또는 제한값 `1`이면 다음 단계
- 아니라면 `answer = min(answer, max(0, 앱 일 제한값 - 앱 일 누적 사용량))`
- 이때 `answer == 0`이면 `[0, HIT_APP_DAILY_LIMIT]` 반환
11. 앱 속도 제한 검증
- 정책 비활성 또는 제한값 `1`이면 다음 단계
- 아니라면 `answer = min(answer, 초당 사용 가능 데이터량(Byte))`
- 이때 `answer == 0`이면 `[0, HIT_APP_SPEED]` 반환
12. `answer`만큼 공유풀 잔량 차감
13. `answer`만큼 `daily_total_usage` 증가
14. `answer`만큼 `monthly_shared_usage` 증가
15. `answer`만큼 `daily_app_usage` 증가
16. `[answer, OK]` 반환

### **12) 데이터 리필량 계산**

참고 용어

- `refill unit`: 리필 단위(델타 * 10, 0이라면 api total data).
- `redis threshold`: 리필 임계치(델타 * 3, 0이라면 api total data의 30%).
1. answer의 status가 NO_BALANCE인 경우
    1. 차감량(answer)을 redis에 저장 (5.4.1)
        - tick은 같은 초에 동시 저장 가능
    2. 초 단위로 a의 정보를 가진 하나의 방(bucket)이 생성
    3. 버킷은 `EXPIRE`를 통해 생성된 지 15초가 지나면 자동으로 삭제
2. `remaining_indiv_amount`가 `refill unit`(델타 * 10, 현재 redis에 남은 데이터가 0이라면 api total data로 대체)의 임계점(=redis threshold) 밑으로 떨어졌을 때
    1. Redis(Lua 스크립트)는 애플리케이션(스프링) 서버 쪽으로 리필 신호 전송
3. redis에 버킷 정보가 있을 때
    1. 현재 시간 - 최근 10초(임의) 간의 버킷 정보 조회
        1. 10초 간의 정보가 없을 시 존재하는 모든 버킷 정보 조회
    2. a의 값으로 평균 계산 후 redis-refill unit에 적재
4. redis에 버킷 정보가 없을 때
    1. api total data 값을 redis-refill unit에 적재

## **13) 키 미존재/HYDRATE 규칙**

- key 미존재 시 Lua는 HYDRATE 반환
- 트래픽 처리 서버는 DB hydrate 후 같은 tick에서 1회 재시도
- 앱 정책 미존재는 무제한으로 간주
- hydrate 실패 또는 재실패 시 ERROR, 5xx 로그 기록 후 종료

## **14) 회복 불가 상태 처리(처리 불가 상태)**

- 아래 상태는 회복 불가로 간주하고 로그 후 즉시 종료:
- BLOCKED_IMMEDIATE
- BLOCKED_REPEAT
- HIT_DAILY_LIMIT
- HIT_MONTHLY_SHARED_LIMIT
- HIT_APP_DAILY_LIMIT
- ERROR

## **15) 동시성/정합성 규칙**

- Lua는 읽기/검증/차감/갱신을 원자 수행
- 직렬 처리 불변조건:
- 같은 `lineId + appId`는 동시에 1건만 처리한다.
- 재전달/재시도 상황에서도 동일 `lineId + appId` 동시 실행을 허용하지 않는다.
- 하나의 데이터 처리 요청(10 tick)은 단일 스레드에서 순차 실행한다.
- dedupe는 Redis(in-flight) + 영속 저장소(DONE) 이중 보장
- `XACK`는 반드시 DONE 영속 저장 이후 수행

## **16) 최종 데이터 차감 플로우 (개발 착수용)**

1. API 서버가 요청 수신 후 traceId 생성, MQ 적재.
2. 트래픽 처리 서버가 메시지 소비, in-flight dedupe 선점.
3. api_remaining_data = api_total_data 초기화.
4. tick=1..10 반복:
5. remaining_ticks 계산.
6. current_tick_target_data 계산.
7. 개인풀 Lua 실행.
8. HYDRATE면 hydrate + 개인풀 재시도 1회.
9. NO_BALANCE면 refill gate 실행.
10. OK로 lock 획득 시 DB refill 수행(heartbeat 포함) 후 개인풀 재시도 1회.
11. 개인풀 후 tick_residual_data > 0이고 status가 NO_BALANCE면 공유풀 Lua 실행.
12. 공유풀에서도 HYDRATE/refill 동일 처리.
13. 해당 tick 차감량만큼 api_remaining_data 감소.
14. 회복 불가 상태면 즉시 종료.
15. api_remaining_data == 0이면 조기 종료.
16. 루프 종료 후 결과 상태 계산:
    - api_remaining_data == 0 -> SUCCESS
    - api_remaining_data > 0 -> PARTIAL_SUCCESS
    - 시스템 예외 -> FAILED
17. 최종 결과를 영속 저장소에 기록.
18. 영속 저장 성공 후 XACK.
19. in-flight dedupe 정리.

### 16) 수정본(가독성 향상)

1. 트래픽 처리 서버가 MQ 메시지(traceId, lineId, familyId, appId, apiTotalData)를 수신한다.
2. dedupe:run:{traceId}를 선점한다. 실패 시 중복 처리 정책에 따라 스킵/종료한다.
3. api_remaining_data = apiTotalData로 초기화한다.
4. tick=1..10 루프를 시작한다.
5. remaining_ticks를 계산하고 current_tick_target_data = ceil(api_remaining_data / remaining_ticks)를 구한다.
6. `deduct_indiv_tick.lua`를 호출한다.
7. Lua는 원자적으로 검증 -> answer 계산 -> 차감/집계 -> [answer,status] 반환을 수행한다.
8. 개인풀 결과가 HYDRATE면 DB hydrate 후 같은 tick에서 `deduct_indiv_tick.lua` 1회 재호출한다.
9. 개인풀 결과가 NO_BALANCE면 `refill_gate.lua`를 호출한다.
10. refill 결과가 OK면 DB refill 수행 후(필요 시 heartbeat) 같은 tick에서 `deduct_indiv_tick.lua` 1회 재호출한다.
11. 개인풀 처리 후 tick_residual_data = current_tick_target_data - indiv_answer_total을 계산한다.
12. tick_residual_data > 0이고 개인풀 최종 status가 NO_BALANCE인 경우에만 `deduct_shared_tick.lua`를 호출한다.
13. 공유풀도 동일하게 HYDRATE -> hydrate 1회 재호출, NO_BALANCE -> refill_gate -> 필요 시 재호출 규칙을 적용한다.
14. 공유풀까지 끝난 뒤 해당 tick의 실제 총 차감량(개인+공유)만큼 api_remaining_data를 감소시킨다.
15. 최종 status가 BLOCKED_*, HIT_*, ERROR이면 로그를 남기고 즉시 종료한다.
16. api_remaining_data == 0이면 조기 종료한다.
17. 10 tick 종료 후 api_remaining_data == 0이면 SUCCESS, 아니면 PARTIAL_SUCCESS, 시스템 예외면 FAILED를 결정한다.
18. 최종 결과를 영속 저장소에 저장한다(DONE).
19. DONE 저장 성공 후 XACK한다.
20. dedupe:run:{traceId}를 정리하고 종료한다.

## **17) 프로파일 운영 규칙 (추가 확정)**

- 프로파일 종류: `local`, `api`, `traffic`
- `local` 프로파일: 개발용. API 서버 역할 + 트래픽 처리 서버 역할을 모두 수행한다.
- `api` 프로파일: API 서버 역할만 수행한다.
    - 요청 수신 -> `traceId` 생성 -> MQ 적재까지 수행한다.
    - cache용 Redis, streams용 Redis에는 접근하지 않는다.
    - 세션용 Redis는 별도 인프라이며 기존 설정대로 사용한다.
- `traffic` 프로파일: 트래픽 처리 서버 역할만 수행한다.
    - MQ/Streams 소비, 1초 tick 차감 오케스트레이션, DONE 저장, XACK 수행
    - cache용 Redis, streams용 Redis 모두 접근한다.
- 배포 방식: 동일 애플리케이션 아티팩트를 프로파일만 다르게 실행한다.
    - 예: `SPRING_PROFILES_ACTIVE=api` 또는 `SPRING_PROFILES_ACTIVE=traffic`

## **18) Redis 인프라 분리 및 설정 키 (추가 확정)**

- Redis 인프라는 아래 3개를 완전히 분리한다.
    - 세션용 Redis
    - 캐시/정책/카운터용 Redis
    - Streams용 Redis
- 설정 값은 모두 `.env` 환경변수로 주입한다.
- 공통 키 네임스페이스(prefix)는 통일한다.
    - 예: `${REDIS_NAMESPACE}`
    - 키 예시: `${REDIS_NAMESPACE}:policy:{policyId}`, `${REDIS_NAMESPACE}:dedupe:run:{traceId}`

권장 프로퍼티 키(값은 `${필드명}` 주입):

- 세션 Redis
    - `spring.data.redis.host: ${SESSION_REDIS_HOST}`
    - `spring.data.redis.port: ${SESSION_REDIS_PORT}`
    - `spring.data.redis.password: ${SESSION_REDIS_PASSWORD}`
- 캐시 Redis
    - `app.redis.cache.host: ${CACHE_REDIS_HOST}`
    - `app.redis.cache.port: ${CACHE_REDIS_PORT}`
    - `app.redis.cache.password: ${CACHE_REDIS_PASSWORD}`
- Streams Redis
    - `app.redis.streams.host: ${STREAMS_REDIS_HOST}`
    - `app.redis.streams.port: ${STREAMS_REDIS_PORT}`
    - `app.redis.streams.password: ${STREAMS_REDIS_PASSWORD}`

## **19) Streams 소비/ACK 전략 (추가 확정)**

- 메인 소비는 스케줄러 기반 n초 폴링이 아니라, **블로킹 기반 지속 소비**를 기본으로 한다.
    - 권장: `XREADGROUP ... BLOCK <ms>`
- 스케줄러는 보조 작업(예: pending reclaim/XAUTOCLAIM) 용도로만 사용한다.
- ACK 순서 고정:
    - DONE 영속 저장 성공 후 `XACK`
    - 실패 시 `XACK` 금지

## **20) Streams 권장 초기값 (추가 확정)**

- stream key: `${STREAMS_KEY_TRAFFIC_REQUEST}` (예: `traffic:deduct:request`)
- consumer group: `${STREAMS_GROUP_TRAFFIC}` (예: `traffic-deduct-cg`)
- consumer name: `${STREAMS_CONSUMER_NAME}` (예: `${HOSTNAME}-${SERVER_PORT}`)
- read batch size: `${STREAMS_READ_COUNT}` (권장 초기값: `20`)
- read block ms: `${STREAMS_BLOCK_MS}` (권장 초기값: `2000`)
- reclaim interval ms: `${STREAMS_RECLAIM_INTERVAL_MS}` (권장 초기값: `5000`)
- reclaim min idle ms: `${STREAMS_RECLAIM_MIN_IDLE_MS}` (권장 초기값: `70000`)
    - 근거: `INFLIGHT_TTL_SEC=60`보다 여유를 둬 중복 경쟁을 줄인다.
- max retry: `${STREAMS_MAX_RETRY}` (권장 초기값: `3`)
- DLQ key: `${STREAMS_KEY_TRAFFIC_DLQ}` (예: `traffic:deduct:dlq`)

## **21) Streams JSON 메시지 처리 규칙 (추가 확정)**

- Streams 메시지는 JSON 기반으로 처리한다.
- 권장 저장 형식:
    - field: `payload`
    - value: JSON 문자열
- `payload` JSON에는 최소 아래 필드를 포함한다.
    - `traceId`, `lineId`, `familyId`, `appId`, `apiTotalData`, `enqueuedAt`
- 트래픽 처리 서버는 아래 순서로 처리한다.
    1. Streams 레코드 수신
    2. `payload` 문자열 추출
    3. DTO 역직렬화(JSON -> 객체)
    4. 필수 필드 검증
    5. 차감 로직 실행
    6. DONE 저장 성공 시 `XACK`
    7. 역직렬화/검증 실패 또는 재시도 초과 시 DLQ 적재

## **22) 정책 변경 시 캐시 갱신 규칙 (추가 확정)**

- 정책 변경 시 Redis 반영은 **즉시 갱신**이 원칙이다.
- 변경 대상 정책 키는 지연 없이 갱신(write-through 또는 동등한 즉시 반영 방식)한다.
- 즉시 갱신 실패 시 재시도/오류 로그를 남기고, 불일치 상태를 방치하지 않는다.
