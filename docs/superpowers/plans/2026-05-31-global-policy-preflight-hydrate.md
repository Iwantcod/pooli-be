# 전역 정책 Preflight Hydrate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:executing-plans`를 사용해 이 계획을 task 단위로 실행한다. 고위험 변경으로 판단되거나 reviewer 분리가 필요하면 `superpowers:subagent-driven-development`를 선택한다. 각 단계는 checkbox(`- [ ]`)로 추적한다.

**Goal:** 차감 전 Redis key 존재 여부 확인 단계에서 전역 정책 key(1~7)도 함께 검사하고, 누락 시 기존 policy bootstrap lock을 이용해 전역 정책 snapshot hydrate를 선행한다.

**Architecture:** 기존 `preflight_key_existence.lua`를 확장해 line policy ready key, 개인/공유 잔량 key, 전역 policy key 1~7의 존재 여부를 한 번에 조회한다. `TrafficDeductOrchestratorService`는 전역 policy key 누락을 감지하면 실제 정책 검증 Lua와 차감 Lua 호출 전에 `TrafficPolicyBootstrapService`의 lock-first hydrate 메서드를 호출한다. 기존 `block_policy_check.lua`와 `deduct_unified.lua`의 `GLOBAL_POLICY_HYDRATE` fallback은 마지막 방어선으로 유지한다.

**Tech Stack:** Java 17, Spring Boot 3.5.10, Spring Data Redis 3.5.8 + Lettuce 6.6.0.RELEASE, MyBatis 3.5.19, JUnit Jupiter 5.12.2, Mockito 5.17.0, Redis Lua.

---

## 범위와 우선 규칙

- 이 계획은 production code 구현을 승인하지 않는다. 사용자가 이 계획을 명시적으로 승인한 뒤에만 구현한다.
- 코드 변경 커밋은 AGENTS.md §11.6에 따라 사용자 확인 전까지 수행하지 않는다.
- 새 dependency, 새 framework 기능, 새 추상화 계층은 추가하지 않는다.
- Context7는 이 계획 작성 단계에서 사용하지 않았다. 이유: 외부 API 시그니처가 아니라 repository 내부 설계와 호출 흐름을 정리하는 작업이다.
- 테스트 작성 시 `docs/junit-unit-test-guide.md`의 JUnit/Mockito 단위 테스트 원칙을 따른다.

## 변경 파일 구조

- Modify: `src/main/resources/lua/traffic/preflight_key_existence.lua`
  - 차감 preflight key 존재 여부 Lua를 고정 3개 key에서 전달된 전체 key 목록 검사로 확장한다.
- Modify: `src/main/java/com/pooli/traffic/service/runtime/TrafficLuaScriptInfraService.java`
  - `executePreflightKeyExistence(...)`가 전역 policy key 목록을 함께 받아 결과 크기를 검증하도록 변경한다.
- Modify: `src/main/java/com/pooli/traffic/service/decision/TrafficDeductOrchestratorService.java`
  - 전역 policy key 1~7을 preflight 조회 대상에 포함하고, 누락 시 전역 policy hydrate를 선행한다.
- Modify: `src/main/java/com/pooli/traffic/service/policy/TrafficPolicyBootstrapService.java`
  - preflight 전용 lock-first hydrate 메서드를 추가한다. 기존 `hydrateOnDemand()` 동작은 fallback 경로 호환을 위해 유지한다.
- Modify: `src/test/java/com/pooli/traffic/service/runtime/TrafficLuaPolicyContractTest.java`
  - preflight Lua가 전달된 모든 key를 `EXISTS`로 검사한다는 계약을 검증한다.
- Modify: `src/test/java/com/pooli/traffic/service/decision/TrafficDeductOrchestratorServiceTest.java`
  - 전역 policy key 누락 시 bootstrap hydrate가 정책 검증/차감 전에 호출되는지 검증한다.
