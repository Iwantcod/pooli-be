# 트래픽 제너레이터 기능 구현 상세 계획

## 1. 문서 목적
- `docs/feat-traffic-generator-guide.md`의 확정 명세를 실제 개발 태스크로 분해한다.
- API 서버 역할과 트래픽 처리 서버 역할을 분리해 단계별 구현 순서를 정의한다.
- 구현 완료 판정 기준(DoD)을 각 단계에 명시한다.

## 2. 기준 명세(확정 사항)
- 기준 문서: `docs/feat-traffic-generator-guide.md`
- 프로파일: `local`, `api`, `traffic`
- `api` 프로파일:
  - 요청 수신 -> `traceId` 생성 -> Streams(MQ) 적재 수행
  - cache Redis 미접근, streams Redis 접근
- 일별 usage TTL: `일말 + 8h`
- API 서버 실패 응답코드: `TBD` (추후 확정)

## 3. 구현 단계(상세)

### 3.1 스펙 고정 및 태스크 분해
- 작업:
  - 명세 항목을 개발 체크리스트로 전환
  - 미확정 항목(`TBD`)을 backlog로 분리
- 완료 기준:
  - API/traffic 담당자가 동일 체크리스트를 기준으로 개발 가능

### 3.2 프로파일/설정 분리
- 작업:
  - `local`, `api`, `traffic` 프로파일별 Bean 활성/비활성 분리
  - Redis 3분리(세션/캐시/스트림) 설정 추가
  - `.env` 키 매핑 추가(`SESSION_*`, `CACHE_*`, `STREAMS_*`, `REDIS_NAMESPACE`)
- 완료 기준:
  - `api` 기동 시 producer 계층만 활성
  - `traffic` 기동 시 consumer + 차감 계층 활성

### 3.3 메시지 계약/DTO 고정
- 작업:
  - Streams payload DTO 정의
  - 필수 필드(`traceId`, `lineId`, `familyId`, `appId`, `apiTotalData`, `enqueuedAt`) 검증 추가
- 완료 기준:
  - API 직렬화 -> traffic 역직렬화가 스키마 오류 없이 동작

### 3.4 API 서버 엔드포인트 구현
- 작업:
  - 트래픽 발생 API 구현(요청 검증, traceId 생성, XADD 적재)
  - 성공 응답 `200` 고정
  - 실패 응답코드는 임시 처리 후 `TBD` 정책 확정 시 교체
- 완료 기준:
  - API 호출 시 Streams에 `payload` 레코드 생성 확인

### 3.5 Streams 소비 인프라 구현
- 작업:
  - Consumer Group bootstrap
  - `XREADGROUP ... BLOCK` 기반 지속 소비 구현
  - ACK/DLQ 유틸 분리
- 완료 기준:
  - 수신/ACK/재수신 기본 흐름 검증

### 3.6 Lua 스크립트 자산화
- 작업:
  - `deduct_indiv_tick.lua`
  - `deduct_shared_tick.lua`
  - `refill_gate.lua`
  - `lock_heartbeat.lua`
  - `lock_release.lua`
  - 스크립트 SHA preload 및 실행기 구현
- 완료 기준:
  - `EVALSHA` 호출 및 JSON 반환(`answer`,`status`) 파싱 성공

### 3.7 Redis 키/TTL/시간 규칙 구현
- 작업:
  - 키 네임스페이스 통일(`${REDIS_NAMESPACE}`)
  - 일별 키 만료: `EXPIREAT(일말 + 8h)`
  - 월별 키 만료: `EXPIREAT(월말 + 10d)`
  - 속도 버킷 키/만료 규칙:
    - `speed_bucket:individual:{lineId}:{epochSec}` (TTL=15초)
    - `speed_bucket:shared:{familyId}:{epochSec}` (TTL=15초)
  - lock 상수 적용:
    - `LOCK_TTL_MS=3000`
    - `LOCK_HEARTBEAT_MS=1000`
    - `INFLIGHT_TTL_SEC=60`
- 완료 기준:
  - 생성되는 키와 만료 시간이 명세와 동일

### 3.8 HYDRATE/REFILL 어댑터 구현
- 작업:
  - HYDRATE 반환 시 DB hydrate 후 동일 tick 1회 재시도
  - 매 tick 처리마다 `answer > 0`인 실제 차감량을 속도 버킷에 기록
    - 개인풀: lineId 버킷, 공유풀: familyId 버킷으로 분리 집계
    - 같은 초 다중 기록은 누적 증가(`INCRBY`)로 합산
  - NO_BALANCE 반환 시 refill gate -> lock 획득 시 DB refill -> 재호출
  - `refill_gate.lua` 호출 직전에 `delta/refill unit/redis threshold`를 최신 버킷으로 재계산
    - 10초 평균 산식: `delta = ceil(sum(answer) / 존재 버킷 수)`
    - fallback: 최근 10초 버킷 없음 -> TTL 내 전체 버킷 -> 없으면 `apiTotalData`
    - 계산식: `refillUnit=ceil(delta*10)`, `redisThreshold=ceil(delta*3)`
    - `delta=0` 또는 버킷 없음이면 `refillUnit=apiTotalData`, `redisThreshold=ceil(apiTotalData*0.3)`
    - `redisThreshold` 최소값은 1로 보정
  - DB refill은 `SELECT ... FOR UPDATE`로 row lock 후 `actual_refill_amount=min(refill_unit, db_remaining)` 계산
  - DB 차감 성공 시 같은 `actual_refill_amount`만 Redis에 충전
  - DB refill 수행 중 `LOCK_HEARTBEAT_MS=1000` 주기 heartbeat 유지
  - lock heartbeat/release 구현(실패/예외 포함 finally 해제)
  - 리필 관측 로그에 필수 필드 기록:
    - `traceId`, `delta`, `bucketCount`, `refillUnit`, `redisThreshold`,
      `actualRefill`, `dbRemainingBefore`, `dbRemainingAfter`
