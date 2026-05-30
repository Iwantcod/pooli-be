# Traffic Restore Idempotency After Commit 구현 계획서

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development`를 기본으로 사용한다. 이 작업은 Redis idempotency, DB transaction commit, replay 정합성에 걸친 고위험 변경이다. 단계는 checkbox(`- [ ]`) 문법으로 추적한다.

**Goal:** Redis 복구 phase 1/2 replay 성공 처리에서 DB terminal update가 실제로 1건 반영되고 commit된 뒤에만 idempotency key를 삭제한다.

**Architecture:** 기존 replay service 내부에 post-commit callback 등록을 직접 추가하고, 새 공용 abstraction은 만들지 않는다. Phase 1은 `RESTORE_DAILY_APP_TARGET`의 `worker_id` 조건이 이미 ownership 역할을 하므로 update count만 확인한다. Phase 2는 현재 `TRAFFIC_DEDUCT_DONE`에 worker 소유 컬럼이 없으므로 schema 변경 없이 `restore_status = PROCESSING` 조건의 update count를 성공 기준으로 삼고, worker ownership 컬럼 추가는 이번 범위에서 제외한다.

**Tech Stack:** Spring Boot 3.5.10, Spring Framework 6.2.15 transaction synchronization, MyBatis core 3.5.19 / starter 3.0.5, Spring Data Redis 3.5.8 + Lettuce 6.6.0.RELEASE, JUnit Jupiter 5.12.2, Mockito 5.17.0

---

## 적용 기준

- `docs/context7-dependencies.yaml` 기준 버전을 사용한다.
- Spring transaction API는 Context7 `/spring-projects/spring-framework`로 확인한 `TransactionSynchronizationManager.registerSynchronization(...)`와 `TransactionSynchronization.afterCommit()`를 사용한다.
- 테스트 작성은 `docs/junit-unit-test-guide.md`의 FIRST 원칙, `@ExtendWith(MockitoExtension.class)`, self-validating assertion 기준을 따른다.
- inline immediate delete fallback은 두지 않는다. `@Transactional` service method의 transaction synchronization에만 Redis 삭제를 등록한다.
- `TrafficRestoreIdempotencyCleanupService`는 전체 검증 성공 후 prefix scan으로 잔여 key를 정리하는 최종 cleanup 역할을 유지한다. replay 단계의 개별 key post-commit 삭제와 책임이 충돌하지 않는다.

## 파일 구조

- Modify: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayServiceTest.java`
  - Phase 1 성공 update count가 1일 때 afterCommit 전에는 Redis key를 삭제하지 않고 afterCommit 후 삭제하는지 검증한다.
  - Phase 1 update count가 0이면 afterCommit을 실행해도 Redis key를 삭제하지 않는지 검증한다.
- Modify: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayService.java`
  - `markTargetTerminalIfProcessing(...)` 반환값이 1일 때만 idempotency key 삭제 callback을 등록한다.
  - `replayLuaExecutor.deleteIdempotencyKey(...)` 직접 호출을 제거한다.
- Modify: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayServiceTest.java`
  - Phase 2 성공 update count가 1일 때 afterCommit 이후에만 삭제하는지 검증한다.
  - Phase 2 update count가 0이면 삭제하지 않는지 검증한다.
- Modify: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayService.java`
  - `markRestoreDoneIfProcessing(...)` 반환값이 1일 때만 idempotency key 삭제 callback을 등록한다.
  - `replayLuaExecutor.deleteIdempotencyKey(...)` 직접 호출을 제거한다.
- No change: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreIdempotencyCleanupService.java`
  - 역할 검토만 수행한다. 최종 검증 성공 후 prefix scan cleanup 책임은 그대로 둔다.
- No change: `src/main/resources/mapper/traffic/TrafficDeductDoneLogMapper.xml`
  - `TRAFFIC_DEDUCT_DONE`에는 phase 2 worker ownership 컬럼이 없으므로 이번 최소 변경에서는 mapper 조건을 확장하지 않는다.

---

