# Redis Restore Batch Implementation Plan

> **agentic worker 필수 지침:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development`를 기본으로 사용하고, 단일 session에서 직접 실행해야 할 때만 `superpowers:executing-plans`를 사용합니다. 각 task는 checkbox(`- [ ]`)로 추적합니다.

**Goal:** Redis 장애 시 DB 원천 데이터로 traffic 잔량/사용량 Redis key를 복구하고, 복구 중 traffic stream 처리를 fail-closed로 중단하는 관리자 전용 복구 batch를 구현합니다.

**Architecture:** 관리자 API가 장애 복구 flag를 활성화하고 traffic 진입을 중단한 뒤, `LINE_DAILY_BATCH_JOB` metadata로 Phase 0 hydrate, Phase 1 `DAILY_APP_TOTAL_DATA` replay, Phase 2 done log replay와 전체 검증/보정을 순차 실행합니다. Redis 반영은 Lua 원자 구간과 phase별 idempotency key로 중복 적용을 막고, MySQL target/restore 상태는 worker claim과 lease timeout으로 병렬 처리합니다.

**Tech Stack:** Spring Boot 3.5.10, Spring Data Redis 3.5.8, Lettuce 6.6.0.RELEASE, Spring JDBC 6.2.15, MyBatis core 3.5.19 / starter 3.0.5, JUnit Jupiter 5.12.2, Mockito 5.17.0, Flyway SQL migration, Redis Lua

---

## 실행 상태

- 현재 상태: 구현 승인
- 설계 명세: `docs/superpowers/specs/2026-05-29-redis-restore-batch-design.md`
- 의존성 출처: `docs/context7-dependencies.yaml`
- 테스트 작성 기준: `docs/junit-unit-test-guide.md`
- Context7 사용 여부: `not_used` - 현재 계획 작성은 exact API signature 확인이 아니라 구현 분해 문서화 작업입니다.

## 실행 전 필수 규칙

- 구현 시작 전 `superpowers:using-git-worktrees`를 사용해 격리 workspace를 확인하거나 생성합니다.
- 이 계획은 Redis Lua, Redis Streams, database schema, idempotency, batch processing, transaction boundary를 포함하므로 승인 후 기본 실행 방식은 `superpowers:subagent-driven-development`입니다.
- 모든 subagent prompt에는 이 저장소의 `AGENTS.md`, dependency/Context7 규칙, 한국어 documentation/review communication 규칙을 포함합니다.
- reviewer subagent는 code quality보다 spec compliance를 먼저 검토합니다.
- codebase 변경 commit은 사용자 명시 확인 전 금지합니다. superpowers의 frequent commit 권장보다 `AGENTS.md` commit rule을 우선 적용합니다.
- test code 작성 전 `docs/junit-unit-test-guide.md`를 확인합니다.
- Spring Boot, Spring Security, MyBatis, Redis, JDBC, JUnit, Mockito 관련 exact API signature가 필요해지면 `docs/context7-dependencies.yaml`에서 library id와 version을 확인한 뒤 Context7을 1 dependency당 1회 원칙으로 사용합니다.

## 승인된 구현 결정

| 항목 | 결정 |
|---|---|
| 장애 복구 flag 식별 방식 | `policy_id = 8` 고정 |
| 일반 정책 목록 제외 방식 | `policy_id = 8` 조건으로 제외 |
| phase 1 대상 범위 | 복구 대상 날짜 범위의 `DAILY_APP_TOTAL_DATA`만 replay |
| worker chunk env 이름 | `TRAFFIC_RESTORE_WORKER_CHUNK_SIZE`, 기본값 `5000` |
| restore manager lock key 이름 | `traffic:restore:manager-lock` |
| phase 2 조회 index | `(restore_status, enqueued_at, restore_status_updated_at)` 복합 인덱스 추가, 기존 `enqueued_at` 단일 인덱스 유지 |
| 보정 Lua 분리 여부 | replay Lua와 correction Lua 분리, 보정은 `restore_usage_correction.lua`에서 수행 |

---

## 파일 책임 구조

### 문서

- Modify: `docs/superpowers/specs/2026-05-29-redis-restore-batch-design.md` - 승인된 설계 변경이 생기면 명세 갱신
- Modify: `docs/superpowers/plans/2026-05-29-redis-restore-batch.md` - 승인된 task 분해 변경이 생기면 계획 갱신

### Schema

- Create: `src/main/resources/db/migration/V2605290100__create_traffic_restore_batch_tables.sql` - `RESTORE_HYDRATE_TARGET`, `RESTORE_DAILY_APP_TARGET`, phase 2 조회 index 생성
- Create 또는 Modify: `src/main/resources/db/migration/V2605290110__add_traffic_restore_policy_flag.sql` - 장애 복구 flag seed 또는 식별 metadata 추가

### Domain

- Modify: `src/main/java/com/pooli/traffic/domain/batch/BatchName.java` - restore batch name 상수 추가
- Create: `src/main/java/com/pooli/traffic/domain/restore/TrafficRestoreTargetStatus.java` - restore target 상태 enum
- Create: `src/main/java/com/pooli/traffic/domain/restore/TrafficRestoreHydrateTargetType.java` - `LINE`, `FAMILY`, `GLOBAL_POLICY`
- Create: `src/main/java/com/pooli/traffic/domain/restore/TrafficRestoreHydrateTarget.java` - phase 0 target row entity
- Create: `src/main/java/com/pooli/traffic/domain/restore/TrafficRestoreDailyAppTarget.java` - phase 1 target row entity
- Create: `src/main/java/com/pooli/traffic/domain/restore/TrafficRestoreReplayCommand.java` - phase 1/2 Lua 입력값 command
- Create: `src/main/java/com/pooli/traffic/domain/restore/TrafficRestoreReplayResult.java` - Lua replay 결과

### Configuration와 Redis key

- Create: `src/main/java/com/pooli/traffic/config/TrafficRestoreProperties.java` - chunk size, lease timeout, retry limit, restore policy id 설정
- Modify: `src/main/resources/application-traffic.yml` - `app.traffic.restore.*` 기본값 추가
- Modify: `src/main/java/com/pooli/traffic/service/runtime/TrafficRedisKeyFactory.java` - restore lock/idempotency key 생성 method 추가

### Mapper

- Create: `src/main/java/com/pooli/traffic/mapper/TrafficRestoreHydrateTargetMapper.java`
- Create: `src/main/resources/mapper/traffic/TrafficRestoreHydrateTargetMapper.xml`
- Create: `src/main/java/com/pooli/traffic/mapper/TrafficRestoreDailyAppTargetMapper.java`
- Create: `src/main/resources/mapper/traffic/TrafficRestoreDailyAppTargetMapper.xml`
- Modify: `src/main/java/com/pooli/traffic/mapper/TrafficDeductDoneLogMapper.java` - phase 2 restore claim/update 조회 method 추가
- Modify: `src/main/resources/mapper/traffic/TrafficDeductDoneLogMapper.xml` - phase 2 restore claim/update SQL 추가
- Modify: `src/main/java/com/pooli/traffic/mapper/LineDailyBatchJobMapper.java` - restore phase metadata 조회/생성 method 추가
- Modify: `src/main/resources/mapper/traffic/LineDailyBatchJobMapper.xml` - restore phase metadata SQL 추가

### Service

- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePolicyFlagService.java` - DB/Redis 복구 flag 활성화와 조회
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreTrafficGateService.java` - producer/poller/reclaim/worker 진입 차단 판단
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreBatchMetadataService.java` - phase metadata 생성/전환/count
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase0TargetInsertService.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase0HydrateService.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase1TargetInsertService.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreReplayLuaExecutor.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayService.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayService.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreVerificationService.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreIdempotencyCleanupService.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreOrchestratorService.java`

### Lua

- Create: `src/main/resources/lua/traffic/restore_usage_replay.lua` - phase 1/2 공통 replay Lua
- Create: `src/main/resources/lua/traffic/restore_usage_correction.lua` - verification correction 전용 Lua
- Modify: `src/main/java/com/pooli/traffic/service/runtime/TrafficLuaScriptType.java` - restore Lua type 추가
- Modify: `src/main/java/com/pooli/traffic/service/runtime/TrafficLuaScriptInfraService.java` - restore Lua 등록과 실행 지원

### Traffic stream guard 적용 위치

- Modify: `src/main/java/com/pooli/traffic/service/invoke/TrafficRequestEnqueueService.java`
- Modify: `src/main/java/com/pooli/traffic/service/invoke/TrafficStreamConsumerRunner.java`
- Modify: `src/main/java/com/pooli/traffic/service/invoke/TrafficStreamReclaimService.java`

### Controller/DTO

- Create: `src/main/java/com/pooli/traffic/controller/AdminTrafficRestoreController.java`
- Create: `src/main/java/com/pooli/traffic/domain/dto/request/TrafficRestoreStartReqDto.java`
- Create: `src/main/java/com/pooli/traffic/domain/dto/response/TrafficRestoreStartResDto.java`
- Create: `src/main/java/com/pooli/traffic/domain/dto/response/TrafficRestoreResumeResDto.java`

### Tests

- Create tests under `src/test/java/com/pooli/traffic/service/restore/`
- Modify or create acceptance tests under `src/test/java/com/pooli/traffic/acceptance/`
- Verify with `./gradlew test` and `./gradlew build`

---

## Task 0: 실행 전 결정 확정

**Files:**
- Modify: `docs/superpowers/specs/2026-05-29-redis-restore-batch-design.md`
- Modify: `docs/superpowers/plans/2026-05-29-redis-restore-batch.md`

- [x] **Step 1: 확정 항목을 사용자에게 제시**

확정 요청 항목:

```markdown
1. 장애 복구 flag 식별 방식
2. 일반 정책 목록 제외 방식
3. phase 1 대상 범위
4. worker chunk env 이름
5. restore manager lock key 이름
6. phase 2 조회 index
7. 보정 Lua 분리 여부
```

Expected: 사용자가 각 항목의 결정을 명시합니다.

- [x] **Step 2: 승인된 결정을 설계 명세에 반영**

Run:

```bash
sed -n '1,260p' docs/superpowers/specs/2026-05-29-redis-restore-batch-design.md
```

Expected: 확정된 decision이 `승인된 구현 결정`에 반영되어 있습니다.

- [x] **Step 3: 계획의 차단 상태 제거**

Run:

```bash
rg -n "확정되지 않으면|필요한 결정|구현 미승인" docs/superpowers/plans/2026-05-29-redis-restore-batch.md
```

Expected: 실행 차단 문구가 승인된 decision으로 대체되어 있습니다.

- [x] **Step 4: commit 여부 확인**

Expected: 문서 변경 commit은 사용자에게 명시 확인을 받은 경우에만 수행합니다. 현재 승인 범위에는 commit 요청이 없으므로 commit하지 않습니다.

---

## Task 1: Schema와 domain model 추가

**Files:**
- Create: `src/main/resources/db/migration/V2605290100__create_traffic_restore_batch_tables.sql`
- Create 또는 Modify: `src/main/resources/db/migration/V2605290110__add_traffic_restore_policy_flag.sql`
- Modify: `src/main/java/com/pooli/traffic/domain/batch/BatchName.java`
- Create: `src/main/java/com/pooli/traffic/domain/restore/TrafficRestoreTargetStatus.java`
- Create: `src/main/java/com/pooli/traffic/domain/restore/TrafficRestoreHydrateTargetType.java`
- Create: `src/main/java/com/pooli/traffic/domain/restore/TrafficRestoreHydrateTarget.java`
- Create: `src/main/java/com/pooli/traffic/domain/restore/TrafficRestoreDailyAppTarget.java`
- Test: `src/test/java/com/pooli/traffic/domain/restore/TrafficRestoreDomainTest.java`

- [x] **Step 1: failing test 작성**

검증할 동작:

```java
@DisplayName("restore target 상태는 worker claim과 terminal 상태를 구분한다")
@Test
void restoreTargetStatusContract() {
    assertTrue(TrafficRestoreTargetStatus.PENDING.isClaimable());
    assertTrue(TrafficRestoreTargetStatus.RETRYABLE.isClaimable());
    assertFalse(TrafficRestoreTargetStatus.PROCESSING.isTerminal());
    assertTrue(TrafficRestoreTargetStatus.DONE.isTerminal());
    assertTrue(TrafficRestoreTargetStatus.FAILED.isTerminal());
}
```

- [x] **Step 2: failing test 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.domain.restore.TrafficRestoreDomainTest
```

