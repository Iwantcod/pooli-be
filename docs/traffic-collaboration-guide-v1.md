# 트래픽 제너레이터 3인 병렬 고도화 통합 가이드 v1

## 0. 문서 메타데이터

| 항목 | 값 |
| --- | -- |
| 문서 버전 | v1 |
| 문서 상태 | `Contract v1 Locked` |
| 기준 타임존 | `Asia/Seoul` |
| 작성일 | `2026-03-12` |
| 목표 완료일 | `2026-03-15` |
| 문서 성격 | 개발/테스트/운영 통합 가이드 |

## 1. 문서 목적 및 프로젝트 현황 (요약)

이 문서는 트래픽 제너레이터 고도화 작업을 3개 Plane(A/B/C)으로 분리해 병렬 개발 충돌을 줄이고, Contract Lock으로 경계와 책임을 통제해 정합성 붕괴 리스크를 최소화하기 위한 통합 지침서다.

목표:
- Plane 경계 고정
- Contract v1 잠금 유지
- Big Step 일정 기반 병렬 고도화
- E2E + HA 장애 복구 검증 완료

현재 구현 상태(요약):
- Lua 스크립트 5종 완료: `deduct_indiv_tick`, `deduct_shared_tick`, `refill_gate`, `lock_heartbeat`, `lock_release`
- Redis 3분리 설정 완료: Session / Cache / Streams
- 모듈 골격 완료:
  - Traffic 모듈 약 40개 클래스
  - Metrics 모듈 7개 클래스
  - Policy 모듈(Admin/User) 완성

## 2. 공통 협업 원칙 (Lock Rules)

- 계약 잠금 전에는 Plane 내부 구현만 진행한다.
- 계약 잠금 후에만 Plane 경계 로직을 구현한다.
- Contract v1은 추가만 허용한다.
- Contract v1의 필드 삭제/의미 변경은 금지한다.
- 브레이킹 변경은 반드시 Contract v2로 분리한다.
- 변경 배포는 주 1회 배치를 원칙으로 한다. 긴급 hotfix만 예외다.

## 3. Big Step 일정

| 단계 | 일정(절대 날짜) | 목표 |
| --- | --- | --- |
| 사전 단계 | `2026-03-12` (30분) | 기존 DTO/Enum/Interface와 계약 일치 여부 확인 후 잠금 |
| Step 1 | `~2026-03-13` | 테스트 보강 + 실패 로깅 정리 |
| Step 2 | `~2026-03-14` | 성능 고도화 + 안정성 보강 |
| Step 3 | `~2026-03-15` | AWS `n100000` E2E + HA 복구 검증 |

Step별 핵심:
- Step 1:
  - A: Lua 반환 계약, 키/TTL, lock/dedupe 테스트
  - B: Streams 소비/ACK/DLQ, payload 검증 테스트
  - C: 이벤트 단일 사이클(개인풀 1회 -> residual -> 조건부 공유풀 1회) + HYDRATE/REFILL 테스트
  - 공통: 실패 로그 구현, 불필요한 성공 로그 제거
- Step 2:
  - A: `EVALSHA` TPS 최적화, Redis 장애 시 락/중복 반영 방지
  - B: 소비기 스케일아웃, 커넥션풀/리클레임 안정화
  - C: hydrate/refill 경합 안정화, 최종 상태 판정 회귀 강화
  - 공통: 로컬 TPS `n100 -> n10000` 정합성 재검증
- Step 3:
  - 공통: AWS `n100000` E2E, 장애 주입 기반 HA 복구 검증

## 4. Plane 소유권 매트릭스

| Plane | 역할명 | 소유 책임 | 비소유 책임 |
| --- | --- | --- | --- |
| A | Runtime State Owner | Redis/Lua 실행 계약, 키/TTL 규칙, lock/dedupe, speed bucket, policy write-through | 최종 비즈니스 판정, DB refill 원자 처리 |
| B | Intake & Delivery Owner | API 수신, traceId 부여, Streams 적재/소비, payload 검증, reclaim/DLQ, DONE 저장 후 ACK 보장 | 차감/리필 의사결정, Redis 키/TTL 정책 계산 |
| C | Decision & Consistency Owner | 이벤트 단일 사이클 오케스트레이션(개인풀 1회 -> residual -> 조건부 공유풀 1회), HYDRATE/REFILL 경로, 최종 상태 판정 | Streams 그룹 운영, Lua preload/실행 인프라 |

