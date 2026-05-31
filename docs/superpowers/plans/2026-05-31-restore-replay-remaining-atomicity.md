# restore replay remaining atomicity 구현 계획

> **agentic worker 필수 지침:** 이 계획을 구현할 때는 `superpowers:executing-plans`를 사용한다. 변경 범위가 단일 Lua 스크립트와 단일 contract test에 한정되므로 subagent는 기본 선택이 아니다. 각 단계는 checkbox(`- [ ]`)로 진행 상태를 추적한다.

**목표:** `restore_usage_replay.lua`에서 individual/shared remaining 검증 실패 시 부분 차감이 남지 않도록 검증과 적용을 분리한다.

**아키텍처:** replay Lua는 idempotency key가 없을 때 먼저 입력값과 remaining 상태를 모두 검증하고, individual/shared의 다음 `amount` 값을 계산만 한다. 두 remaining 검증이 모두 성공한 뒤에만 `HSET`으로 차감을 적용하고, 이후 usage 누적과 expire/idempotency 처리를 기존 순서대로 수행한다.

**기술 스택:** Redis Lua, Spring Data Redis 3.5.8, Lettuce 6.6.0.RELEASE, JUnit Jupiter 5.12.2, AssertJ.

**규칙 기준:** `docs/context7-dependencies.yaml`, `docs/junit-unit-test-guide.md`, 저장소 `AGENTS.md`.

---

## 파일 구조

- Modify: `src/test/java/com/pooli/traffic/service/runtime/TrafficRestoreLuaContractTest.java`
  - replay Lua가 shared 검증 실패 전에 individual remaining을 변경하지 않는다는 contract test를 추가한다.
  - 외부 Redis를 띄우지 않고 현재 테스트 파일의 문자열 기반 contract 검증 방식을 유지한다.
- Modify: `src/main/resources/lua/traffic/restore_usage_replay.lua`
  - `apply_remaining_delta`를 검증/계산 전용 함수로 바꾸고, 함수 내부 `HSET`을 제거한다.
  - individual/shared 검증이 모두 성공한 뒤 computed next amount만 일괄 `HSET`한다.

새 class, 새 dependency, 새 설정은 추가하지 않는다. 변경은 결함이 발생한 Lua와 이를 고정하는 contract test에만 제한한다.

## Task 1: 실패 contract test 추가

**Files:**
- Modify: `src/test/java/com/pooli/traffic/service/runtime/TrafficRestoreLuaContractTest.java`

- [x] **Step 1: Lua 문자열 순서 검증 helper를 추가한다**

`TrafficRestoreLuaContractTest` class 내부 마지막 `}` 바로 앞에 아래 private helper를 추가한다.

```java
    private void assertAppearsBefore(String source, String earlier, String later) {
        int earlierIndex = source.indexOf(earlier);
        int laterIndex = source.indexOf(later);

        assertThat(earlierIndex)
                .as("앞에 있어야 하는 Lua 조각: %s", earlier)
                .isGreaterThanOrEqualTo(0);
        assertThat(laterIndex)
                .as("뒤에 있어야 하는 Lua 조각: %s", later)
                .isGreaterThanOrEqualTo(0);
        assertThat(earlierIndex)
                .as("Lua 조각 순서가 보장되어야 한다")
                .isLessThan(laterIndex);
    }
```

- [x] **Step 2: 부분 차감 방지 실패 테스트를 추가한다**

`TrafficRestoreLuaContractTest`의 `restoreReplayCreatesSharedUsageHashOnlyForPositiveSharedUsage` 테스트 다음에 아래 테스트를 추가한다.

```java
    @Test
    @DisplayName("restore replay Lua는 모든 remaining 검증 성공 후에만 잔량을 변경한다")
    void restoreReplayMutatesRemainingOnlyAfterAllRemainingValidationPasses() throws IOException {
        String lua = Files.readString(Path.of("src/main/resources/lua/traffic/restore_usage_replay.lua"));

        assertAppearsBefore(lua,
                "shared_error = resolve_remaining_delta",
                "redis.call('HSET', KEYS[2], 'amount'");
        assertAppearsBefore(lua,
                "if shared_error ~= nil then",
                "redis.call('HSET', KEYS[2], 'amount'");
        assertAppearsBefore(lua,
                "return { 'ERROR', shared_error }",
                "redis.call('HSET', KEYS[2], 'amount'");
        assertThat(lua).doesNotContain("redis.call('HSET', key, 'amount'");
    }
```