Expected: `TrafficRestoreTargetStatus`가 없어서 compile fail.

- [x] **Step 3: migration 작성**

구현 내용:

```sql
CREATE TABLE IF NOT EXISTS RESTORE_HYDRATE_TARGET (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    batch_name         VARCHAR(64)  NOT NULL,
    target_month_start DATE         NOT NULL,
    target_type        VARCHAR(32)  NOT NULL,
    target_owner_id    BIGINT       NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    status_updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    worker_id          VARCHAR(128) NULL,
    retry_count        INT          NOT NULL DEFAULT 0,
    last_error_code    VARCHAR(64)  NULL,
    last_error_message VARCHAR(1000) NULL,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_restore_hydrate_target (
        batch_name,
        target_month_start,
        target_type,
        target_owner_id
    ),
    KEY idx_restore_hydrate_target_claim (
        batch_name,
        status,
        status_updated_at
    )
);

CREATE TABLE IF NOT EXISTS RESTORE_DAILY_APP_TARGET (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    batch_name         VARCHAR(64)  NOT NULL,
    usage_date         DATE         NOT NULL,
    line_id            BIGINT       NOT NULL,
    application_id     INT          NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    status_updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    worker_id          VARCHAR(128) NULL,
    retry_count        INT          NOT NULL DEFAULT 0,
    last_error_code    VARCHAR(64)  NULL,
    last_error_message VARCHAR(1000) NULL,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_restore_daily_app_target (
        batch_name,
        usage_date,
        line_id,
        application_id
    ),
    KEY idx_restore_daily_app_target_claim (
        batch_name,
        status,
        status_updated_at
    )
);

CREATE INDEX idx_traffic_deduct_done_restore_claim
    ON TRAFFIC_DEDUCT_DONE (restore_status, enqueued_at, restore_status_updated_at);
```

