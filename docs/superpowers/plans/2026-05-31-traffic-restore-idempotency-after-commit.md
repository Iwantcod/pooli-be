# Traffic Restore Idempotency After Commit 구현 계획서

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. 현재 코드에서는 제보된 즉시 삭제 문제가 이미 해소되어 있으므로, 단계는 검증과 회귀 방지 확인을 중심으로 checkbox(`- [ ]`) 문법으로 추적한다.

**Goal:** `TrafficRestorePhase1ReplayService`와 `TrafficRestorePhase2ReplayService`에서 Redis replay idempotency key 삭제가 MySQL DONE commit 이후에만 실행되는지 검증하고, 현재 코드가 이미 충족하면 소스 변경 없이 완료한다.

**Architecture:** 각 replay service는 Redis Lua replay 성공 또는 idempotency skip 이후 DB terminal update count가 `1`인 경우에만 `TransactionSynchronizationManager.registerSynchronization(...)`으로 `afterCommit()` cleanup을 등록한다. Redis key 삭제는 `afterCommit()` 내부에서만 수행하며, update count가 `0`이면 cleanup callback을 등록하지 않는다. 새 공용 abstraction, schema 변경, mapper 조건 변경은 현재 검토 범위에 포함하지 않는다.

**Tech Stack:** Spring Boot 3.5.10, Spring Framework 6.2.15 transaction synchronization, MyBatis core 3.5.19 / starter 3.0.5, Spring Data Redis 3.5.8 + Lettuce 6.6.0.RELEASE, JUnit Jupiter 5.12.2, Mockito 5.17.0

---

## 현재 검토 결과

- `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayService.java`
  - 현재 `replay(...)`는 `markRestoreDoneIfProcessing(...)` 반환값이 `1`일 때만 `registerIdempotencyCleanupAfterCommit(command.getIdempotencyKey())`를 호출한다.
  - `deleteIdempotencyKey(...)` 직접 호출은 `afterCommit()` 내부에만 존재한다.
  - 따라서 제보된 “DONE 전환 후 commit 전 즉시 Redis idempotency key 삭제” 문제는 현재 코드 기준으로는 재현되지 않는다.
- `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayService.java`
  - 현재 `replay(...)`도 `markTargetTerminalIfProcessing(...)` 반환값이 `1`일 때만 `afterCommit()` cleanup을 등록한다.
  - 같은 패턴의 즉시 삭제 문제는 현재 코드 기준으로는 재현되지 않는다.
- `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayServiceTest.java`
  - APPLIED, SKIPPED 성공 경로에서 `afterCommit()` 전에는 삭제하지 않고 `afterCommit()` 후 삭제하는 테스트가 있다.
  - DONE update count가 `0`이면 synchronization이 비어 있고 삭제하지 않는 테스트가 있다.
- `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayServiceTest.java`
  - APPLIED 성공 경로에서 `afterCommit()` 전에는 삭제하지 않고 `afterCommit()` 후 삭제하는 테스트가 있다.
  - DONE update count가 `0`이면 synchronization이 비어 있고 삭제하지 않는 테스트가 있다.

## 적용 기준

- `docs/context7-dependencies.yaml` 기준 버전을 사용한다.
- Spring transaction API는 Context7 `/spring-projects/spring-framework`로 확인한 Spring Framework 6.2.15 기준을 따른다.
- `TransactionSynchronizationAdapter`는 사용하지 않고 현재 코드처럼 `TransactionSynchronization`을 직접 구현한다.
- 테스트 작성 또는 수정이 필요해지는 경우 `docs/junit-unit-test-guide.md`의 FIRST 원칙, `@ExtendWith(MockitoExtension.class)`, self-validating assertion 기준을 따른다.
- 현재 검토 결과가 유지되면 production code와 test code는 수정하지 않는다.

## 파일 구조

- Inspect only: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayService.java`
  - Phase 1 replay cleanup이 `afterCommit()` 내부에서만 수행되는지 확인한다.
- Inspect only: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayService.java`
  - Phase 2 replay cleanup이 `afterCommit()` 내부에서만 수행되는지 확인한다.
- Inspect only: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayServiceTest.java`
  - Phase 1 post-commit cleanup 회귀 테스트가 존재하는지 확인한다.
- Inspect only: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayServiceTest.java`
  - Phase 2 post-commit cleanup 회귀 테스트가 존재하는지 확인한다.