- Modify: `src/test/java/com/pooli/traffic/service/policy/TrafficPolicyBootstrapServiceTest.java`
  - 새 lock-first hydrate 메서드의 lock 획득, 재확인, 스킵, release 동작을 검증한다.

---

### Task 1: Preflight Lua 계약 확장

**Files:**
- Modify: `src/main/resources/lua/traffic/preflight_key_existence.lua`
- Modify: `src/test/java/com/pooli/traffic/service/runtime/TrafficLuaPolicyContractTest.java`

- [x] **Step 1: 실패하는 Lua 계약 테스트 작성**

`TrafficLuaPolicyContractTest`에 preflight script 상수를 추가한다.

```java
private static final Path PREFLIGHT_KEY_EXISTENCE_SCRIPT =
        Path.of("src/main/resources/lua/traffic/preflight_key_existence.lua");
```

같은 테스트 클래스에 아래 테스트를 추가한다. `@DisplayName`은 한국어로 작성한다.

```java
@Test
@DisplayName("preflight Lua는 전달된 모든 key의 존재 여부를 순서대로 반환한다")
void preflightKeyExistenceChecksEveryProvidedKey() throws IOException {
    String script = Files.readString(PREFLIGHT_KEY_EXISTENCE_SCRIPT, StandardCharsets.UTF_8);

    assertTrue(script.contains("while idx <= #KEYS do"));
    assertTrue(script.contains("redis.call('EXISTS', KEYS[idx])"));
    assertFalse(script.contains("KEYS[3]"));
}
```

- [x] **Step 2: 테스트 실패 확인**

실행:

```bash
./gradlew test --tests "com.pooli.traffic.service.runtime.TrafficLuaPolicyContractTest.preflightKeyExistenceChecksEveryProvidedKey"
```

예상 결과: FAIL. 현재 Lua가 `KEYS[1]`, `KEYS[2]`, `KEYS[3]`만 고정 검사하므로 `while idx <= #KEYS do` 조건이 없다.

- [x] **Step 3: Lua를 최소 변경으로 확장**

`preflight_key_existence.lua` 전체를 아래 내용으로 교체한다.

```lua
-- 차감 preflight에서 필요한 Redis key 존재 여부를 한 번의 Lua 호출로 확인한다.
-- KEYS: 호출자가 전달한 preflight 대상 key 목록
-- 반환: KEYS 순서와 같은 1/0 존재 여부 목록

local result = {}
local idx = 1

while idx <= #KEYS do
    result[idx] = redis.call('EXISTS', KEYS[idx])
    idx = idx + 1
end

return result
```

- [x] **Step 4: Lua 계약 테스트 통과 확인**

실행:

```bash
./gradlew test --tests "com.pooli.traffic.service.runtime.TrafficLuaPolicyContractTest.preflightKeyExistenceChecksEveryProvidedKey"
```

예상 결과: PASS.

---

### Task 2: Java preflight Lua 호출 계약 확장

**Files:**
- Modify: `src/main/java/com/pooli/traffic/service/runtime/TrafficLuaScriptInfraService.java`
- Modify: `src/test/java/com/pooli/traffic/service/decision/TrafficDeductOrchestratorServiceTest.java`

- [x] **Step 1: orchestrator 테스트 mock 계약을 먼저 실패하도록 갱신**

`TrafficDeductOrchestratorServiceTest`의 import에 `java.util.stream.LongStream`은 추가하지 않는다. 테스트에서는 명시적 list를 사용해 정책 ID 순서를 드러낸다.

`setUp()`에 전역 policy key stub을 추가한다.