소유권 고정:
- ACK/DONE 순서 보장: Plane B
- 최종 상태 판정: Plane C
- Redis 키/정책 규칙: Plane A

### 현재 데이터 처리 로직 요약 (코드 기준)

1. API 요청마다 `TrafficRequestEnqueueService`가 `traceId`/`enqueuedAt`을 보강해 Streams에 메시지 1건을 적재한다.
2. `TrafficStreamConsumerRunner`가 메시지를 읽고 payload 검증/역직렬화 후 `traceId` 중복 완료 여부를 먼저 확인한다.
3. 완료 이력이 없으면 `TrafficInFlightDedupeService`로 in-flight 선점을 수행한 뒤 오케스트레이터를 실행한다.
4. `TrafficDeductOrchestratorService`는 이벤트 1건을 단일 사이클로 처리한다.
5. 처리 순서는 `개인풀 1회 차감 -> residual 계산 -> residual > 0 && NO_BALANCE일 때만 공유풀 1회 차감`이다.
6. 각 풀 차감 내부는 `HYDRATE 1회 복구 -> REFILL gate/lock/DB claim/Redis refill -> 동일 요청량 1회 재차감` 순서를 유지한다.
7. 결과는 `TrafficDeductDoneLogService`에 저장 성공 후에만 ACK하며, 저장 실패 시 ACK하지 않아 재전달/reclaim으로 복구한다.
8. pending 메시지는 `TrafficStreamReclaimService`가 `min-idle/max-retry` 기준으로 재처리 또는 DLQ로 분기한다.

## 4.1 서비스/테스트 패키지 역할 매핑 (구현 반영)

현재 코드베이스는 `traffic.service`를 아래 4개 하위 패키지로 분리해 관리한다.

| 패키지 | 연관 Plane | 역할 요약 | 대표 클래스 |
| --- | --- | --- | --- |
| `com.pooli.traffic.service.runtime` | Plane A | Redis/Lua 실행 계약, 키/TTL 규칙, dedupe/버킷 관리 | `TrafficLuaScriptInfraService`, `TrafficRedisKeyFactory`, `TrafficRecentUsageBucketService` |
| `com.pooli.traffic.service.invoke` | Plane B | API 수신, Streams 적재/소비, reclaim/DLQ, DONE 저장 트리거 | `TrafficRequestEnqueueService`, `TrafficStreamConsumerRunner`, `TrafficStreamInfraService`, `TrafficDeductDoneLogService` |
| `com.pooli.traffic.service.decision` | Plane C | 이벤트 단위 오케스트레이션, hydrate/refill 의사결정, DB refill 원자성 | `TrafficDeductOrchestratorService`, `TrafficHydrateRefillAdapterService`, `TrafficDefaultQuotaSourceAdapter` |
| `com.pooli.traffic.service.policy` | Plane A + C 경계 | 정책 bootstrap/hydration/write-through로 정책 정합성 유지 | `TrafficPolicyBootstrapService`, `TrafficLinePolicyHydrationService`, `TrafficPolicyWriteThroughService` |

테스트 패키지도 동일한 역할 경계를 따르며 `src/test/java/com/pooli/traffic/service/{runtime,invoke,decision,policy}` 구조를 기본으로 사용한다.
프로파일 조합 검증(`TrafficProfileBootTest`)은 cross-plane 성격이므로 `src/test/java/com/pooli/traffic/service` 루트에 유지한다.

## 5. Contract v1 (Locked)

이 섹션이 계약의 단일 진실원(Single Source of Truth)이다.