### Task 1: Phase 1 replay post-commit 테스트 추가

**Files:**
- Modify: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayServiceTest.java`

- [ ] **Step 1: Phase 1 테스트 import를 보강한다**

현재 static import에 `never`를 추가하고 Spring transaction synchronization import를 추가한다.

```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
```

- [ ] **Step 2: 기존 APPLIED 성공 테스트를 afterCommit 검증으로 바꾼다**

`marksTargetDoneWhenReplayApplied()`를 아래 형태로 갱신한다.

```java
@Test
@DisplayName("phase 1 worker는 DONE 전환 commit 이후 idempotency key를 제거한다")
void marksTargetDoneWhenReplayApplied() {
    TrafficRestoreDailyAppTarget target = target();
    TrafficRestoreReplayCommand command = command();
    when(dailyAppTargetMapper.selectReplayCommand(target.getId())).thenReturn(command);
    when(replayLuaExecutor.replay(command)).thenReturn(new TrafficRestoreReplayResult("APPLIED", null));
    when(dailyAppTargetMapper.markTargetTerminalIfProcessing(
            target.getId(),
            TrafficRestoreTargetStatus.DONE,
            WORKER_ID
    )).thenReturn(1);

    TransactionSynchronizationManager.initSynchronization();
    try {
        service.replay(target, WORKER_ID);

        verify(dailyAppTargetMapper).markTargetTerminalIfProcessing(
                target.getId(),
                TrafficRestoreTargetStatus.DONE,
                WORKER_ID
        );
        verify(replayLuaExecutor, never()).deleteIdempotencyKey(command.getIdempotencyKey());

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(replayLuaExecutor).deleteIdempotencyKey(command.getIdempotencyKey());
    } finally {
        TransactionSynchronizationManager.clearSynchronization();
    }
}
```

- [ ] **Step 3: 기존 SKIPPED 성공 테스트도 afterCommit 검증으로 바꾼다**

`marksTargetDoneWhenReplaySkippedByIdempotency()`를 아래 형태로 갱신한다.

```java
@Test
@DisplayName("phase 1 worker는 idempotency skip이어도 DONE 전환 commit 이후 key를 제거한다")
void marksTargetDoneWhenReplaySkippedByIdempotency() {
    TrafficRestoreDailyAppTarget target = target();
    TrafficRestoreReplayCommand command = command();
    when(dailyAppTargetMapper.selectReplayCommand(target.getId())).thenReturn(command);
    when(replayLuaExecutor.replay(command)).thenReturn(new TrafficRestoreReplayResult("SKIPPED", null));
    when(dailyAppTargetMapper.markTargetTerminalIfProcessing(
            target.getId(),
            TrafficRestoreTargetStatus.DONE,
            WORKER_ID
    )).thenReturn(1);

    TransactionSynchronizationManager.initSynchronization();
    try {
        service.replay(target, WORKER_ID);

        verify(dailyAppTargetMapper).markTargetTerminalIfProcessing(
                target.getId(),
                TrafficRestoreTargetStatus.DONE,
                WORKER_ID
        );
        verify(replayLuaExecutor, never()).deleteIdempotencyKey(command.getIdempotencyKey());

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(replayLuaExecutor).deleteIdempotencyKey(command.getIdempotencyKey());
    } finally {
        TransactionSynchronizationManager.clearSynchronization();
    }
}
```

- [ ] **Step 4: Phase 1 update count 0이면 cleanup callback을 등록하지 않는 테스트를 추가한다**

```java
@Test
@DisplayName("phase 1 worker는 DONE 전환 ownership을 잃으면 idempotency key를 제거하지 않는다")
void doesNotDeleteIdempotencyKeyWhenDoneUpdateAffectsNoRows() {
    TrafficRestoreDailyAppTarget target = target();
    TrafficRestoreReplayCommand command = command();
    when(dailyAppTargetMapper.selectReplayCommand(target.getId())).thenReturn(command);
    when(replayLuaExecutor.replay(command)).thenReturn(new TrafficRestoreReplayResult("APPLIED", null));
    when(dailyAppTargetMapper.markTargetTerminalIfProcessing(
            target.getId(),
            TrafficRestoreTargetStatus.DONE,
            WORKER_ID
    )).thenReturn(0);

    TransactionSynchronizationManager.initSynchronization();
    try {
        service.replay(target, WORKER_ID);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(replayLuaExecutor, never()).deleteIdempotencyKey(command.getIdempotencyKey());
    } finally {
        TransactionSynchronizationManager.clearSynchronization();
    }
}
```

- [ ] **Step 5: Phase 1 테스트를 실행해 실패를 확인한다**

Run:

```bash
./gradlew test --tests "com.pooli.traffic.service.restore.TrafficRestorePhase1ReplayServiceTest"
```

Expected: 현재 production code가 즉시 삭제를 수행하므로 afterCommit 전 `never()` 검증 또는 synchronization 등록 검증에서 FAIL.

---

### Task 2: Phase 1 replay cleanup을 DB commit 이후로 이동

**Files:**
- Modify: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayService.java`