```java
lenient().when(trafficRedisKeyFactory.policyKey(1L)).thenReturn("pooli:policy:1");
lenient().when(trafficRedisKeyFactory.policyKey(2L)).thenReturn("pooli:policy:2");
lenient().when(trafficRedisKeyFactory.policyKey(3L)).thenReturn("pooli:policy:3");
lenient().when(trafficRedisKeyFactory.policyKey(4L)).thenReturn("pooli:policy:4");
lenient().when(trafficRedisKeyFactory.policyKey(5L)).thenReturn("pooli:policy:5");
lenient().when(trafficRedisKeyFactory.policyKey(6L)).thenReturn("pooli:policy:6");
lenient().when(trafficRedisKeyFactory.policyKey(7L)).thenReturn("pooli:policy:7");
```

기존 `executePreflightKeyExistence(...)` stub을 새 signature 기준으로 바꾼다.

```java
lenient().when(trafficLuaScriptInfraService.executePreflightKeyExistence(
        "pooli:line_policy_ready:11",
        "pooli:remaining_indiv_amount:11:202603",
        "pooli:remaining_shared_amount:22:202603",
        List.of(
                "pooli:policy:1",
                "pooli:policy:2",
                "pooli:policy:3",
                "pooli:policy:4",
                "pooli:policy:5",
                "pooli:policy:6",
                "pooli:policy:7"
        )
)).thenReturn(List.of(1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L));
```

- [x] **Step 2: 컴파일 실패 확인**

실행:

```bash
./gradlew test --tests "com.pooli.traffic.service.decision.TrafficDeductOrchestratorServiceTest"
```

예상 결과: FAIL. `TrafficLuaScriptInfraService.executePreflightKeyExistence(...)`가 아직 4번째 인자를 받지 않는다.

- [x] **Step 3: `TrafficLuaScriptInfraService` signature 확장**

`TrafficLuaScriptInfraService`에 `ArrayList` import를 추가한다.

```java
import java.util.ArrayList;
```

`executePreflightKeyExistence(...)`를 아래 형태로 변경한다.

```java
public List<Long> executePreflightKeyExistence(
        String linePolicyReadyKey,
        String individualBalanceKey,
        String sharedBalanceKey,
        List<String> globalPolicyKeys
) {
    List<String> keys = new ArrayList<>();
    keys.add(linePolicyReadyKey);
    keys.add(individualBalanceKey);
    keys.add(sharedBalanceKey);
    if (globalPolicyKeys != null) {
        keys.addAll(globalPolicyKeys);
    }

    List rawResult = executeListSingle(
            TrafficLuaScriptType.PREFLIGHT_KEY_EXISTENCE,
            keys,
            List.of()
    );
    if (rawResult.size() != keys.size()) {
        throw new ApplicationException(
                CommonErrorCode.INTERNAL_SERVER_ERROR,
                "Lua preflight key existence result size is invalid."
        );
    }
    return rawResult.stream()
            .map(this::toLongResult)
            .toList();
}
```

- [x] **Step 4: 컴파일 범위 테스트 통과 확인**

실행:

```bash
./gradlew test --tests "com.pooli.traffic.service.decision.TrafficDeductOrchestratorServiceTest"
```

예상 결과: 현재는 orchestrator production code가 아직 3개 인자 호출을 사용하므로 컴파일 실패가 날 수 있다. 실패하면 Task 3로 진행한다.

---

### Task 3: Orchestrator에 전역 정책 preflight hydrate 연결

**Files:**
- Modify: `src/main/java/com/pooli/traffic/service/decision/TrafficDeductOrchestratorService.java`
- Modify: `src/test/java/com/pooli/traffic/service/decision/TrafficDeductOrchestratorServiceTest.java`

- [x] **Step 1: 실패하는 orchestrator 테스트 작성**

`TrafficDeductOrchestratorServiceTest`에 mock을 추가한다.

```java
@Mock
private TrafficPolicyBootstrapService trafficPolicyBootstrapService;
```

import를 추가한다.

```java
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
```

아래 테스트를 추가한다.