Expected: table, unique key, claim index가 설계 명세와 일치합니다. 기존 `enqueued_at` 단일 인덱스는 제거하지 않습니다.

- [x] **Step 4: domain class와 enum 작성**

구현 원칙:

```java
public enum TrafficRestoreTargetStatus {
    PENDING,
    PROCESSING,
    DONE,
    RETRYABLE,
    FAILED;
}
```

Javadocs와 enum constant 설명은 한국어로 작성합니다.

- [x] **Step 5: test 통과 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.domain.restore.TrafficRestoreDomainTest
```

Expected: PASS.

---

## Task 2: Traffic 복구 flag와 fail-closed guard 구현

**Files:**
- Create: `src/main/java/com/pooli/traffic/config/TrafficRestoreProperties.java`
- Modify: `src/main/resources/application-traffic.yml`
- Modify: `src/main/java/com/pooli/traffic/service/runtime/TrafficRedisKeyFactory.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePolicyFlagService.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreTrafficGateService.java`
- Modify: `src/main/java/com/pooli/traffic/service/invoke/TrafficRequestEnqueueService.java`
- Modify: `src/main/java/com/pooli/traffic/service/invoke/TrafficStreamConsumerRunner.java`
- Modify: `src/main/java/com/pooli/traffic/service/invoke/TrafficStreamReclaimService.java`
- Test: `src/test/java/com/pooli/traffic/service/restore/TrafficRestoreTrafficGateServiceTest.java`

- [x] **Step 1: failing guard test 작성**

검증할 동작:

```java
@DisplayName("복구 flag가 활성화되면 traffic 진입을 차단한다")
@Test
void blocksTrafficWhenRestoreFlagIsActive() {
    when(policyFlagService.isRestoreActiveFailClosed()).thenReturn(true);

    assertTrue(trafficRestoreTrafficGateService.shouldBlockTraffic());
}
```

- [x] **Step 2: failing test 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreTrafficGateServiceTest
```