- [ ] **Step 1: Spring transaction synchronization import를 추가한다**

```java
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
```

- [ ] **Step 2: DONE update count가 1일 때만 cleanup callback을 등록한다**

`replay(...)` 성공 경로를 아래 형태로 바꾼다.

```java
int updated = dailyAppTargetMapper.markTargetTerminalIfProcessing(
        target.getId(),
        TrafficRestoreTargetStatus.DONE,
        workerId
);
if (updated == 1) {
    registerIdempotencyCleanupAfterCommit(command.getIdempotencyKey());
}
```

- [ ] **Step 3: private helper를 추가한다**

같은 class 안에 아래 method를 추가한다. 새 공용 class는 만들지 않는다.

```java
/**
 * DB terminal 상태 전환 commit이 확정된 뒤에만 Redis replay idempotency key를 제거한다.
 */
private void registerIdempotencyCleanupAfterCommit(String idempotencyKey) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            replayLuaExecutor.deleteIdempotencyKey(idempotencyKey);
        }
    });
}
```

- [ ] **Step 4: 즉시 삭제 호출을 제거한다**

아래 코드는 남기지 않는다.

```java
replayLuaExecutor.deleteIdempotencyKey(command.getIdempotencyKey());
```

- [ ] **Step 5: Phase 1 테스트를 실행해 통과를 확인한다**

Run:

```bash
./gradlew test --tests "com.pooli.traffic.service.restore.TrafficRestorePhase1ReplayServiceTest"
```

Expected: PASS.

---

### Task 3: Phase 2 replay post-commit 테스트 추가