```java
@Test
@DisplayName("preflight에서 전역 정책 key가 누락되면 정책 검증 전에 전역 정책 hydrate를 시도한다")
void hydratesGlobalPolicyBeforePolicyCheckWhenPreflightDetectsMissingPolicyKey() {
    TrafficPayloadReqDto payload = payload(100L);
    List<String> globalPolicyKeys = List.of(
            "pooli:policy:1",
            "pooli:policy:2",
            "pooli:policy:3",
            "pooli:policy:4",
            "pooli:policy:5",
            "pooli:policy:6",
            "pooli:policy:7"
    );
    TrafficLuaDeductExecutionResult initialResult = unifiedResult(100L, 0L, 0L, TrafficLuaStatus.OK);

    when(trafficLuaScriptInfraService.executePreflightKeyExistence(
            "pooli:line_policy_ready:11",
            "pooli:remaining_indiv_amount:11:202603",
            "pooli:remaining_shared_amount:22:202603",
            globalPolicyKeys
    )).thenReturn(List.of(1L, 1L, 1L, 1L, 0L, 1L, 1L, 1L, 1L, 1L));
    when(trafficDeductLuaExecutor.executeUnifiedWithRetry(
            eq(payload),
            eq(100L),
            any(TrafficDeductExecutionContext.class),
            eq(TrafficFailureStage.DEDUCT)
    )).thenReturn(initialResult);
    when(trafficHydrateService.recoverIfNeeded(
            eq(payload),
            eq(100L),
            any(TrafficDeductExecutionContext.class),
            eq(initialResult)
    )).thenReturn(initialResult);

    service.orchestrate(payload);

    InOrder inOrder = inOrder(trafficPolicyBootstrapService, trafficPolicyCheckLayerService, trafficDeductLuaExecutor);
    inOrder.verify(trafficPolicyBootstrapService).hydrateOnDemandIfAnyPolicyKeyMissing(globalPolicyKeys);
    inOrder.verify(trafficPolicyCheckLayerService).evaluate(payload);
    inOrder.verify(trafficDeductLuaExecutor).executeUnifiedWithRetry(
            eq(payload),
            eq(100L),
            any(TrafficDeductExecutionContext.class),
            eq(TrafficFailureStage.DEDUCT)
    );
}
```

- [x] **Step 2: 테스트 실패 확인**

실행:

```bash
./gradlew test --tests "com.pooli.traffic.service.decision.TrafficDeductOrchestratorServiceTest.hydratesGlobalPolicyBeforePolicyCheckWhenPreflightDetectsMissingPolicyKey"
```

예상 결과: FAIL. `TrafficDeductOrchestratorService`에 `TrafficPolicyBootstrapService` 의존성과 호출 로직이 아직 없다.

- [x] **Step 3: orchestrator에 최소 구현 추가**

`TrafficDeductOrchestratorService` import를 추가한다.

```java
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
```

필드에 dependency를 추가한다.

```java
private static final List<Long> GLOBAL_POLICY_IDS = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L);
```

```java
private final TrafficPolicyBootstrapService trafficPolicyBootstrapService;
```

`ensurePreflightHydrated(...)`에서 전역 policy key를 생성하고 Lua 호출에 포함한다.

```java
List<String> globalPolicyKeys = globalPolicyKeys();
List<Long> keyExistenceResult = trafficLuaScriptInfraService.executePreflightKeyExistence(
        linePolicyReadyKey,
        individualBalanceKey,
        sharedBalanceKey,
        globalPolicyKeys
);
```

line/balance hydrate 호출 앞에 전역 policy 누락 처리를 추가한다.

```java
if (!allGlobalPolicyKeysExist(keyExistenceResult, globalPolicyKeys.size())) {
    trafficPolicyBootstrapService.hydrateOnDemandIfAnyPolicyKeyMissing(globalPolicyKeys);
}
```

클래스 하단 private helper를 추가한다.

