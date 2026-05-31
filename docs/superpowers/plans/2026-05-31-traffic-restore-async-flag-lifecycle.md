# Traffic Restore Async Flag Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan because it touches async execution, Redis lock/session idempotency, restore flag lifecycle, and resume semantics. If subagents cannot be used in the current environment, use `superpowers:executing-plans` and execute task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자 Redis 복구 시작/재개 API가 요청 스레드를 장시간 점유하지 않고, 실패/재개 경로에서도 복구 flag를 일관되게 정리하며, 중복 시작을 방지하게 한다.

**Architecture:** `TrafficRestoreOrchestratorService.start()`는 start date 검증과 Redis manager lock 획득만 요청 스레드에서 수행한 뒤 background worker를 제출하고 즉시 접수 응답을 반환한다. worker는 flag 활성화, policy hydrate, worst-processing 대기, phase 실행을 `try`에서 수행하고 `finally`에서 flag와 Redis lock을 해제한다. `resume()`은 기존 target reset과 metadata restart를 유지하되, 재개 허용 시 같은 background worker를 제출해 flag lifecycle을 start와 공유한다.

**Tech Stack:** Java 21, Spring Boot 3.5.10, Spring Data Redis 3.5.8 + Lettuce 6.6.0.RELEASE, MyBatis core 3.5.19 / starter 3.0.5, JUnit Jupiter 5.12.2, Mockito 5.17.0

---

## 검토 결과

- 유효: `TrafficRestoreOrchestratorService.start()`는 `hydrateOnDemand()`, `waitWorstProcessingTimePlusBuffer()`, `executionService.execute(...)`를 요청 스레드에서 동기 실행한다.
- 유효: `start()`는 정상 경로에서만 `policyFlagService.deactivateRestoreFlag()`를 호출한다. `hydrateOnDemand()`, wait, execute 중 예외가 발생하면 flag가 남을 수 있다.
- 유효: `resume()`은 `findResumableRestorePhase()`와 `batchJobMapper.restartRestorePhaseBatch(...)`로 metadata만 재개하고 flag 활성화/비활성화나 실제 worker 실행을 하지 않는다.
- 유효: `start()` 중복 시작을 막는 restore session, in-memory lock, Redis/DB lock이 없다.
- 일부 제외: 새 DB lock table 또는 schema 변경은 계획하지 않는다. 현재 코드에 `TrafficRedisKeyFactory.trafficRestoreManagerLockKey()`와 owner 비교 lock release Lua 경로가 이미 있으므로, 최소 변경 원칙상 기존 Redis lock을 재사용한다.
- 일부 제외: `executionService.execute`가 terminal phase callback으로 flag를 해제하게 만드는 방식은 계획하지 않는다. 현재 `execute`는 phase 실행 책임만 갖고 있고 resume도 orchestrator에서 관리되므로, flag/lock lifecycle은 orchestrator worker의 단일 `finally`로 모은다.

## 의존성 및 Context7 기준

- `version`: Spring Boot 3.5.10, Spring Data Redis 3.5.8 + Lettuce 6.6.0.RELEASE, MyBatis core 3.5.19 / starter 3.0.5, JUnit Jupiter 5.12.2, Mockito 5.17.0
- `source`: `docs/context7-dependencies.yaml`, `docs/junit-unit-test-guide.md`
- `context7_library_id`: `not_used`
- 이유: 현재 계획은 저장소 내부 orchestration, Redis lock 재사용, Mockito 단위 테스트 변경이며 exact external API signature 확인이 필요하지 않다. 구현 중 Spring `Executor` 주입 또는 RedisTemplate API 서명 확인이 필요해지면 `docs/context7-dependencies.yaml`의 primary library id 기준으로 Context7를 사용한다.

## 파일 책임 구조