- No change: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreReplayLuaExecutor.java`
  - `deleteIdempotencyKey(...)`의 “MySQL DONE commit 이후 삭제” 계약은 현재 replay service 구현과 일치한다.
- No change: `src/main/resources/mapper/traffic/TrafficDeductDoneLogMapper.xml`
  - 이번 검토는 commit 이후 Redis cleanup 시점만 다룬다. Phase 2 worker ownership schema 또는 mapper 조건 변경은 별도 요구가 있을 때 분리한다.

---

### Task 1: 현재 production code에서 즉시 삭제 잔여 경로 확인

**Files:**
- Inspect only: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayService.java`
- Inspect only: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayService.java`

- [x] **Step 1: replay service의 삭제 호출 위치를 검색한다**

Run:

```bash
rg -n "deleteIdempotencyKey|registerIdempotencyCleanupAfterCommit|afterCommit" src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayService.java src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayService.java
```

Expected:
- 두 service 모두 `registerIdempotencyCleanupAfterCommit(...)` helper가 있다.
- `replay(...)` 본문에는 `replayLuaExecutor.deleteIdempotencyKey(...)` 직접 호출이 없다.
- `replayLuaExecutor.deleteIdempotencyKey(...)` 호출은 `afterCommit()` override 내부에만 있다.

- [x] **Step 2: DONE update count gating을 확인한다**

확인할 코드 형태:

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

```java
int updated = doneLogMapper.markRestoreDoneIfProcessing(log.getTrafficDeductDoneId(), workerId);
if (updated == 1) {
    registerIdempotencyCleanupAfterCommit(command.getIdempotencyKey());
}
```

Expected:
- update count가 `1`일 때만 cleanup callback을 등록한다.
- update count가 `0`인 ownership 상실 또는 상태 변경 경쟁 상황에서는 Redis key 삭제를 예약하지 않는다.

---

### Task 2: 회귀 테스트 존재 여부 확인

**Files:**
- Inspect only: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayServiceTest.java`
- Inspect only: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayServiceTest.java`

- [x] **Step 1: Phase 1 afterCommit 테스트를 확인한다**

Run:

```bash
rg -n "afterCommit|deleteIdempotencyKey|DONE 전환 commit 이후|ownership을 잃으면" src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayServiceTest.java
```

Expected:
- APPLIED 성공 경로에서 `afterCommit()` 전 `never()` 검증이 있다.
- SKIPPED 경로에서도 `afterCommit()` 전 `never()` 검증이 있다.
- update count `0` 경로에서 synchronization이 비어 있고 삭제하지 않는 검증이 있다.

- [x] **Step 2: Phase 2 afterCommit 테스트를 확인한다**

Run:

```bash
rg -n "afterCommit|deleteIdempotencyKey|commit 이후|DONE 전환 대상이 없으면" src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayServiceTest.java
```

Expected:
- APPLIED 성공 경로에서 `afterCommit()` 전 `never()` 검증이 있다.
- `afterCommit()` 실행 후 `deleteIdempotencyKey(...)` 호출 검증이 있다.
- update count `0` 경로에서 synchronization이 비어 있고 삭제하지 않는 검증이 있다.

---

### Task 3: 검증 실행

**Files:**
- Verify only: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayServiceTest.java`
- Verify only: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayServiceTest.java`

- [x] **Step 1: 관련 단위 테스트를 실행한다**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestorePhase1ReplayServiceTest --tests com.pooli.traffic.service.restore.TrafficRestorePhase2ReplayServiceTest
```

Expected:
- `BUILD SUCCESSFUL`
- `TrafficRestorePhase1ReplayServiceTest`와 `TrafficRestorePhase2ReplayServiceTest`가 통과한다.

- [x] **Step 2: 실패 시에만 최소 수정으로 되돌린다**

현재 코드가 아래 형태에서 벗어나 테스트가 실패하면, 해당 service만 최소 수정한다.

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

수정 기준:
- `replay(...)` 본문의 즉시 삭제 호출은 제거한다.
- DONE update count가 `1`일 때만 helper를 호출한다.
- 새 class, 새 interface, 새 dependency, schema 변경은 추가하지 않는다.

Expected:
- 현재 워크트리에서는 이 수정 단계가 필요하지 않다.

---

### Task 4: 자체 검토 및 보고

**Files:**
- Inspect only: changed files, if any

- [x] **Step 1: 자체 검토를 수행한다**

검토 항목:
- 제보된 Phase 2 finding이 현재 코드에서 재현되는가?
- Phase 1에도 같은 즉시 삭제 패턴이 남아 있는가?
- Redis key 삭제가 `afterCommit()` 밖에서 실행되는 경로가 있는가?
- update count가 `0`일 때 cleanup callback이 등록되는가?
- 테스트 편의를 위해 production design을 왜곡한 변경이 있는가?
- 요구 범위를 넘어 schema, mapper, ownership 구조를 변경했는가?

Expected:
- 현재 코드 기준으로 Phase 1/2 finding은 모두 이미 해소되어 있다.
- 필요한 소스 변경은 없다.
- 회귀 테스트는 존재하고 통과한다.

- [x] **Step 2: 결과를 보고한다**

보고 내용:
- 현재 코드 검토 결과
- 수정이 필요 없었던 이유
- 실행한 검증 명령과 결과
- 변경한 파일이 있다면 목록, 없으면 소스 변경 없음
- 남은 위험: 이번 검토는 replay idempotency key 삭제 시점에 한정하며, Phase 2 worker ownership schema 확장은 범위 밖이라는 점

---

## 승인 후 실행 방식

이 계획은 현재 코드 검토와 검증을 위한 계획서다. 사용자가 실행을 지시하면 `superpowers:executing-plans`로 Task 1부터 진행한다. 현재 검토 결과가 유지되면 production code와 test code는 수정하지 않고, 검증 결과만 보고한다. 코드 변경이 필요해지는 경우에도 사용자 명시 확인 전에는 commit하지 않는다.

## Dependency / Context7 기록

- `version`: Spring Boot 3.5.10, Spring Framework 6.2.15, MyBatis core 3.5.19 / starter 3.0.5, Spring Data Redis 3.5.8 + Lettuce 6.6.0.RELEASE, JUnit Jupiter 5.12.2, Mockito 5.17.0
- `source`: `docs/context7-dependencies.yaml`
- `context7_library_id`: `/spring-projects/spring-framework`
- `context7_reason`: `TransactionSynchronizationManager.registerSynchronization(...)`와 `TransactionSynchronization.afterCommit()` 기반 post-commit callback 사용 방식을 확인하기 위해 1회 사용했다.