```java
private List<String> globalPolicyKeys() {
    return GLOBAL_POLICY_IDS.stream()
            .map(trafficRedisKeyFactory::policyKey)
            .toList();
}

private boolean allGlobalPolicyKeysExist(List<Long> keyExistenceResult, int globalPolicyKeyCount) {
    int firstGlobalPolicyIndex = 3;
    for (int index = 0; index < globalPolicyKeyCount; index++) {
        if (!existsAt(keyExistenceResult, firstGlobalPolicyIndex + index)) {
            return false;
        }
    }
    return true;
}
```

- [x] **Step 4: orchestrator 테스트 통과 확인**

실행:

```bash
./gradlew test --tests "com.pooli.traffic.service.decision.TrafficDeductOrchestratorServiceTest"
```

예상 결과: Task 4의 production method가 준비되면 PASS. 현재 단계에서 `hydrateOnDemandIfAnyPolicyKeyMissing(...)` 미정의로 컴파일 실패하면 Task 4로 진행한다.

---

### Task 4: Policy bootstrap lock-first hydrate 메서드 추가

**Files:**
- Modify: `src/main/java/com/pooli/traffic/service/policy/TrafficPolicyBootstrapService.java`
- Modify: `src/test/java/com/pooli/traffic/service/policy/TrafficPolicyBootstrapServiceTest.java`

- [x] **Step 1: 실패하는 단위 테스트 작성**

`HydrateOnDemandTest` 내부에 아래 테스트를 추가한다.

```java
@Test
@DisplayName("preflight 대상 정책 key가 모두 존재하면 DB 조회와 lock 획득을 수행하지 않음")
void skipsPreflightHydrateWhenAllPolicyKeysExist() {
    List<String> policyKeys = List.of("pooli:policy:1", "pooli:policy:2");
    when(cacheStringRedisTemplate.hasKey("pooli:policy:1")).thenReturn(true);
    when(cacheStringRedisTemplate.hasKey("pooli:policy:2")).thenReturn(true);

    trafficPolicyBootstrapService.hydrateOnDemandIfAnyPolicyKeyMissing(policyKeys);

    verify(policyBackOfficeMapper, never()).selectPolicyActivationSnapshot();
    verify(cacheStringRedisTemplate, never()).opsForValue();
}
```

같은 nested class에 아래 테스트도 추가한다.

```java
@Test
@DisplayName("preflight 대상 정책 key 누락 시 lock 획득 후 재확인하고 snapshot을 hydrate함")
void hydratesAfterLockAndRecheckWhenPolicyKeyStillMissing() {
    String lockKey = "pooli:policy:bootstrap:lock";
    List<String> policyKeys = List.of("pooli:policy:1", "pooli:policy:2");
    when(cacheStringRedisTemplate.hasKey("pooli:policy:1")).thenReturn(true);
    when(cacheStringRedisTemplate.hasKey("pooli:policy:2")).thenReturn(false, false);
    when(trafficRedisKeyFactory.policyBootstrapLockKey()).thenReturn(lockKey);
    when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(
            eq(lockKey),
            anyString(),
            eq(Duration.ofMillis(30_000L))
    )).thenReturn(true);
    when(policyBackOfficeMapper.selectPolicyActivationSnapshot()).thenReturn(allPolicySnapshots());
    when(trafficRedisKeyFactory.policyBootstrapVersionKey()).thenReturn("pooli:policy_bootstrap_version");
    when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
    when(cacheStringRedisTemplate.executePipelined(any(org.springframework.data.redis.core.SessionCallback.class)))
            .thenReturn(List.of());
    when(trafficLuaScriptInfraService.executeLockRelease(eq(lockKey), anyString()))
            .thenReturn(true);

    trafficPolicyBootstrapService.hydrateOnDemandIfAnyPolicyKeyMissing(policyKeys);

    verify(policyBackOfficeMapper, times(1)).selectPolicyActivationSnapshot();
    verify(cacheStringRedisTemplate, times(1))
            .executePipelined(any(org.springframework.data.redis.core.SessionCallback.class));
    verify(trafficLuaScriptInfraService, times(1))
            .executeLockRelease(eq(lockKey), anyString());
}
```