Expected: restore gate service가 없어 compile fail.

- [x] **Step 3: properties와 Redis key method 구현**

구현 원칙:

```java
public String trafficRestoreManagerLockKey() {
    return namespaced("traffic_restore:manager_lock");
}

public String restoreIdempotencyKey(String phase, String id) {
    return namespaced("restore:idempotency:" + phase + ":" + id);
}
```

- [x] **Step 4: fail-closed flag service 구현**

구현 원칙:

```java
public boolean isRestoreActiveFailClosed() {
    // 1. Redis policy key 조회
    // 2. key 누락 시 policy bootstrap/hydrate 1회 시도
    // 3. 여전히 상태 불명확하거나 Redis 조회 실패면 true 반환
}
```

- [x] **Step 5: enqueue/poll/reclaim/worker 진입 차단 적용**

Expected:
- enqueue는 `XADD` 전에 차단합니다.
- poll loop는 `XREAD` 전에 차단합니다.
- reclaim loop는 pending scan 전에 차단합니다.
- worker는 ACK/DLQ/done log 생성 전에 차단하고 처리하지 않은 메시지는 pending으로 유지합니다.

- [x] **Step 6: test와 관련 acceptance 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreTrafficGateServiceTest
./gradlew test --tests com.pooli.traffic.service.invoke.TrafficRequestEnqueueServiceTest
./gradlew test --tests com.pooli.traffic.service.invoke.TrafficStreamConsumerRunnerTest
```

Expected: PASS.

---

## Task 3: Restore batch metadata와 target claim infra 구현

**Files:**
- Create: `src/main/java/com/pooli/traffic/mapper/TrafficRestoreHydrateTargetMapper.java`
- Create: `src/main/resources/mapper/traffic/TrafficRestoreHydrateTargetMapper.xml`
- Create: `src/main/java/com/pooli/traffic/mapper/TrafficRestoreDailyAppTargetMapper.java`
- Create: `src/main/resources/mapper/traffic/TrafficRestoreDailyAppTargetMapper.xml`
- Modify: `src/main/java/com/pooli/traffic/mapper/LineDailyBatchJobMapper.java`
- Modify: `src/main/resources/mapper/traffic/LineDailyBatchJobMapper.xml`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreBatchMetadataService.java`
- Test: `src/test/java/com/pooli/traffic/service/restore/TrafficRestoreBatchMetadataServiceTest.java`