- Modify: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreOrchestratorService.java`
  - start 요청을 background worker 접수 방식으로 변경한다.
  - Redis restore manager lock 획득 실패 시 중복 요청 거절 응답을 반환한다.
  - worker 공통 메서드에서 flag activate/deactivate와 lock release를 `try/finally`로 보장한다.
  - flag 해제 실패가 Redis lock 해제를 막지 않도록 cleanup helper에서 각각 독립적으로 시도한다.
  - resume 재개 허용 시 같은 worker 메서드를 사용해 flag lifecycle을 공유한다.
- Modify: `src/test/java/com/pooli/traffic/service/restore/TrafficRestoreOrchestratorServiceTest.java`
  - 동기 실행 기대를 async 제출 기대와 worker 직접 실행 검증으로 바꾼다.
  - 실패 시 flag와 lock이 정리되는지 검증한다.
  - 중복 start lock 미획득 시 execute가 제출되지 않는지 검증한다.
  - resume 허용 시 flag lifecycle이 동일하게 적용되는지 검증한다.
- Modify: `src/main/java/com/pooli/traffic/domain/dto/response/TrafficRestoreStartResDto.java`
  - `nextPhase` Javadocs를 접수 상태를 포함하는 의미로 갱신한다.
- Modify: `src/test/java/com/pooli/traffic/controller/AdminTrafficRestoreControllerTest.java`
  - start delegation fixture의 `nextPhase`를 `RESTORE_ACCEPTED`로 갱신한다.

## Task 0: 실행 전 격리 workspace 확인

**Files:**
- No code change

- [x] **Step 1: `superpowers:using-git-worktrees` 사용**

승인 후 구현 시작 전에 `superpowers:using-git-worktrees`를 사용한다.

Expected:
- 현재 작업이 `main` 또는 `master`에서 직접 수행되지 않는다.
- 새 worktree 또는 안전한 feature branch에서 구현한다.

- [x] **Step 2: 작업 전 상태 확인**

Run:

```bash
git status --short
```

Expected:
- 사용자 또는 이전 작업자의 unrelated 변경이 있으면 되돌리지 않는다.
- 이 계획 문서 변경은 구현 변경과 분리해 유지한다.

## Task 1: start async 접수와 중복 시작 방지 실패 테스트 작성

**Files:**
- Modify: `src/test/java/com/pooli/traffic/service/restore/TrafficRestoreOrchestratorServiceTest.java`

- [x] **Step 1: 테스트 fixture에 executor와 Redis lock 의존성 추가**

`@InjectMocks`는 `Executor`, `StringRedisTemplate`, `ValueOperations`, `TrafficRedisKeyFactory`, `TrafficLuaScriptInfraService` 주입 제어가 어려우므로 명시 생성 방식으로 바꾼다.

```java
@Mock
private java.util.concurrent.Executor taskExecutor;

@Mock
private org.springframework.data.redis.core.StringRedisTemplate cacheStringRedisTemplate;

@Mock
private org.springframework.data.redis.core.ValueOperations<String, String> valueOperations;

@Mock
private com.pooli.traffic.service.runtime.TrafficRedisKeyFactory trafficRedisKeyFactory;

@Mock
private com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService trafficLuaScriptInfraService;

private TrafficRestoreOrchestratorService service;

@org.junit.jupiter.api.BeforeEach
void setUp() {
    service = new TrafficRestoreOrchestratorService(
            policyFlagService,
            policyBootstrapService,
            waitService,
            executionService,
            startDateResolver,
            batchJobMapper,
            hydrateTargetMapper,
            dailyAppTargetMapper,
            doneLogMapper,
            taskExecutor,
            cacheStringRedisTemplate,
            trafficRedisKeyFactory,
            trafficLuaScriptInfraService
    );
}
```

- [x] **Step 2: start가 worker를 제출하고 즉시 접수 응답을 반환하는 실패 테스트 작성**

```java
@Test
@DisplayName("복구 시작은 Redis lock 획득 후 background worker를 제출하고 접수 응답을 반환한다")
void startSubmitsBackgroundWorkerAndReturnsAcceptedResponse() {
    TrafficRestoreStartReqDto request = new TrafficRestoreStartReqDto(LocalDate.of(2026, 5, 29));
    when(startDateResolver.resolve(LocalDate.of(2026, 5, 29))).thenReturn(LocalDate.of(2026, 5, 27));
    when(trafficRedisKeyFactory.trafficRestoreManagerLockKey()).thenReturn("pooli:traffic:restore:manager-lock");
    when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(
            eq("pooli:traffic:restore:manager-lock"),
            anyString(),
            eq(java.time.Duration.ofMillis(TrafficRestoreOrchestratorService.RESTORE_MANAGER_LOCK_TTL_MS))
    )).thenReturn(true);

    var response = service.start(request);

    verify(taskExecutor).execute(any(Runnable.class));
    verify(policyFlagService, never()).activateRestoreFlag();
    verify(executionService, never()).execute(any(), any(), any());
    assertThat(response.accepted()).isTrue();
    assertThat(response.nextPhase()).isEqualTo("RESTORE_ACCEPTED");
    assertThat(response.failureDate()).isEqualTo(LocalDate.of(2026, 5, 29));
    assertThat(response.restoreStartDate()).isEqualTo(LocalDate.of(2026, 5, 27));
}
```

- [x] **Step 3: 중복 start lock 미획득 실패 테스트 작성**

```java
@Test
@DisplayName("복구 시작은 Redis lock을 획득하지 못하면 중복 요청으로 거절한다")
void startRejectsDuplicateWhenRestoreLockAlreadyHeld() {
    TrafficRestoreStartReqDto request = new TrafficRestoreStartReqDto(LocalDate.of(2026, 5, 29));
    when(startDateResolver.resolve(LocalDate.of(2026, 5, 29))).thenReturn(LocalDate.of(2026, 5, 27));
    when(trafficRedisKeyFactory.trafficRestoreManagerLockKey()).thenReturn("pooli:traffic:restore:manager-lock");
    when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(
            eq("pooli:traffic:restore:manager-lock"),
            anyString(),
            eq(java.time.Duration.ofMillis(TrafficRestoreOrchestratorService.RESTORE_MANAGER_LOCK_TTL_MS))
    )).thenReturn(false);

    var response = service.start(request);

    assertThat(response.accepted()).isFalse();
    assertThat(response.nextPhase()).isEqualTo("RESTORE_ALREADY_RUNNING");
    verify(taskExecutor, never()).execute(any(Runnable.class));
    verify(policyFlagService, never()).activateRestoreFlag();
    verify(executionService, never()).execute(any(), any(), any());
}
```

- [x] **Step 4: 실패 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreOrchestratorServiceTest
```