lock 획득 실패 테스트도 추가한다.

```java
@Test
@DisplayName("preflight hydrate lock을 얻지 못하면 DB 조회 없이 종료")
void skipsPreflightHydrateWhenLockNotAcquired() {
    String lockKey = "pooli:policy:bootstrap:lock";
    List<String> policyKeys = List.of("pooli:policy:1", "pooli:policy:2");
    when(cacheStringRedisTemplate.hasKey("pooli:policy:1")).thenReturn(false);
    when(trafficRedisKeyFactory.policyBootstrapLockKey()).thenReturn(lockKey);
    when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(
            eq(lockKey),
            anyString(),
            eq(Duration.ofMillis(30_000L))
    )).thenReturn(false);

    trafficPolicyBootstrapService.hydrateOnDemandIfAnyPolicyKeyMissing(policyKeys);

    verify(policyBackOfficeMapper, never()).selectPolicyActivationSnapshot();
    verify(cacheStringRedisTemplate, never())
            .executePipelined(any(org.springframework.data.redis.core.SessionCallback.class));
    verify(trafficLuaScriptInfraService, never()).executeLockRelease(anyString(), anyString());
}
```

- [x] **Step 2: 테스트 실패 확인**

실행:

```bash
./gradlew test --tests "com.pooli.traffic.service.policy.TrafficPolicyBootstrapServiceTest"
```

예상 결과: FAIL. `hydrateOnDemandIfAnyPolicyKeyMissing(...)`가 아직 없다.

- [x] **Step 3: lock-first hydrate 메서드 구현**

`TrafficPolicyBootstrapService`에 import를 추가한다.

```java
import java.util.Collection;
```

public method를 추가한다.

```java
/**
 * 차감 preflight에서 전역 policy key 누락을 감지했을 때 lock-first 방식으로 snapshot hydrate를 시도합니다.
 *
 * <p>lock 획득 전후로 key 존재 여부를 재확인하여 정상 hot-path에서 DB 조회를 피하고,
 * 다른 worker가 이미 hydrate한 경우 중복 snapshot 조회와 pipeline 반영을 생략합니다.
 *
 * @param policyKeys preflight에서 확인한 전역 policy Redis key 목록
 */
public void hydrateOnDemandIfAnyPolicyKeyMissing(Collection<String> policyKeys) {
    if (allPolicyKeysExist(policyKeys)) {
        return;
    }

    String lockKey = trafficRedisKeyFactory.policyBootstrapLockKey();
    String lockOwner = buildLockOwner("on_demand_preflight");
    boolean lockAcquired = tryAcquireLock(lockKey, lockOwner);
    if (!lockAcquired) {
        log.info(
                "traffic_policy_bootstrap_preflight_lock_skipped lockKey={}",
                lockKey
        );
        return;
    }

    try {
        if (allPolicyKeysExist(policyKeys)) {
            return;
        }
        synchronizePolicyActivationSnapshotWithoutLock("on_demand_preflight", false);
    } finally {
        releaseLock(lockKey, lockOwner);
    }
}
```

private helper를 추가한다.

```java
private boolean allPolicyKeysExist(Collection<String> policyKeys) {
    if (policyKeys == null || policyKeys.isEmpty()) {
        return false;
    }
    for (String policyKey : policyKeys) {
        if (policyKey == null || policyKey.isBlank()) {
            return false;
        }
        if (!Boolean.TRUE.equals(cacheStringRedisTemplate.hasKey(policyKey))) {
            return false;
        }
    }
    return true;
}
```

기존 `synchronizePolicyActivationSnapshot(...)`에서 DB 조회/검증/pipeline 부분을 helper로 분리한다. 기존 method는 lock 획득 구조를 유지한다.