**Files:**
- Modify: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayServiceTest.java`

- [ ] **Step 1: Phase 2 테스트 import를 보강한다**

```java
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
```

- [ ] **Step 2: 기존 APPLIED 성공 테스트를 afterCommit 검증으로 바꾼다**

`marksDoneWhenReplayApplied()`를 아래 형태로 갱신한다.

```java
@Test
@DisplayName("phase 2 replay 성공 후 done log commit 이후 idempotency key를 제거한다")
void marksDoneWhenReplayApplied() {
    TrafficDeductDoneLog log = doneLog(10L, "PROCESSING");
    when(trafficRedisKeyFactory.restoreIdempotencyKey("p2:done_log", "10"))
            .thenReturn("pooli:restore:idempotency:p2:done_log:10");
    when(replayLuaExecutor.replay(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new TrafficRestoreReplayResult("APPLIED", null));
    when(doneLogMapper.markRestoreDoneIfProcessing(10L, WORKER_ID)).thenReturn(1);

    TransactionSynchronizationManager.initSynchronization();
    try {
        service.replay(log, WORKER_ID);

        verify(doneLogMapper).markRestoreDoneIfProcessing(10L, WORKER_ID);
        verify(replayLuaExecutor, never()).deleteIdempotencyKey("pooli:restore:idempotency:p2:done_log:10");

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(replayLuaExecutor).deleteIdempotencyKey("pooli:restore:idempotency:p2:done_log:10");
    } finally {
        TransactionSynchronizationManager.clearSynchronization();
    }
}
```

- [ ] **Step 3: Phase 2 update count 0이면 cleanup callback을 등록하지 않는 테스트를 추가한다**

```java
@Test
@DisplayName("phase 2 worker는 DONE 전환 대상이 없으면 idempotency key를 제거하지 않는다")
void doesNotDeleteIdempotencyKeyWhenDoneUpdateAffectsNoRows() {
    TrafficDeductDoneLog log = doneLog(10L, "PROCESSING");
    when(trafficRedisKeyFactory.restoreIdempotencyKey("p2:done_log", "10"))
            .thenReturn("pooli:restore:idempotency:p2:done_log:10");
    when(replayLuaExecutor.replay(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new TrafficRestoreReplayResult("APPLIED", null));
    when(doneLogMapper.markRestoreDoneIfProcessing(10L, WORKER_ID)).thenReturn(0);

    TransactionSynchronizationManager.initSynchronization();
    try {
        service.replay(log, WORKER_ID);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(replayLuaExecutor, never()).deleteIdempotencyKey("pooli:restore:idempotency:p2:done_log:10");
    } finally {
        TransactionSynchronizationManager.clearSynchronization();
    }
}
```

- [ ] **Step 4: Phase 2 테스트를 실행해 실패를 확인한다**

Run:

```bash
./gradlew test --tests "com.pooli.traffic.service.restore.TrafficRestorePhase2ReplayServiceTest"
```

Expected: 현재 production code가 즉시 삭제를 수행하므로 afterCommit 전 `never()` 검증 또는 synchronization 등록 검증에서 FAIL.

---

### Task 4: Phase 2 replay cleanup을 DB commit 이후로 이동

**Files:**
- Modify: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayService.java`

- [ ] **Step 1: Spring transaction synchronization import를 추가한다**

```java
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
```

- [ ] **Step 2: DONE update count가 1일 때만 cleanup callback을 등록한다**

`replay(...)` 성공 경로를 아래 형태로 바꾼다.

```java
int updated = doneLogMapper.markRestoreDoneIfProcessing(log.getTrafficDeductDoneId(), workerId);
if (updated == 1) {
    registerIdempotencyCleanupAfterCommit(command.getIdempotencyKey());
}
```

- [ ] **Step 3: private helper를 추가한다**

```java
/**
 * DB terminal 상태 전환 commit이 확정된 뒤에만 Redis replay idempotency key를 제거한다.
 */
private void registerIdempotencyCleanupAfterCommit(String idempotencyKey) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            replayLuaExecutor.deleteIdempotencyKey(idempotencyKey);
        }
    });
}
```

- [ ] **Step 4: 즉시 삭제 호출을 제거한다**

아래 코드는 남기지 않는다.

```java
replayLuaExecutor.deleteIdempotencyKey(command.getIdempotencyKey());
```

- [ ] **Step 5: Phase 2 테스트를 실행해 통과를 확인한다**

Run:

```bash
./gradlew test --tests "com.pooli.traffic.service.restore.TrafficRestorePhase2ReplayServiceTest"
```

Expected: PASS.

---

### Task 5: cleanup 책임 충돌 및 즉시 삭제 잔여 호출 검증

**Files:**
- Inspect only: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreIdempotencyCleanupService.java`
- Inspect only: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreVerificationService.java`
- Inspect only: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreReplayLuaExecutor.java`

- [ ] **Step 1: replay service 외 idempotency cleanup 책임을 확인한다**

Run:

```bash
rg -n "cleanupRestoreIdempotencyKeys|deleteIdempotencyKey|restoreIdempotencyKeyPattern" src/main/java/com/pooli/traffic/service/restore src/test/java/com/pooli/traffic/service/restore
```

Expected:
- `TrafficRestoreVerificationService`는 전체 검증 성공 후 `cleanupRestoreIdempotencyKeys()`만 호출한다.
- `TrafficRestoreIdempotencyCleanupService`는 prefix scan cleanup만 수행한다.
- `TrafficRestorePhase1ReplayService`와 `TrafficRestorePhase2ReplayService`는 `afterCommit()` 내부에서만 `deleteIdempotencyKey(...)`를 호출한다.
- `TrafficRestoreReplayLuaExecutor`의 `deleteIdempotencyKey(...)` method 자체는 유지한다.

- [ ] **Step 2: Phase 2 worker ownership scope를 명시적으로 기록한다**

이번 변경에서는 `TRAFFIC_DEDUCT_DONE` schema에 `restore_worker_id` 또는 `worker_id`가 없으므로 mapper XML에 worker 조건을 추가하지 않는다. 성공 기준은 기존 `restore_status = 'PROCESSING'` 조건 update count `== 1`이다. worker ownership 컬럼 추가가 필요하면 별도 schema 변경 계획으로 분리한다.

---

### Task 6: 전체 검증과 자체 검토

**Files:**
- Verify only: changed production/test files

- [ ] **Step 1: 관련 단위 테스트를 실행한다**

Run:

```bash
./gradlew test --tests "com.pooli.traffic.service.restore.TrafficRestorePhase1ReplayServiceTest" --tests "com.pooli.traffic.service.restore.TrafficRestorePhase2ReplayServiceTest"
```

Expected: PASS.

- [ ] **Step 2: compile을 실행한다**

Run:

```bash
./gradlew compileJava compileTestJava
```

Expected: PASS.

- [ ] **Step 3: 전체 테스트를 실행한다**

Run:

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 4: 자체 검토를 수행한다**

검토 항목:
- update count가 0일 때 Redis key 삭제 callback이 등록되지 않는가?
- Redis key 삭제가 `afterCommit()` 밖에서 실행되는 경로가 남아 있지 않은가?
- rollback 상황에서 `afterCommit()`이 실행되지 않는 한 삭제가 발생하지 않는가?
- 새 class, 새 dependency, schema 변경 없이 요구사항을 만족하는가?
- `TrafficRestoreIdempotencyCleanupService`의 최종 prefix cleanup 책임을 침범하지 않았는가?
- Phase 2 worker ownership 컬럼 추가 같은 범위 확장이 들어가지 않았는가?

- [ ] **Step 5: 변경 요약과 미해결 범위를 보고한다**

보고 내용:
- 변경 파일 목록
- Phase 1/2 update count gating 결과
- post-commit cleanup 검증 결과
- `TrafficRestoreIdempotencyCleanupService`와 책임 충돌 없음
- Phase 2 worker ownership schema 변경은 이번 최소 변경에서 제외했다는 사실
- 실행한 Gradle 검증 결과
- 코드 커밋은 사용자 명시 확인 전에는 수행하지 않았다는 사실

---

## 승인 후 실행 방식

이 계획은 아직 승인되지 않았다. 사용자가 이 계획을 명시적으로 승인하거나 실행을 지시하기 전에는 production code와 test code를 수정하지 않는다.

승인 후에는 고위험 Redis/DB 정합성 변경이므로 `superpowers:using-git-worktrees`를 먼저 적용하고, `superpowers:subagent-driven-development`로 task 단위 구현과 review를 진행한다. 코드 변경 commit은 사용자 명시 확인 후에만 수행한다.

## Dependency / Context7 기록

- `version`: Spring Boot 3.5.10, Spring Framework 6.2.15, MyBatis core 3.5.19 / starter 3.0.5, Spring Data Redis 3.5.8 + Lettuce 6.6.0.RELEASE, JUnit Jupiter 5.12.2, Mockito 5.17.0
- `source`: `docs/context7-dependencies.yaml`
- `context7_library_id`: `/spring-projects/spring-framework`
- `context7_reason`: `TransactionSynchronizationManager.registerSynchronization(...)`와 `TransactionSynchronization.afterCommit()` 기반 post-commit callback 사용 방식을 확인하기 위해 1회 사용했다.