Expected:
- constructor mismatch 또는 expected interaction mismatch로 실패한다.

## Task 2: worker finally에서 flag와 lock을 정리하는 실패 테스트 작성

**Files:**
- Modify: `src/test/java/com/pooli/traffic/service/restore/TrafficRestoreOrchestratorServiceTest.java`

- [x] **Step 1: 제출된 worker를 캡처해 성공 경로 순서를 검증하는 실패 테스트 작성**

```java
@Test
@DisplayName("background worker는 flag 활성화, policy hydrate, 대기, phase 실행 후 flag와 lock을 정리한다")
void backgroundWorkerRunsRestoreAndAlwaysCleansUpOnSuccess() {
    TrafficRestoreStartReqDto request = new TrafficRestoreStartReqDto(LocalDate.of(2026, 5, 29));
    when(startDateResolver.resolve(LocalDate.of(2026, 5, 29))).thenReturn(LocalDate.of(2026, 5, 27));
    when(trafficRedisKeyFactory.trafficRestoreManagerLockKey()).thenReturn("pooli:traffic:restore:manager-lock");
    when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
    when(trafficLuaScriptInfraService.executeLockRelease(eq("pooli:traffic:restore:manager-lock"), anyString()))
            .thenReturn(true);
    ArgumentCaptor<Runnable> workerCaptor = ArgumentCaptor.forClass(Runnable.class);
    doNothing().when(taskExecutor).execute(workerCaptor.capture());

    service.start(request);
    workerCaptor.getValue().run();

    var inOrder = inOrder(policyFlagService, policyBootstrapService, waitService, executionService, trafficLuaScriptInfraService);
    inOrder.verify(policyFlagService).activateRestoreFlag();
    inOrder.verify(policyBootstrapService).hydrateOnDemand();
    inOrder.verify(waitService).waitWorstProcessingTimePlusBuffer();
    inOrder.verify(executionService).execute(
            eq(LocalDate.of(2026, 5, 29)),
            eq(LocalDate.of(2026, 5, 27)),
            eq(java.util.List.of(java.time.YearMonth.of(2026, 5)))
    );
    inOrder.verify(policyFlagService).deactivateRestoreFlag();
    inOrder.verify(trafficLuaScriptInfraService).executeLockRelease(eq("pooli:traffic:restore:manager-lock"), anyString());
}
```

- [x] **Step 2: execute 예외 경로에서도 정리되는 실패 테스트 작성**