```java
private void synchronizePolicyActivationSnapshotWithoutLock(
        String executionType,
        boolean failFastOnMissingRequiredIds
) {
    List<PolicyActivationSnapshotResDto> snapshots = policyBackOfficeMapper.selectPolicyActivationSnapshot();
    if (!validateRequiredPolicyIds(snapshots, failFastOnMissingRequiredIds)) {
        return;
    }

    syncSnapshotToRedis(snapshots);
    log.info(
            "traffic_policy_bootstrap_completed executionType={} policyCount={}",
            executionType,
            snapshots.size()
    );
}
```

기존 `synchronizePolicyActivationSnapshot(...)`의 lock 획득 후 try block은 아래처럼 바꾼다.

```java
try {
    synchronizePolicyActivationSnapshotWithoutLock(executionType, failFastOnMissingRequiredIds);
} finally {
    releaseLock(lockKey, lockOwner);
}
```

- [x] **Step 4: policy bootstrap 테스트 통과 확인**

실행:

```bash
./gradlew test --tests "com.pooli.traffic.service.policy.TrafficPolicyBootstrapServiceTest"
```

예상 결과: PASS.

---

### Task 5: 기존 preflight 테스트와 누락 분기 정리

**Files:**
- Modify: `src/test/java/com/pooli/traffic/service/decision/TrafficDeductOrchestratorServiceTest.java`

- [x] **Step 1: 기존 preflight 관련 테스트 mock 결과 크기 보정**

`TrafficDeductOrchestratorServiceTest`에서 `executePreflightKeyExistence(...)`를 stub하는 모든 위치를 찾아 결과를 10개 값으로 맞춘다.

기본 성공 결과는 아래 값을 사용한다.

```java
List.of(1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L)
```

개인 잔량 key 누락 테스트에서는 두 번째 index만 `0L`로 둔다.

```java
List.of(1L, 0L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L)
```

공유 잔량 key 누락 테스트에서는 세 번째 index만 `0L`로 둔다.

```java
List.of(1L, 1L, 0L, 1L, 1L, 1L, 1L, 1L, 1L, 1L)
```

- [x] **Step 2: line policy ready 누락과 전역 policy 누락이 독립적으로 동작하는지 검증 추가**

아래 테스트를 추가한다.

```java
@Test
@DisplayName("line policy ready와 전역 정책 key 누락은 각각의 hydrate 경로를 호출한다")
void hydratesLinePolicyAndGlobalPolicyIndependentlyWhenBothMissing() {
    TrafficPayloadReqDto payload = payload(100L);
    List<String> globalPolicyKeys = List.of(
            "pooli:policy:1",
            "pooli:policy:2",
            "pooli:policy:3",
            "pooli:policy:4",
            "pooli:policy:5",
            "pooli:policy:6",
            "pooli:policy:7"
    );
    TrafficLuaDeductExecutionResult initialResult = unifiedResult(100L, 0L, 0L, TrafficLuaStatus.OK);

    when(trafficLuaScriptInfraService.executePreflightKeyExistence(
            "pooli:line_policy_ready:11",
            "pooli:remaining_indiv_amount:11:202603",
            "pooli:remaining_shared_amount:22:202603",
            globalPolicyKeys
    )).thenReturn(List.of(0L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 0L, 1L));
    when(trafficDeductLuaExecutor.executeUnifiedWithRetry(
            eq(payload),
            eq(100L),
            any(TrafficDeductExecutionContext.class),
            eq(TrafficFailureStage.DEDUCT)
    )).thenReturn(initialResult);
    when(trafficHydrateService.recoverIfNeeded(
            eq(payload),
            eq(100L),
            any(TrafficDeductExecutionContext.class),
            eq(initialResult)
    )).thenReturn(initialResult);

    service.orchestrate(payload);

    verify(trafficLinePolicyHydrationService).ensureLoaded(11L);
    verify(trafficPolicyBootstrapService).hydrateOnDemandIfAnyPolicyKeyMissing(globalPolicyKeys);
}
```