이 테스트는 현재 코드에서 실패해야 한다. 현재 `apply_remaining_delta` 함수 내부에 `redis.call('HSET', key, 'amount', tostring(next_amount))`가 있고, shared 검증 전에 individual 함수 호출이 먼저 실행되기 때문이다.

- [x] **Step 3: 실패를 확인한다**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.runtime.TrafficRestoreLuaContractTest
```

Expected:

```text
TrafficRestoreLuaContractTest > restore replay Lua는 모든 remaining 검증 성공 후에만 잔량을 변경한다 FAILED
```

구체 실패 원인은 `redis.call('HSET', KEYS[2], 'amount'`가 아직 없거나, 함수 내부 `redis.call('HSET', key, 'amount'`가 남아 있다는 assertion이어야 한다.

## Task 2: replay Lua 검증과 적용 분리

**Files:**
- Modify: `src/main/resources/lua/traffic/restore_usage_replay.lua`

- [x] **Step 1: `apply_remaining_delta`를 계산 전용 함수로 교체한다**

`restore_usage_replay.lua`의 기존 `apply_remaining_delta` 함수와 individual/shared 호출 블록을 아래 코드로 교체한다. 위치는 현재 line 37부터 line 69까지의 블록이다.

```lua
-- remaining 상태를 검증하고 적용할 다음 값을 계산한다.
local function resolve_remaining_delta(key, usage)
    if usage <= 0 then
        return nil, nil
    end

    local amount = tonumber(redis.call('HGET', key, 'amount'))
    if amount == nil then
        return nil, 'MISSING_REMAINING'
    end
    if amount == -1 then
        return nil, nil
    end
    if amount < -1 then
        return nil, 'INVALID_REMAINING'
    end

    local next_amount = amount - usage
    if next_amount < 0 then
        return nil, 'NEGATIVE_REMAINING'
    end
    return next_amount, nil
end

local individual_next, individual_error = resolve_remaining_delta(KEYS[2], individual_usage)
if individual_error ~= nil then
    return { 'ERROR', individual_error }
end

local shared_next, shared_error = resolve_remaining_delta(KEYS[3], shared_usage)
if shared_error ~= nil then
    return { 'ERROR', shared_error }
end

-- 두 remaining 검증이 모두 성공한 뒤에만 실제 차감을 적용한다.
if individual_next ~= nil then
    redis.call('HSET', KEYS[2], 'amount', tostring(individual_next))
end
if shared_next ~= nil then
    redis.call('HSET', KEYS[3], 'amount', tostring(shared_next))
end
```

이 변경은 다음 동작을 유지한다.

- `usage <= 0`: remaining hash를 읽거나 쓰지 않는다.
- `amount == -1`: 무제한 remaining이므로 쓰지 않는다.
- `amount == nil`: `MISSING_REMAINING`으로 반환한다.
- `amount < -1`: `INVALID_REMAINING`으로 반환한다.
- `amount - usage < 0`: `NEGATIVE_REMAINING`으로 반환한다.

이 변경은 다음 동작을 새로 보장한다.

- shared remaining 오류가 발생하면 individual remaining에도 `HSET`이 수행되지 않는다.
- 모든 ERROR 반환은 remaining `HSET`보다 먼저 발생한다.
- idempotency key `SET`은 기존처럼 성공 경로 마지막에서만 수행된다.

- [x] **Step 2: contract test를 통과시킨다**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.runtime.TrafficRestoreLuaContractTest
```

Expected:

```text
BUILD SUCCESSFUL
```

## Task 3: 관련 restore 테스트 회귀 확인

**Files:**
- Test only

- [x] **Step 1: restore replay executor 단위 테스트를 실행한다**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreReplayLuaExecutorTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [x] **Step 2: restore batch acceptance 테스트를 실행한다**

Run:

```bash
./gradlew test --tests com.pooli.traffic.acceptance.TrafficRestoreBatchAcceptanceTest
```

Expected:

```text
BUILD SUCCESSFUL
```

이 테스트가 환경 의존성 때문에 실패하면, 실패한 외부 의존성 또는 profile 조건을 기록하고 Task 1, Task 2의 focused test 결과를 최소 검증으로 보고한다.

결과: 기본 `test` 태스크에서는 `local-only` 태그 제외 때문에 테스트를 찾지 못했다. 실제 설정에 맞춰 `./gradlew local-only --tests com.pooli.traffic.acceptance.TrafficRestoreBatchAcceptanceTest`와 실패 메서드 단독 실행을 수행했으며, `preflight_key_existence` list script 미등록으로 stream record가 DLQ 처리되어 실패했다. 변경 파일인 `restore_usage_replay.lua`와 직접 관련 없는 기존 local-only 시나리오 문제로 기록한다.

- [x] **Step 3: 전체 테스트를 실행한다**

Run:

```bash
./gradlew test
```

Expected:

```text
BUILD SUCCESSFUL
```

전체 테스트가 시간 또는 환경 문제로 완료되지 않으면 실패 지점과 원인을 보고하고, 적어도 아래 두 명령은 성공해야 한다.

```bash
./gradlew test --tests com.pooli.traffic.service.runtime.TrafficRestoreLuaContractTest
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreReplayLuaExecutorTest
```

결과: `./gradlew test`는 661개 중 `TrafficRestorePhase0HydrateServiceTest.throwsExceptionWhenTargetMonthStartIsNull` 1건이 실패했다. 실패 원인은 기대 메시지 `targetMonthStart must not be null for owner 10`과 실제 메시지 `temporal` 불일치이며, 변경 파일과 무관하다. 최소 검증 명령 두 개는 모두 성공했다.

## Task 4: 자체 검토와 보고

**Files:**
- Review only

- [x] **Step 1: 변경 범위를 확인한다**

Run:

```bash
git diff -- src/main/resources/lua/traffic/restore_usage_replay.lua src/test/java/com/pooli/traffic/service/runtime/TrafficRestoreLuaContractTest.java
```

Expected:

```text
diff --git a/src/main/resources/lua/traffic/restore_usage_replay.lua ...
diff --git a/src/test/java/com/pooli/traffic/service/runtime/TrafficRestoreLuaContractTest.java ...
```

확인 기준:

- 변경 파일은 Lua와 contract test 2개로 제한된다.
- 새 dependency, 새 설정, 새 production Java class가 없다.
- Lua 함수는 검증/계산만 수행하고, `HSET`은 shared error 반환 뒤에만 나타난다.
- 테스트 편의 때문에 production 설계를 왜곡한 변경이 없다.

- [x] **Step 2: 단순성 검토를 수행한다**

아래 질문에 모두 “예”로 답할 수 있어야 한다.

- 이 변경을 더 적은 파일로 구현할 수 없는가? 답: 현재 결함 고정에는 Lua 1개와 regression test 1개가 최소 범위다.
- 새 개념이나 새 abstraction을 추가하지 않았는가? 답: Redis Lua 내부 local function 이름 변경과 반환값 확장만 사용한다.
- 미래 요구를 위해 일반화하지 않았는가? 답: individual/shared 두 remaining 검증과 적용 순서만 다룬다.
- 관련 없는 코드를 정리하지 않았는가? 답: restore replay remaining 원자성 외 코드는 건드리지 않는다.
- 유지보수자가 추가 문서 없이 변경 의도를 이해할 수 있는가? 답: Lua 주석과 테스트 DisplayName이 결함 조건을 직접 설명한다.

- [x] **Step 3: 완료 보고를 작성한다**

보고에는 아래 항목을 포함한다.

- 변경 파일:
  - `src/main/resources/lua/traffic/restore_usage_replay.lua`
  - `src/test/java/com/pooli/traffic/service/runtime/TrafficRestoreLuaContractTest.java`
- 구현 내용:
  - remaining 검증/계산과 `HSET` 적용 분리
  - shared 검증 실패 전 individual 차감 방지 contract test 추가
- 검증 결과:
  - 실행한 Gradle test 명령과 성공/실패 결과
- 자체 검토:
  - 부분 적용 제거 확인
  - idempotency key 성공 경로 유지 확인
  - 변경 범위 최소화 확인
- 남은 위험:
  - 문자열 기반 contract test는 실제 Redis 실행 테스트보다 약하다. 다만 현재 파일의 기존 테스트 패턴과 빠른 단위 테스트 기준을 유지하기 위한 선택이다.

## 승인 경계

이 계획서는 아직 구현 승인이 아니다. 사용자가 “이 계획대로 진행하세요”, “구현하세요”, “승인합니다”처럼 명시적으로 승인한 뒤에만 production code와 test code를 수정한다.