```java
@Test
@DisplayName("background worker는 phase 실행이 실패해도 flag와 lock을 정리한다")
void backgroundWorkerCleansUpWhenExecutionFails() {
    TrafficRestoreStartReqDto request = new TrafficRestoreStartReqDto(LocalDate.of(2026, 5, 29));
    when(startDateResolver.resolve(LocalDate.of(2026, 5, 29))).thenReturn(LocalDate.of(2026, 5, 27));
    when(trafficRedisKeyFactory.trafficRestoreManagerLockKey()).thenReturn("pooli:traffic:restore:manager-lock");
    when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
    when(trafficLuaScriptInfraService.executeLockRelease(eq("pooli:traffic:restore:manager-lock"), anyString()))
            .thenReturn(true);
    doThrow(new IllegalStateException("restore failed"))
            .when(executionService)
            .execute(any(), any(), any());
    ArgumentCaptor<Runnable> workerCaptor = ArgumentCaptor.forClass(Runnable.class);
    doNothing().when(taskExecutor).execute(workerCaptor.capture());

    service.start(request);
    assertThatThrownBy(() -> workerCaptor.getValue().run())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("restore failed");

    verify(policyFlagService).deactivateRestoreFlag();
    verify(trafficLuaScriptInfraService).executeLockRelease(eq("pooli:traffic:restore:manager-lock"), anyString());
}
```

- [x] **Step 3: 실패 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreOrchestratorServiceTest
```

Expected:
- worker 캡처 또는 cleanup 검증이 실패한다.

## Task 3: start async worker와 Redis restore lock 구현

**Files:**
- Modify: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreOrchestratorService.java`

- [x] **Step 1: 필요한 의존성과 상수 추가**

```java
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

import lombok.extern.slf4j.Slf4j;
```

```java
@Slf4j
```

```java
static final long RESTORE_MANAGER_LOCK_TTL_MS = 6 * 60 * 60 * 1000L;

private final Executor taskExecutor;
@Qualifier("cacheStringRedisTemplate")
private final StringRedisTemplate cacheStringRedisTemplate;
private final TrafficRedisKeyFactory trafficRedisKeyFactory;
private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;
```

- [x] **Step 2: `start()`를 접수형으로 변경**

```java
public TrafficRestoreStartResDto start(TrafficRestoreStartReqDto request) {
    LocalDate failureDate = request.failureDate();
    LocalDate restoreStartDate = startDateResolver.resolve(failureDate);
    if (restoreStartDate.isAfter(failureDate)) {
        return new TrafficRestoreStartResDto(false, "NO_RESTORE_TARGET", failureDate, restoreStartDate);
    }

    RestoreLock restoreLock = tryAcquireRestoreLock();
    if (restoreLock == null) {
        return new TrafficRestoreStartResDto(false, "RESTORE_ALREADY_RUNNING", failureDate, restoreStartDate);
    }

    taskExecutor.execute(() -> runRestoreWorker(failureDate, restoreStartDate, restoreLock));
    return new TrafficRestoreStartResDto(true, "RESTORE_ACCEPTED", failureDate, restoreStartDate);
}
```

- [x] **Step 3: worker와 lock helper 구현**

```java
private void runRestoreWorker(LocalDate failureDate, LocalDate restoreStartDate, RestoreLock restoreLock) {
    try {
        policyFlagService.activateRestoreFlag();
        policyBootstrapService.hydrateOnDemand();
        waitService.waitWorstProcessingTimePlusBuffer();
        executionService.execute(
                failureDate,
                restoreStartDate,
                resolveTargetMonths(restoreStartDate, failureDate)
        );
    } finally {
        cleanupRestoreWorker(restoreLock);
    }
}

private void cleanupRestoreWorker(RestoreLock restoreLock) {
    try {
        policyFlagService.deactivateRestoreFlag();
    } catch (Exception e) {
        log.warn("traffic_restore_flag_deactivate_failed", e);
    } finally {
        releaseRestoreLock(restoreLock);
    }
}

private RestoreLock tryAcquireRestoreLock() {
    String lockKey = trafficRedisKeyFactory.trafficRestoreManagerLockKey();
    String owner = "traffic-restore:" + UUID.randomUUID();
    Boolean acquired = cacheStringRedisTemplate.opsForValue().setIfAbsent(
            lockKey,
            owner,
            Duration.ofMillis(RESTORE_MANAGER_LOCK_TTL_MS)
    );
    if (!Boolean.TRUE.equals(acquired)) {
        return null;
    }
    return new RestoreLock(lockKey, owner);
}

private void releaseRestoreLock(RestoreLock restoreLock) {
    try {
        trafficLuaScriptInfraService.executeLockRelease(restoreLock.lockKey(), restoreLock.owner());
    } catch (Exception e) {
        // 복구 작업 결과는 유지하고, lock TTL에 의한 자동 만료에 맡긴다.
    }
}

private record RestoreLock(String lockKey, String owner) {
}
```