```yaml
contract_version: v1
breaking_rule:
  allow: ["field_addition"]
  deny: ["field_removal", "semantic_change"]
  breaking_change: "must_create_v2"

b_to_c_request:
  owner_in: "Plane B"
  owner_out: "Plane C"
  source_refs:
    - "TrafficPayloadReqDto.java"
  required_fields:
    traceId: "string(uuid)"
    lineId: "long"
    familyId: "long"
    appId: "int"
    apiTotalData: "long(bytes)"
    enqueuedAt: "epoch_millis"
    retryCount: "int"
    streamMessageId: "string"
  validation_rule: "missing_or_type_mismatch -> DLQ"

c_to_a_runtime:
  owner_in: "Plane C"
  owner_out: "Plane A"
  source_refs:
    - "TrafficLuaStatus.java"
    - "Lua JSON schema"
  request_required:
    commandId: "string"
    traceId: "string(uuid)"
    tickNo: "int(1..10)"
    operation: "enum(deduct, refill, gate, heartbeat, release)"
    poolType: "enum(indiv, shared)"
    amount: "long(bytes)"
    policyVersion: "string"
    nowEpochSec: "epoch_seconds"
  response_required:
    answer: "enum(OK, NO_BALANCE, WAIT, SKIP, FAIL)"
    status: "enum(SUCCESS, RETRYABLE, NON_RETRYABLE)"
    reasonCode: "enum(shared_reason_code_dictionary)"
    appliedAmount: "long(bytes)"
    remainingAmount: "long(bytes)"
  note: "Lua raw numeric answer는 appliedAmount로 정규화한다."

c_to_b_result:
  owner_in: "Plane C"
  owner_out: "Plane B"
  source_refs:
    - "TrafficDeductDone.java"
    - "TrafficFinalStatus.java"
  required_fields:
    traceId: "string(uuid)"
    finalStatus: "enum(SUCCESS, PARTIAL_SUCCESS, FAILED)"
    processedAmount: "long(bytes)"
    failureReason: "string"
    idempotencyKey: "string(traceId)"
  ack_rule: "persist_success_then_ack_only"
```

정합성 주의:
- `finalStatus`는 `SUCCESS | PARTIAL_SUCCESS | FAILED`만 허용한다.
- `PARTIAL` 단독 표기는 금지한다.

## 6. 의존 관계 및 통합 순서

```mermaid
graph LR
    A["Plane A<br/>Redis + Lua"] --> D["통합 테스트"]
    B["Plane B<br/>Streams + Intake"] --> D
    C["Plane C<br/>DB + 오케스트레이터"] --> D
    B -- "요청 전달" --> C
    C -- "런타임 실행 요청" --> A
    A -- "정책/런타임 상태 제공" --> C
```

Mermaid 미지원 뷰어용:

```text
[Plane B] --(요청 전달)--> [Plane C]
[Plane C] --(런타임 실행 요청)--> [Plane A]
[Plane A] --(정책/런타임 상태 제공)--> [Plane C]

[Plane A] --> [통합 테스트]
[Plane B] --> [통합 테스트]
[Plane C] --> [통합 테스트]
```

선행 합의 항목:
- `B -> C` 요청 DTO 필드 고정
- `C <-> A` 호출 시그니처 + 반환 스키마 고정
- Redis key namespace prefix 고정

## 7. 담당자별 상세 구현 책임

### 7.1 Plane A (Runtime State Owner)

주요 작업:
- Lua 자산화: SHA preload, `EVALSHA`, 반환 JSON 파싱
- Redis 규칙: namespace, 일/월 `EXPIREAT`, speed bucket TTL, lock TTL
- 최근 사용량 버킷 집계 기반 refill plan(delta/unit/threshold) 계산
- 동시성/중복 제어: `dedupe:run:{traceId}`, lock heartbeat/release
- 정책 write-through/bootstrap/hydration 지원을 위한 키 규칙 제공

주요 파일:
- `src/main/java/com/pooli/traffic/service/runtime/TrafficLuaScriptInfraService.java`
- `src/main/java/com/pooli/traffic/service/runtime/TrafficRedisKeyFactory.java`
- `src/main/java/com/pooli/traffic/service/runtime/TrafficRedisRuntimePolicy.java`
- `src/main/java/com/pooli/traffic/service/runtime/TrafficRecentUsageBucketService.java`
- `src/main/java/com/pooli/traffic/service/runtime/TrafficInFlightDedupeService.java`
- `src/main/java/com/pooli/traffic/service/runtime/TrafficQuotaCacheService.java`
- `src/main/java/com/pooli/traffic/service/policy/TrafficPolicyWriteThroughService.java`
- `src/main/resources/lua/traffic/*.lua`