- [x] **Step 1: failing metadata service test 작성**

검증할 동작:

```java
@DisplayName("restore phase는 모든 대상이 DONE일 때만 완료된다")
@Test
void completesOnlyWhenAllTargetsAreDone() {
    when(targetMapper.countNotDoneTargets("RESTORE_P0_REDIS_HYDRATE")).thenReturn(0L);

    boolean completed = service.completePhaseIfAllTargetsDone(batchJob);

    assertTrue(completed);
}
```

- [x] **Step 2: failing test 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreBatchMetadataServiceTest
```

Expected: service/mapper method가 없어 compile fail.

- [x] **Step 3: mapper claim SQL 작성**

구현 원칙:

```sql
SELECT
    id,
    batch_name,
    target_month_start,
    target_type,
    target_owner_id,
    status,
    status_updated_at,
    worker_id,
    retry_count,
    last_error_code,
    last_error_message,
    created_at
FROM RESTORE_HYDRATE_TARGET
WHERE batch_name = #{batchName}
  AND (
      status IN ('PENDING', 'RETRYABLE')
      OR (status = 'PROCESSING' AND status_updated_at < #{leaseExpiredBefore})
  )
ORDER BY id
LIMIT #{limit}
FOR UPDATE SKIP LOCKED
```

- [x] **Step 4: metadata service 구현**

Expected:
- target insert batch와 replay batch를 분리합니다.
- 다음 phase metadata insert는 이전 phase 완료 후 별도 transaction에서 수행합니다.
- `FAILED` target 존재 시 다음 phase 전환을 거부합니다.

- [x] **Step 5: mapper/service test 통과 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreBatchMetadataServiceTest
```

Expected: PASS.

---

## Task 4: Phase 0 target insert와 hydrate 구현

**Files:**
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase0TargetInsertService.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase0HydrateService.java`
- Modify: `src/main/java/com/pooli/traffic/mapper/TrafficBalanceSnapshotSourceMapper.java`
- Modify: `src/main/resources/mapper/traffic/TrafficBalanceSnapshotSourceMapper.xml`
- Test: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase0TargetInsertServiceTest.java`
- Test: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase0HydrateServiceTest.java`

- [x] **Step 1: failing target source test 작성**

검증할 동작:

```java
@DisplayName("phase 0 target은 daily app 월 데이터와 done log 범위의 line union으로 생성된다")
@Test
void createsLineTargetsFromDailyAppAndDoneLogUnion() {
    List<Long> lineIds = service.resolveLineTargetIds(anchorDate, restoreStartDate, months);

    assertThat(lineIds).containsExactlyInAnyOrder(10L, 20L, 30L);
}
```

- [x] **Step 2: failing test 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestorePhase0TargetInsertServiceTest
```

Expected: phase 0 service가 없어 compile fail.

- [x] **Step 3: target insert 구현**

Expected:
- `LINE` target은 `DAILY_APP_TOTAL_DATA`와 phase 2 done log 범위의 union으로 생성합니다.
- `FAMILY` target은 `FAMILY_LINE` 매핑 기준으로 생성합니다.
- family_id가 없는 line은 정상 case로 family target 생성을 생략합니다.
- `GLOBAL_POLICY` target은 장애 복구 flag를 포함합니다.

- [x] **Step 4: hydrate worker 구현**

Expected:
- `LINE`은 `TrafficBalanceSnapshotHydrateService`의 개인 잔량 hydrate 흐름을 재사용합니다.
- `FAMILY`는 공유 잔량 hydrate 흐름을 재사용합니다.
- `GLOBAL_POLICY`는 `TrafficPolicyBootstrapService` 또는 기존 policy hydrate 흐름을 재사용합니다.

- [x] **Step 5: test 통과 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestorePhase0TargetInsertServiceTest
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestorePhase0HydrateServiceTest
```

Expected: PASS.

---

## Task 5: Phase 1 replay Lua와 worker 구현

**Files:**
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase1TargetInsertService.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreReplayLuaExecutor.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayService.java`
- Create: `src/main/resources/lua/traffic/restore_usage_replay.lua`
- Modify: `src/main/java/com/pooli/traffic/service/runtime/TrafficLuaScriptType.java`
- Modify: `src/main/java/com/pooli/traffic/service/runtime/TrafficLuaScriptInfraService.java`
- Test: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayServiceTest.java`
- Test: `src/test/java/com/pooli/traffic/service/runtime/TrafficRestoreLuaContractTest.java`

- [x] **Step 1: failing Lua contract test 작성**

검증할 동작:

```java
@DisplayName("restore replay Lua는 idempotency key 존재 시 사용량과 잔량을 변경하지 않는다")
@Test
void skipsReplayWhenIdempotencyKeyExists() throws IOException {
    String lua = Files.readString(Path.of("src/main/resources/lua/traffic/restore_usage_replay.lua"));

    assertThat(lua).contains("redis.call('EXISTS', idempotency_key)");
    assertThat(lua).contains("return { 'SKIPPED' }");
}
```

- [x] **Step 2: failing test 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.runtime.TrafficRestoreLuaContractTest
```

Expected: Lua file이 없어 fail.

- [x] **Step 3: phase 1 target insert 구현**

Expected:
- 승인된 phase 1 대상 범위 결정에 따라 `RESTORE_DAILY_APP_TARGET`을 생성합니다.
- unique key로 중복 target 생성을 방지합니다.

- [x] **Step 4: replay Lua 구현**

Expected:
- idempotency key 확인과 생성, 사용량 증가, 잔량 차감을 하나의 Lua script에서 원자적으로 수행합니다.
- `amount = -1`은 잔량 차감하지 않습니다.
- `amount < -1` 또는 무제한이 아닌 잔량 음수 결과는 오류로 반환합니다.
- QoS 사용량은 잔량 차감하지 않습니다.

- [x] **Step 5: phase 1 worker 구현**

Expected:
- target claim 후 Lua를 실행합니다.
- Redis replay 성공 또는 idempotency skip이면 MySQL target을 `DONE`으로 전환합니다.
- MySQL commit 이후 idempotency key를 제거합니다.
- Redis replay 후 MySQL commit 전 worker 사망 case는 다음 worker가 idempotency skip 후 `DONE` 처리합니다.

- [x] **Step 6: test 통과 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.runtime.TrafficRestoreLuaContractTest
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestorePhase1ReplayServiceTest
```

Expected: PASS.

---

## Task 6: Phase 2 done log replay 구현

**Files:**
- Modify: `src/main/java/com/pooli/traffic/mapper/TrafficDeductDoneLogMapper.java`
- Modify: `src/main/resources/mapper/traffic/TrafficDeductDoneLogMapper.xml`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayService.java`
- Test: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayServiceTest.java`

- [x] **Step 1: failing phase 2 claim test 작성**

검증할 동작:

```java
@DisplayName("phase 2는 업무일 범위의 NONE/RETRYABLE done log만 claim한다")
@Test
void claimsOnlyEligibleDoneLogs() {
    List<TrafficDeductDoneLog> logs = mapper.selectClaimableRestoreLogsForUpdate(startDateTime, endDateTime, leaseExpiredBefore, 5000);

    assertThat(logs).allMatch(log -> log.getRestoreStatus().equals("NONE") || log.getRestoreStatus().equals("RETRYABLE"));
}
```

- [x] **Step 2: failing test 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestorePhase2ReplayServiceTest
```

Expected: phase 2 restore method가 없어 compile fail.

- [x] **Step 3: done log restore claim SQL 구현**

구현 원칙:

```sql
SELECT
    traffic_deduct_done_id,
    trace_id,
    record_id,
    line_id,
    family_id,
    app_id,
    enqueued_at,
    deducted_individual_bytes,
    deducted_shared_bytes,
    deducted_qos_bytes,
    restore_status,
    restore_status_updated_at,
    restore_retry_count,
    restore_last_error_message
FROM TRAFFIC_DEDUCT_DONE
WHERE enqueued_at >= #{startInclusive}
  AND enqueued_at < #{endExclusive}
  AND (
      restore_status IN ('NONE', 'RETRYABLE')
      OR (restore_status = 'PROCESSING' AND restore_status_updated_at < #{leaseExpiredBefore})
  )
ORDER BY traffic_deduct_done_id
LIMIT #{limit}
FOR UPDATE SKIP LOCKED
```

- [x] **Step 4: phase 2 replay service 구현**

Expected:
- phase 1과 동일한 Lua replay 계약을 사용합니다.
- idempotency key pattern은 `restore:idempotency:p2:done_log:{trafficDeductDoneId}`입니다.
- MySQL `restore_status = DONE` commit 이후 idempotency key를 제거합니다.
- `FAILED` done log가 남아 있으면 phase 2 batch를 완료하지 않습니다.

- [x] **Step 5: test 통과 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestorePhase2ReplayServiceTest
```

Expected: PASS.

---

## Task 7: 전체 검증, 자동 보정, cleanup 구현

**Files:**
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreVerificationService.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreIdempotencyCleanupService.java`
- Create: `src/main/resources/lua/traffic/restore_usage_correction.lua`
- Test: `src/test/java/com/pooli/traffic/service/restore/TrafficRestoreVerificationServiceTest.java`
- Test: `src/test/java/com/pooli/traffic/service/restore/TrafficRestoreIdempotencyCleanupServiceTest.java`

- [x] **Step 1: failing verification test 작성**

검증할 동작:

```java
@DisplayName("전체 검증에서 Redis 값이 기준값과 다르면 structured log 후 기준값으로 보정한다")
@Test
void correctsRedisValueWhenMismatchFound() {
    RestoreVerificationResult result = service.verifyAndCorrect(anchorDate, restoreRange);

    assertEquals(0, result.failedCorrectionCount());
    assertTrue(result.correctedCount() > 0);
}
```

- [x] **Step 2: failing test 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreVerificationServiceTest
```

Expected: verification service가 없어 compile fail.

- [x] **Step 3: 검증 기준 산출 구현**

Expected:
- 개인 잔량: hydrate 기준 개인 잔량에서 개인 사용량 replay 합계를 차감합니다.
- 공유 잔량: hydrate 기준 공유 잔량에서 공유 사용량 replay 합계를 차감합니다.
- 사용량 key: phase 1/2 replay source 합계와 비교합니다.
- policy key: DB `POLICY` 기준 전역 정책 상태와 비교합니다.

- [x] **Step 4: 자동 보정 구현**

Expected:
- 전체 검증은 sampling 없이 수행합니다.
- 불일치는 structured log로 기록합니다.
- 보정 실패 시 phase 2 batch를 `FAILED`로 전환합니다.
- 보정 성공 후 `restore:idempotency:*` prefix cleanup을 수행합니다.

- [x] **Step 5: test 통과 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreVerificationServiceTest
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreIdempotencyCleanupServiceTest
```

Expected: PASS.

---

## Task 8: 관리자 API와 orchestration 구현

**Files:**
- Create: `src/main/java/com/pooli/traffic/controller/AdminTrafficRestoreController.java`
- Create: `src/main/java/com/pooli/traffic/domain/dto/request/TrafficRestoreStartReqDto.java`
- Create: `src/main/java/com/pooli/traffic/domain/dto/response/TrafficRestoreStartResDto.java`
- Create: `src/main/java/com/pooli/traffic/domain/dto/response/TrafficRestoreResumeResDto.java`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreStartDateResolver.java`
- Modify: `src/main/java/com/pooli/traffic/mapper/LineDailyBatchJobMapper.java`
- Modify: `src/main/resources/mapper/traffic/LineDailyBatchJobMapper.xml`
- Create: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreOrchestratorService.java`
- Test: `src/test/java/com/pooli/traffic/service/restore/TrafficRestoreStartDateResolverTest.java`
- Test: `src/test/java/com/pooli/traffic/service/restore/TrafficRestoreOrchestratorServiceTest.java`
- Test: `src/test/java/com/pooli/traffic/controller/AdminTrafficRestoreControllerTest.java`
- Test: `src/test/java/com/pooli/traffic/mapper/LineDailyBatchJobMapperSqlContractTest.java`

- [x] **Step 0: 서버 산정 방식 반영**

Expected:
- start API 요청은 `failureDate`만 입력받습니다.
- `restoreStartDate`는 `LINE_DAILY_USAGE_SYNC_BATCH`의 마지막 `COMPLETED` `usage_date <= failureDate` 다음 날로 계산합니다.
- 완료된 일별 동기화 이력이 없으면 `failureDate`를 `restoreStartDate`로 사용해 금일자 done log만 복구합니다.
- 계산된 `restoreStartDate`가 `failureDate`보다 늦으면 복구 대상 없음 응답을 반환하고 복구 flag를 활성화하지 않습니다.
- start 응답은 `failureDate`와 계산된 `restoreStartDate`를 포함합니다.
- start API는 phase 0 hydrate, phase 1 daily app replay, phase 2 done log replay, 전체 검증/보정을 현재 요청에서 순차 실행합니다.
- 복구 성공 시 start 응답의 `nextPhase`는 `RESTORE_COMPLETED`입니다.

- [x] **Step 1: failing orchestration test 작성**

검증할 동작:

```java
@DisplayName("복구 시작은 flag 활성화, 대기, phase 0 시작 순서로 진행된다")
@Test
void startsRestoreInRequiredOrder() {
    service.start(request);

    InOrder inOrder = inOrder(policyFlagService, waitService, phase0TargetInsertService);
    inOrder.verify(policyFlagService).activateRestoreFlag();
    inOrder.verify(waitService).waitWorstProcessingTimePlusBuffer();
    inOrder.verify(phase0TargetInsertService).start(any());
}
```

- [x] **Step 2: failing test 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreOrchestratorServiceTest
```

Expected: orchestrator service가 없어 compile fail.

- [x] **Step 3: start API 구현**

Expected:
- 관리자 권한을 요구합니다.
- 장애 발생일을 입력받고 미완료 시작일은 서버 내부에서 계산합니다.
- 복구 flag 활성화와 전역 정책 hydrate를 수행합니다.
- `app.streams.reclaim-worst-processing-ms + 1000ms`를 대기합니다.
- phase 0/1/2와 전체 검증/보정을 순차 실행합니다.
- 성공 시 복구 flag를 비활성화합니다.

- [x] **Step 4: resume API 구현**

Expected:
- `FAILED` 또는 중단 상태의 phase를 확인합니다.
- `FAILED` target 재처리 정책에 따라 `RETRYABLE`로 되돌릴 대상만 재개합니다.
- phase scope 확장이 필요하면 중단하고 사용자 개입을 요구합니다.

- [x] **Step 5: test 통과 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreOrchestratorServiceTest
./gradlew test --tests com.pooli.traffic.controller.AdminTrafficRestoreControllerTest
```

Expected: PASS.

---

## Task 9: 통합 검증과 회귀 테스트

**Files:**
- Create: `src/test/java/com/pooli/traffic/acceptance/TrafficRestoreBatchAcceptanceTest.java`
- Modify: `src/test/java/com/pooli/traffic/acceptance/TrafficAcceptanceTestSupport.java`

- [x] **Step 1: acceptance scenario 작성**

검증 scenario:

```java
@DisplayName("Redis 장애 후 복구 batch는 잔량과 사용량 key를 DB 원천 데이터 기준으로 복구한다")
@Test
void restoresRedisUsageAndBalanceFromDatabaseSources() {
    // given: Redis 잔량/사용량 key 삭제, daily app data와 done log fixture 준비
    // when: 관리자 복구 start 실행
    // then: Redis key 값이 검증 기준값과 일치
}
```

- [x] **Step 2: traffic guard acceptance 작성**

검증 scenario:

```java
@DisplayName("복구 flag 활성화 중에는 신규 stream 생산과 소비가 중단된다")
@Test
void blocksTrafficWhileRestoreFlagIsActive() {
    // given: restore flag active
    // when: traffic 요청 enqueue와 consumer cycle 실행
    // then: XADD, ACK, DLQ, done log 생성이 발생하지 않음
}
```

- [x] **Step 3: idempotency 재시작 scenario 작성**

검증 scenario:

```java
@DisplayName("Redis replay 후 MySQL commit 전 중단되어도 재시작 시 중복 차감하지 않는다")
@Test
void doesNotDoubleApplyWhenWorkerDiesAfterRedisReplay() {
    // given: idempotency key가 남아 있는 target
    // when: 다음 worker가 같은 target을 처리
    // then: Redis replay는 skip되고 MySQL 상태만 DONE 처리됨
}
```

- [x] **Step 4: 전체 test 실행**

Run:

```bash
./gradlew test
```

Expected: 모든 test PASS.

- [x] **Step 5: build 실행**

Run:

```bash
./gradlew build
```

Expected: build SUCCESS.

- [x] **Step 6: self-review 수행**

검토 항목:
- 새 abstraction이 실제 runtime 책임 때문에 필요한가?
- test 편의를 위해 production design을 왜곡하지 않았는가?
- phase 1/2 중복 replay 경계가 명확한가?
- Redis key namespace 처리가 `TrafficRedisKeyFactory` 경로로 일원화되어 있는가?
- `FAILED` target이 남아 있는 상태에서 다음 phase로 넘어갈 수 없는가?
- 복구 flag active 중 ACK, DLQ, done log 생성이 차단되는가?

Expected: 발견된 문제를 수정하고 최소 verification을 다시 실행합니다.

---

## 완료 기준

- 설계 명세의 목표와 비목표가 구현 결과와 일치합니다.
- 모든 phase가 `LINE_DAILY_BATCH_JOB` metadata로 추적됩니다.
- traffic guard가 fail-closed로 동작합니다.
- phase 1/2 replay가 idempotency key와 Lua 원자 구간으로 중복 적용을 막습니다.
- 전체 검증과 자동 보정이 sampling 없이 수행됩니다.
- `./gradlew test`와 `./gradlew build`가 성공합니다.
- `superpowers:verification-before-completion` 기준으로 fresh verification evidence 없이 완료를 보고하지 않습니다.
- `superpowers:finishing-a-development-branch`는 구현 완료와 verification 통과 후에만 사용합니다.