- 완료 기준:
  - HYDRATE, NO_BALANCE, WAIT, FAIL, SKIP 분기 시나리오 검증
  - DB 차감량과 Redis 충전량이 항상 동일함을 테스트로 보장
  - 10초 평균/계산/fallback 규칙이 테스트로 재현됨

### 3.9 10-tick 오케스트레이터 구현
- 작업:
  - tick을 초당 1개 슬롯으로 실행(총 10 tick 기준 10초 창)
  - `currentTickTargetData = ceil(apiRemainingData / remainingTicks)`
  - 개인풀 우선 -> 잔여(residual) 조건 시 공유풀 차감
  - 회복 불가 상태 즉시 종료
  - 조기 종료(`apiRemainingData == 0`) 구현
- 완료 기준:
  - `SUCCESS`, `PARTIAL_SUCCESS`, `FAILED` 상태 판정 재현

### 3.10 동시성/중복 처리 구현
- 작업:
  - `dedupe:run:{traceId}` 선점(SET NX EX=60)
  - `lineId + appId` 직렬 처리 락 도입
  - 재전달/재시도 시 동시 실행 차단
- 완료 기준:
  - 동일 traceId/동일 line+app 경쟁 상황에서 1건만 처리

### 3.11 DONE 영속화 및 ACK 순서 고정
- 작업:
  - 결과 영속 스키마(DONE) 생성
  - `trace_id UNIQUE` 기반 idempotency 보장
  - 저장 성공 후에만 `XACK`
- 완료 기준:
  - 영속 실패 시 ACK 미수행
  - 재처리 시 중복 차감 없음

### 3.12 재시도/리클레임/DLQ 구현
- 작업:
  - reclaim 보조 작업 구현
    - 구현 상세: `XPENDING + XCLAIM(min-idle)` 조합으로 idle pending 메시지 회수
  - `max retry` 초과 시 DLQ 이동
  - 역직렬화/검증 실패 즉시 DLQ 적재
- 완료 기준:
  - 유실 없이 `main -> retry -> DLQ` 흐름 검증

### 3.13 정책 변경 write-through 구현
- 작업:
  - 정책 DB 변경 직후 대상 Redis 키 즉시 갱신
    - 구현 상세: 트랜잭션 커밋 후(after-commit) `daily_total_limit`, `monthly_shared_limit`,
      `app_data_daily_limit`, `app_speed_limit`, `app_whitelist`, `immediately_block_end`,
      `repeat_block`, `policy:{policyId}`를 즉시 동기화
  - 실패 시 재시도/오류 로그 추가
- 완료 기준:
  - 정책 변경 후 다음 tick부터 Redis 반영 확인

### 3.14 테스트/릴리스
- 작업:
  - 단위 테스트: 분배 계산, 상태 전이, 종료 규칙
  - 통합 테스트: Redis + Streams + DB + Lua
  - 프로파일 테스트: `local`/`api`/`traffic` 부팅 검증
    - 구현 상세: `ApplicationContextRunner` 기반 트래픽 핵심 빈 프로파일 활성/비활성 매트릭스 테스트 추가
  - 점진 배포: API 먼저, 이후 traffic 배포
- 완료 기준:
  - 핵심 시나리오(정상/차단/한도/hydrate/refill/retry/dlq) 테스트 통과

### 3.15 refill ledger(outbox) 및 재처리 워커 구현
- 작업:
  - refill ledger 테이블 생성(`trace_id`, `tick`, `pool_type` UNIQUE)
  - 상태 컬럼 도입(`INIT`, `DB_DEDUCTED`, `REDIS_PENDING`, `REDIS_APPLIED`)
  - DB 차감 성공 후 Redis 충전 실패 시 `REDIS_PENDING` 기록
  - 백그라운드 워커가 `REDIS_PENDING`을 재처리해 Redis 충전 후 `REDIS_APPLIED`로 전이
- 완료 기준:
  - Redis 일시 장애 후에도 ledger 재처리로 최종 정합성 복구
  - 동일 `(trace_id, tick, pool_type)`에서 중복 DB 차감/중복 Redis 충전이 발생하지 않음

### 3.16 DB 예외/경계 조건 처리
- 작업:
  - DB deadlock/lock timeout만 최대 2회 재시도(짧은 backoff)
  - 비일시적 DB 오류는 즉시 실패 처리(추가 재시도 금지)
  - 월 경계는 `enqueuedAt` 기준 월 고정으로 DB/Redis 키 일치 강제
  - line/family 누락, soft-delete, 음수 잔량 비정상 데이터 처리 규칙 구현
- 완료 기준:
  - 예외/경계 케이스에서 오작동(중복 차감, 음수 잔량, 무한 재시도) 없음
  - 실패 시에도 lock 해제 및 후속 처리 가능 상태 유지

## 4. 권장 구현 순서(실행 우선순위)
1. 프로파일/설정 분리
2. API producer + 메시지 계약
3. traffic consumer 인프라
4. Lua + 오케스트레이터
5. DB refill 원자 차감(`SELECT ... FOR UPDATE`) + heartbeat
6. refill ledger(outbox) + 재처리 워커
7. DONE/ACK + 재시도/DLQ
8. 정책 write-through + 통합 테스트

## 5. 오픈 이슈
- API 실패 응답코드(`TBD`) 확정 필요:
  - 후보 예시: `400/401/403/500/503`
  - 확정 전까지는 내부 오류 중심 임시 정책으로 운영