- [x] **Step 4: 테스트 통과 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreOrchestratorServiceTest
```

Expected:
- Task 1-2 테스트가 PASS한다.

## Task 4: resume이 flag lifecycle을 공유하도록 실패 테스트 작성

**Files:**
- Modify: `src/test/java/com/pooli/traffic/service/restore/TrafficRestoreOrchestratorServiceTest.java`

- [x] **Step 1: resume 허용 시 worker 제출을 검증하는 실패 테스트 작성**

```java
@Test
@DisplayName("복구 재개는 실패 target을 되돌리고 background worker를 제출한다")
void resumeSubmitsBackgroundWorkerWhenRestartAccepted() {
    LocalDate anchorDate = LocalDate.of(2026, 5, 29);
    LineDailyBatchJob batchJob = LineDailyBatchJob.builder()
            .id(10L)
            .batchName(BatchName.RESTORE_P2_DONE_LOG_REPLAY)
            .usageDate(anchorDate)
            .status(LineDailyBatchStatus.FAILED)
            .build();
    when(batchJobMapper.selectLatestByBatchNameAndUsageDate(BatchName.RESTORE_P2_DONE_LOG_REPLAY, anchorDate))
            .thenReturn(batchJob);
    when(batchJobMapper.restartRestorePhaseBatch(10L, BatchName.RESTORE_P2_DONE_LOG_REPLAY, "admin-resume"))
            .thenReturn(1);
    when(trafficRedisKeyFactory.trafficRestoreManagerLockKey()).thenReturn("pooli:traffic:restore:manager-lock");
    when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

    var response = service.resume(anchorDate);

    verify(doneLogMapper).resetFailedRestoreLogsToRetryable(anchorDate.plusDays(1).atStartOfDay());
    verify(taskExecutor).execute(any(Runnable.class));
    assertThat(response.resumeAccepted()).isTrue();
    assertThat(response.currentStatus()).isEqualTo("FAILED");
}
```

- [x] **Step 2: resume 중복 lock 미획득 시 metadata를 재시작하지 않는 실패 테스트 작성**

```java
@Test
@DisplayName("복구 재개는 Redis lock을 획득하지 못하면 metadata를 재시작하지 않고 거절한다")
void resumeRejectsWhenRestoreLockAlreadyHeld() {
    LocalDate anchorDate = LocalDate.of(2026, 5, 29);
    LineDailyBatchJob batchJob = LineDailyBatchJob.builder()
            .id(10L)
            .batchName(BatchName.RESTORE_P2_DONE_LOG_REPLAY)
            .usageDate(anchorDate)
            .status(LineDailyBatchStatus.FAILED)
            .build();
    when(batchJobMapper.selectLatestByBatchNameAndUsageDate(BatchName.RESTORE_P2_DONE_LOG_REPLAY, anchorDate))
            .thenReturn(batchJob);
    when(trafficRedisKeyFactory.trafficRestoreManagerLockKey()).thenReturn("pooli:traffic:restore:manager-lock");
    when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

    var response = service.resume(anchorDate);

    assertThat(response.resumeAccepted()).isFalse();
    assertThat(response.currentStatus()).isEqualTo("RESTORE_ALREADY_RUNNING");
    verify(batchJobMapper, never()).restartRestorePhaseBatch(any(), any(), any());
    verify(taskExecutor, never()).execute(any(Runnable.class));
}
```

- [x] **Step 3: 실패 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreOrchestratorServiceTest
```

Expected:
- resume worker 제출과 lock 순서 기대가 실패한다.

## Task 5: resume worker 위임 구현