도메인/메트릭:
- 도메인: `TrafficLuaExecutionResult`, `TrafficRefillPlan`, `TrafficLuaDeductResDto`, `TrafficLuaScriptType`, `TrafficLuaStatus`, `TrafficRefillGateStatus`
- 메트릭: `TrafficRefillMetrics`, `TrafficHydrateMetrics`

HA 책임:
- Lua 비정상 응답 방어
- TTL 오작동 방지
- 동일 traceId 단일 유효 처리
- Redis 노드 다운 + lock TTL 만료 지연 장애 복구

### 7.2 Plane B (Intake & Delivery Owner)

주요 작업:
- 3.2 설정 분리: `local/api/traffic`, `.env` 키 매핑
- API 엔드포인트(`XADD`), traceId 생성, payload 직렬화
- `XREADGROUP BLOCK` 소비/워커 분배, payload 검증/역직렬화
- DONE 저장 성공 후 ACK 보장(`persist_success_then_ack_only`)
- `XPENDING/XCLAIM` 기반 reclaim, max retry 초과 DLQ 분기

주요 파일:
- `src/main/java/com/pooli/traffic/service/invoke/TrafficStreamConsumerRunner.java`
- `src/main/java/com/pooli/traffic/service/invoke/TrafficStreamInfraService.java`
- `src/main/java/com/pooli/traffic/service/invoke/TrafficStreamReclaimService.java`
- `src/main/java/com/pooli/traffic/service/invoke/TrafficRequestEnqueueService.java`
- `src/main/java/com/pooli/traffic/service/invoke/TrafficPayloadValidationService.java`
- `src/main/java/com/pooli/traffic/service/invoke/TrafficDeductDoneLogService.java`
- `src/main/java/com/pooli/traffic/controller/TrafficController.java`
- `src/main/java/com/pooli/traffic/repository/TrafficDeductDoneLogRepository.java`
- `src/main/java/com/pooli/traffic/config/TrafficSchedulingConfig.java`
- `src/main/java/com/pooli/common/config/StreamsRedisConfig.java`

도메인/메트릭:
- 도메인: `TrafficStreamFields`, `TrafficPayloadReqDto`, `TrafficGenerateReqDto`, `TrafficGenerateResDto`, `TrafficDeductDone`
- 메트릭: `TrafficRequestMetrics`, `TrafficDlqMetrics`, `TrafficGeneratorMetrics`

HA 책임:
- 빈/깨진 payload 방어
- DONE 실패 시 ACK 금지
- 재수신 idempotent 처리
- Consumer 다운/스케일아웃 경쟁 장애 복구

### 7.3 Plane C (Decision & Consistency Owner)

주요 작업:
- 이벤트 단일 사이클 오케스트레이터(개인풀 1회 -> residual -> 조건부 공유풀 1회)
- `SELECT ... FOR UPDATE` 기반 actual refill 정합성 확보
- HYDRATE 1회 복구 + REFILL gate/lock/DB claim/Redis refill/재차감
- 최종 상태 판정(`FAILED`, `SUCCESS`, `PARTIAL_SUCCESS`)과 경계값 방어

주요 파일:
- `src/main/java/com/pooli/traffic/service/decision/TrafficDeductOrchestratorService.java`
- `src/main/java/com/pooli/traffic/service/decision/TrafficHydrateRefillAdapterService.java`
- `src/main/java/com/pooli/traffic/service/decision/TrafficDefaultQuotaSourceAdapter.java`
- `src/main/java/com/pooli/traffic/service/decision/TrafficQuotaSourcePort.java`
- `src/main/java/com/pooli/traffic/mapper/TrafficRefillSourceMapper.java`
- `src/main/java/com/pooli/traffic/service/policy/TrafficLinePolicyHydrationService.java`
- `src/main/java/com/pooli/traffic/service/policy/TrafficPolicyBootstrapService.java`

도메인:
- `TrafficDbRefillClaimResult`, `TrafficDeductResultResDto`, `TrafficFinalStatus`, `TrafficPoolType`