- [x] **Step 3: orchestrator 테스트 전체 통과 확인**

실행:

```bash
./gradlew test --tests "com.pooli.traffic.service.decision.TrafficDeductOrchestratorServiceTest"
```

예상 결과: PASS.

---

### Task 6: 통합 검증과 자기검토

**Files:**
- 이전 task 외 직접 수정 파일 없음.

- [x] **Step 1: 관련 단위 테스트 실행**

실행:

```bash
./gradlew test --tests "com.pooli.traffic.service.runtime.TrafficLuaPolicyContractTest" --tests "com.pooli.traffic.service.decision.TrafficDeductOrchestratorServiceTest" --tests "com.pooli.traffic.service.policy.TrafficPolicyBootstrapServiceTest"
```

예상 결과: PASS.

- [x] **Step 2: 전체 테스트 실행**

실행:

```bash
./gradlew test
```

예상 결과: PASS.

- [x] **Step 3: self-review 체크**

아래 질문에 모두 “아니오/문제 없음”으로 답할 수 있어야 한다.

- 전역 policy hydrate를 위해 새 lock key를 만들었는가? 새 lock key를 만들었다면 기존 `policyBootstrapLockKey()` 재사용으로 되돌린다.
- `TrafficPolicyBootstrapService.hydrateOnDemand()` fallback 동작을 불필요하게 바꿨는가? 바꿨다면 기존 fallback 호출자의 의미를 다시 검토한다.
- preflight에서 전역 policy key 누락을 처리하면서 개인/공유 잔량 hydrate lock 경로를 변경했는가? 변경했다면 원래 범위 밖이므로 되돌린다.
- 테스트 편의를 위해 production visibility, setter, test-only constructor를 추가했는가? 추가했다면 제거한다.
- `preflight_key_existence.lua` 결과 index를 잘못 해석해 line/balance/global policy 순서가 섞였는가? `0=linePolicyReady`, `1=individualBalance`, `2=sharedBalance`, `3..9=globalPolicy` 순서를 유지한다.

- [x] **Step 4: 변경 파일 확인**

실행:

```bash
git diff -- src/main/resources/lua/traffic/preflight_key_existence.lua src/main/java/com/pooli/traffic/service/runtime/TrafficLuaScriptInfraService.java src/main/java/com/pooli/traffic/service/decision/TrafficDeductOrchestratorService.java src/main/java/com/pooli/traffic/service/policy/TrafficPolicyBootstrapService.java src/test/java/com/pooli/traffic/service/runtime/TrafficLuaPolicyContractTest.java src/test/java/com/pooli/traffic/service/decision/TrafficDeductOrchestratorServiceTest.java src/test/java/com/pooli/traffic/service/policy/TrafficPolicyBootstrapServiceTest.java
```

예상 결과: diff가 이 계획의 범위 파일에만 존재한다.

- [x] **Step 5: 커밋 대기**

AGENTS.md §11.6에 따라 코드 변경 커밋은 사용자 확인 전까지 수행하지 않는다. 구현 완료 보고에는 “코드베이스 커밋은 사용자 확인 대기”를 명시한다.

---

## 승인 후 실행 방식

이 계획은 아직 승인되지 않았다. 사용자가 “이 계획대로 진행하세요”, “구현하세요”, “승인합니다”처럼 명시적으로 승인하면 실행을 시작한다.

권장 실행 방식은 `superpowers:executing-plans`이다. 변경 범위는 Redis/Lua/정책 hydrate를 포함하지만 한 흐름 안의 cohesive multi-file change이며, 기존 fallback 방어선을 유지하므로 inline 실행이 적절하다. 실행 중 동시성/데이터 일관성 리스크가 커지면 `superpowers:subagent-driven-development`로 전환한다.