**Files:**
- Modify: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreOrchestratorService.java`

- [x] **Step 1: `resume()`에서 lock을 먼저 획득하고 metadata 재시작을 CAS 결과로 판단**

```java
public TrafficRestoreResumeResDto resume(LocalDate anchorDate) {
    LineDailyBatchJob resumableJob = findResumableRestorePhase(anchorDate);
    if (resumableJob == null || resumableJob.getStatus() == null) {
        return new TrafficRestoreResumeResDto(anchorDate, false, "NOT_FOUND");
    }

    if (resumableJob.getStatus() != LineDailyBatchStatus.FAILED
            && resumableJob.getStatus() != LineDailyBatchStatus.ABANDONED) {
        return new TrafficRestoreResumeResDto(anchorDate, false, resumableJob.getStatus().name());
    }

    RestoreLock restoreLock = tryAcquireRestoreLock();
    if (restoreLock == null) {
        return new TrafficRestoreResumeResDto(anchorDate, false, "RESTORE_ALREADY_RUNNING");
    }

    try {
        resetFailedTargets(resumableJob.getBatchName(), anchorDate);
        int updated = batchJobMapper.restartRestorePhaseBatch(
                resumableJob.getId(),
                resumableJob.getBatchName(),
                "admin-resume"
        );
        if (updated != 1) {
            releaseRestoreLock(restoreLock);
            return new TrafficRestoreResumeResDto(anchorDate, false, resumableJob.getStatus().name());
        }
        taskExecutor.execute(() -> runRestoreWorker(anchorDate, anchorDate, restoreLock));
        return new TrafficRestoreResumeResDto(anchorDate, true, resumableJob.getStatus().name());
    } catch (RuntimeException e) {
        releaseRestoreLock(restoreLock);
        throw e;
    }
}
```

- [x] **Step 2: resume worker 범위 self-review**

검토 기준:
- 기존 `resume(anchorDate)`는 `restoreStartDate`를 별도 입력받지 않는다.
- 현재 `TrafficRestoreResumeResDto`도 `anchorDate`만 반환한다.
- 따라서 최소 변경은 `runRestoreWorker(anchorDate, anchorDate, lock)`로 같은 날짜의 phase 0~2 재개 drain을 수행하는 것이다.
- 구현 중 실제 재개 범위가 `failureDate != restoreStartDate`를 반드시 복원해야 한다는 코드 근거가 발견되면 중단하고, 응답 DTO 또는 metadata에 restore range를 저장하는 별도 계획을 사용자에게 요청한다.

- [x] **Step 3: 테스트 통과 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreOrchestratorServiceTest
```

Expected:
- Task 4 테스트가 PASS한다.

## Task 6: controller fixture와 문서 주석 최소 갱신

**Files:**
- Modify: `src/main/java/com/pooli/traffic/domain/dto/response/TrafficRestoreStartResDto.java`
- Modify: `src/test/java/com/pooli/traffic/controller/AdminTrafficRestoreControllerTest.java`

- [x] **Step 1: start 응답 의미 Javadocs 갱신**

`TrafficRestoreStartResDto`의 `nextPhase` 설명을 접수 상태까지 포괄하도록 바꾼다.

```java
/**
 * 관리자 Redis 복구 시작 응답이다.
 *
 * @param accepted 복구 시작 요청이 접수됐는지 여부
 * @param nextPhase 요청 접수 후 상태 또는 다음 처리 상태
 * @param failureDate Redis 장애가 발생해 복구 anchor로 삼은 업무일
 * @param restoreStartDate 서버가 계산한 미완료 데이터 복구 시작 업무일
 */
```

- [x] **Step 2: controller delegation fixture 기대값 갱신**

```java
TrafficRestoreStartResDto serviceResponse = new TrafficRestoreStartResDto(
        true,
        "RESTORE_ACCEPTED",
        LocalDate.of(2026, 5, 29),
        LocalDate.of(2026, 5, 27)
);
```

- [x] **Step 3: controller 테스트 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.controller.AdminTrafficRestoreControllerTest
```

Expected:
- PASS.

## Task 7: 최종 검증과 self-review

**Files:**
- No code change

- [x] **Step 1: focused tests 실행**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreOrchestratorServiceTest
./gradlew test --tests com.pooli.traffic.controller.AdminTrafficRestoreControllerTest
```

Expected:
- `BUILD SUCCESSFUL`.

- [x] **Step 2: restore 관련 회귀 테스트 실행**

Run:

```bash
./gradlew test --tests 'com.pooli.traffic.service.restore.*'
```

Expected:
- `BUILD SUCCESSFUL`.

- [x] **Step 3: 전체 단위 테스트 실행**

Run:

```bash
./gradlew test
```

Expected:
- `BUILD SUCCESSFUL`.

- [x] **Step 4: self-review**

확인 항목:
- 중복 시작 방지는 Redis restore manager lock 한 곳에서만 처리한다.
- flag activate/deactivate는 start와 resume worker 모두 같은 `runRestoreWorker` 경로를 사용한다.
- worker 실패 시 flag와 lock이 `finally`에서 정리된다.
- test 편의를 위해 production constructor, visibility, DTO 구조를 불필요하게 왜곡하지 않는다.
- DB schema, 신규 table, 신규 dependency, broad refactor를 추가하지 않는다.
- `resume()`의 날짜 범위가 현재 DTO/metadata만으로 충분한지 다시 확인하고, 충분하지 않으면 구현을 중단해 범위 저장 설계를 별도 승인받는다.