HA 책임:
- `SUCCESS/PARTIAL_SUCCESS/FAILED` 판정 일관성
- DB deadlock, Redis 충전 실패, 월 경계/음수 잔량 장애 복구

## 8. 테스트 게이트 (필수)

Plane A:
- Lua 반환 계약 파싱 + 비정상 응답 방어
- 키/TTL 규칙 검증
- 락 게이트(`OK/WAIT/SKIP/FAIL`)와 heartbeat/release 소유권 검증
- 동일 traceId 중복 실행 방지

Plane B:
- API -> payload 직렬화/역직렬화 + 필수 필드 검증
- DONE 성공 후 ACK, 실패 시 ACK 금지
- reclaim + DLQ 흐름 검증
- consumer 재기동 후 pending 복구

Plane C:
- 개인풀 1회 차감 후 residual 계산 규칙 검증
- `residual > 0 && 개인풀 NO_BALANCE` 조건에서만 공유풀 보완 차감 검증
- HYDRATE 1회 재시도 + REFILL gate/lock/DB claim/Redis refill/재차감 검증
- final status 판정 및 `apiTotalData <= 0` 경계값 검증

통합 게이트:
- Contract 호환성(`B->C`, `C<->A`, `C->B`)
- E2E(요청 -> 소비 -> 오케스트레이션 -> Redis/DB -> DONE/ACK)
- 장애 복구(Redis 다운, DB deadlock, consumer 중단)

## 9. 에러 코드 및 로깅 정책

에러 코드 소유권:
- `PAYLOAD_INVALID`: Plane B
- `REDIS_TIMEOUT`: Plane A
- `DEADLOCK_RETRY_EXCEEDED`: Plane C
- `LEDGER_REPLAY_FAILED`: Plane C
- `ACK_BLOCKED_BY_PERSIST_FAIL`: Plane B

로깅 규칙:
- 실패 로그 공통 포맷 정의는 Plane C가 소유한다.
- 실패 로그 구현은 A/B/C가 각자 소유 Plane에서 수행한다.
- 성공 로그는 Plane B가 local-only 수준으로 최소화한다.

권장 실패 로그 필드:
- `traceId`
- `timestamp`
- `lineId`
- `familyId`
- `appId`
- `plane`
- `reasonCode`
- `operation`
- `retryCount`

## 10. 운영 규칙

- API 실패 응답코드는 현재 `TBD`다.
- 임시 정책은 Plane B가 구현한다.
- 확정 후 일괄 치환한다.
- 로컬 Redis 테스트는 `pooli 정리/docker/` 구성 사용을 권장한다.

## 11. Codex/LLM 친화 작업 템플릿

작업 요청 템플릿:

```text
[Plane] A | B | C
[Goal] 구현 단계 번호 + 완료 조건
[Files] 변경 대상 파일 목록
[Contract Impact] 없음 | v1 필드 추가 | 브레이킹(v2 필요)
[Tests] 단위/통합/프로파일 중 수행 범위
[Out of Scope] 이번 작업에서 제외할 항목
```

LLM 응답 규칙:
- 계약 필드명은 원문 그대로 사용한다.
- Enum 문자열은 대소문자를 고정한다.
- 모호한 축약어를 사용하지 않는다.
- `PARTIAL` 대신 `PARTIAL_SUCCESS`를 사용한다.
- 날짜는 `YYYY-MM-DD`로 고정한다.

PR 체크리스트:
- [ ] Contract v1 필수 필드/타입 임의 변경 없음
- [ ] 소유 Plane 외 로직 침범 없음
- [ ] 역할별 테스트 게이트 통과
- [ ] `docs/junit-unit-test-guide.md` 기준 단위 테스트 품질 점검
- [ ] 통합 게이트 영향도 코멘트 포함

## 12. 부록: 책임 경계 빠른 조회

| 항목 | 최종 책임 Plane |
| --- | --- |
| ACK/DONE 순서 | B |
| 최종 상태 판정 | C |
| Redis 키/TTL 규칙 | A |
| payload 검증 실패 처리 | B |
| DB deadlock 재시도 정책 | C |
| Redis lock heartbeat/release | A |
