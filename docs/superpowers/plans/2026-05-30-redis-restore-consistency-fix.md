# Redis Restore Consistency Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` for implementation because this plan touches Redis Lua, Redis data type contracts, MyBatis verification SQL, restore replay idempotency, and traffic deduction consistency. If subagents cannot be used in the current environment, use `superpowers:executing-plans` and execute task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redis 장애 복구가 개인풀 hash의 모든 필드를 온전히 복구하고, 존재하지 않는 가족풀 사용량 정보를 Redis key로 만들지 않으며, 복구 후 기존 트래픽 차감이 같은 Redis 계약 위에서 정상 동작하게 한다.

**Architecture:** 정상 `deduct_unified.lua`와 배치 reader가 사용하는 Redis 계약을 source of truth로 삼는다. Restore replay Lua와 verification/correction 경로를 이 계약에 맞춰 정렬하고, `shared_usage <= 0`인 가족풀 사용량 key는 replay와 검증 보정 모두에서 생성하지 않으며 0으로 초기화하지도 않는다. 개인풀 잔량 hash는 `amount`와 `qos`를 한 세트로 검증하며, `daily_total_usage`는 hash가 아니라 string counter로 복구한다.

**Tech Stack:** Spring Boot 3.5.10, Spring Data Redis 3.5.8, Lettuce 6.6.0.RELEASE, MyBatis core 3.5.19 / starter 3.0.5, Spring JDBC 6.2.15, JUnit Jupiter 5.12.2, Mockito 5.17.0, Redis Lua

---

## 실행 상태

- 현재 상태: 구현 미승인, 계획서 작성만 완료
- 이관 출처: `docs/superpowers/plans/2026-05-29-redis-restore-batch.md`의 초기 복구 배치 구현 완료 후 확인된 정합성 결함
- 종결 조건: 이 계획이 구현되고 fresh verification을 통과해야 Redis 복구 기능을 최종 종결 처리한다.
- 계획 승인 조건: 사용자가 "이 계획대로 진행", "구현하세요", "승인합니다"처럼 이 문서를 명시적으로 승인해야 한다.
- 생산 코드 변경 금지: 승인 전에는 `src/main/**`, `src/test/**`, `src/main/resources/lua/**`, `src/main/resources/mapper/**`를 수정하지 않는다.
- codebase 변경 commit 금지: 코드/테스트/리소스 변경 commit은 사용자 명시 확인 전 금지한다.
- 문서 변경 commit: 사용자가 별도 요청하지 않았으므로 이 계획 문서도 commit하지 않는다.

## 의존성 및 문서 기준

- `docs/context7-dependencies.yaml` 확인 기준:
  - Redis: Spring Data Redis 3.5.8 + Lettuce 6.6.0.RELEASE
  - MyBatis: core 3.5.19 / starter 3.0.5
  - JUnit Jupiter: 5.12.2
  - Mockito: 5.17.0
- `docs/junit-unit-test-guide.md` 확인 기준:
  - 단위 테스트는 외부 리소스에 의존하지 않는 string contract 또는 Mockito 기반 테스트를 우선 작성한다.
  - Redis 실제 동작 검증은 acceptance test로 제한한다.
- Context7 사용 여부: `not_used`
  - 이유: 현재 계획은 저장소 내부 Redis key/type 계약 정렬이며 exact external API signature 확인이 아니다.
  - 구현 중 Spring Data Redis 또는 MyBatis exact API signature가 필요해지면 `docs/context7-dependencies.yaml`의 primary library id 기준으로 Context7를 1 dependency당 1회 원칙으로 사용한다.

## 정상 Redis 계약

| Redis key | 정상 자료형 | 정상 field/value | 기준 파일 |
|---|---|---|---|
| `remaining_indiv_amount:{lineId}:{yyyyMM}` | hash | `amount`, `qos` | `hydrate_individual_snapshot.lua`, `deduct_unified.lua` |
| `remaining_shared_amount:{familyId}:{yyyyMM}` | hash | `amount` | `hydrate_shared_snapshot.lua`, `deduct_unified.lua` |
| `daily_total_usage:{lineId}:{yyyyMMdd}` | string | 전체 사용량 counter | `deduct_unified.lua`, `LineDailyUsageRedisReader` |
| `daily_app_usage:{lineId}:{yyyyMMdd}` | hash | `app:{appId}:individual`, `app:{appId}:shared`, `app:{appId}:qos` 중 양수 source만 | `deduct_unified.lua`, `LineDailyUsageRedisReader` |
| `daily_shared_usage:{lineId}:{yyyyMMdd}` | hash | 공유 사용량이 양수일 때만 `usage_amount`, `family_id`; 공유 사용량이 0이면 key 미생성 | `deduct_unified.lua`, `LineDailyUsageRedisReader` |
| `monthly_shared_usage:{lineId}:{yyyyMM}` | hash | 공유 사용량이 양수일 때만 `usage_amount`, `family_id`; 공유 사용량이 0이면 key 미생성 | `deduct_unified.lua`, `DataServiceImpl` |

## 파일 책임 구조

- Modify: `src/main/resources/lua/traffic/restore_usage_replay.lua`
  - restore replay를 정상 차감 Redis 계약과 동일하게 만든다.
  - `daily_total_usage`는 string `INCRBY`로 복구한다.
  - `daily_shared_usage`와 `monthly_shared_usage`는 `shared_usage > 0`일 때만 `usage_amount`, `family_id`를 기록한다.
  - `shared_usage <= 0`이면 `HINCRBY ... 0`도 실행하지 않아 가족풀 사용량 hash 자체를 만들지 않는다.
- Modify: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreReplayLuaExecutor.java`
  - replay Lua에 `familyId`를 인자로 전달한다.
  - `sharedUsageBytes > 0`이고 `familyId`가 없으면 Lua가 실패하도록 빈 값 대신 `0`을 전달한다.
- Modify: `src/main/resources/lua/traffic/restore_usage_correction.lua`
  - string counter 보정과 hash field 보정을 모두 지원한다.
  - 기존 key 자료형이 목표 자료형과 다르면 `DEL` 후 목표 자료형으로 다시 쓴다.
- Modify: `src/main/java/com/pooli/traffic/service/runtime/TrafficLuaScriptInfraService.java`
  - correction Lua 호출 인자에 value kind를 추가한다.
- Modify: `src/main/java/com/pooli/traffic/domain/restore/TrafficRestoreVerificationKeyType.java`
  - `DAILY_TOTAL_USAGE` 주석을 string counter 기준으로 바로잡는다.
- Modify: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreVerificationService.java`
  - `DAILY_TOTAL_USAGE`는 `opsForValue().get`으로 읽고 string correction mode로 보정한다.
  - hash 대상은 기존처럼 hash field를 읽되 correction mode를 hash로 전달한다.
- Modify: `src/main/resources/mapper/traffic/TrafficRestoreVerificationMapper.xml`
  - 개인풀 `qos` 기준값을 `amount`와 함께 산출한다.
  - `daily_total_usage` 기준값은 개인+공유+QoS 합계 단일 string value로 산출한다.
  - 앱별 사용량 field는 source별 합계가 양수인 경우만 산출한다.
  - 일별/월별 가족풀 사용량은 공유 사용량 합계가 양수인 경우만 `usage_amount`, `family_id` 기준값을 산출한다.
  - 공유 사용량 합계가 0이면 검증 target을 만들지 않아 correction 경로도 가족풀 사용량 hash를 생성하지 않는다.
- Modify: `src/test/java/com/pooli/traffic/service/runtime/TrafficRestoreLuaContractTest.java`
  - Lua contract string tests를 정상 Redis 계약 기준으로 갱신한다.
- Modify: `src/test/java/com/pooli/traffic/service/restore/TrafficRestoreReplayLuaExecutorTest.java`
  - replay Lua 인자에 `familyId`가 포함되는지 검증한다.
- Modify: `src/test/java/com/pooli/traffic/service/restore/TrafficRestoreVerificationServiceTest.java`
  - string counter 보정, hash 보정, 개인풀 `qos` 검증을 단위 테스트한다.
- Modify: `src/test/java/com/pooli/traffic/acceptance/TrafficRestoreBatchAcceptanceTest.java`
  - 복구 후 Redis 자료형과 field가 정상 차감 계약과 일치하는지 검증한다.
  - 공유 사용량 0인 경우 가족풀 사용량 key가 생성되지 않음을 검증한다.
  - 복구 후 실제 트래픽 차감 1회를 수행해 정합성을 검증한다.

## Task 0: 실행 전 격리 workspace 확인

**Files:**
- No code change

- [ ] **Step 1: `superpowers:using-git-worktrees` 사용**

승인 후 구현 시작 전에 `superpowers:using-git-worktrees`를 사용한다.

Expected:
- 현재 작업이 `main` 또는 `master`에서 직접 수행되지 않는다.
- 새 worktree 또는 안전한 feature branch에서 구현한다.

- [ ] **Step 2: 작업 전 상태 확인**

Run:

```bash
git status --short
```

Expected:

```text
docs/superpowers/plans/2026-05-30-redis-restore-consistency-fix.md
```

또는 사용자가 만든 unrelated 변경이 함께 보일 수 있다. unrelated 변경은 되돌리지 않는다.

---

## Task 1: Redis restore Lua contract 실패 테스트 작성

**Files:**
- Modify: `src/test/java/com/pooli/traffic/service/runtime/TrafficRestoreLuaContractTest.java`

- [ ] **Step 1: 실패 테스트 추가**

`TrafficRestoreLuaContractTest`에 아래 테스트를 추가한다.

```java
@Test
@DisplayName("restore replay Lua는 daily total usage를 string counter로 복구한다")
void restoreReplayUsesStringCounterForDailyTotalUsage() throws IOException {
    String lua = Files.readString(Path.of("src/main/resources/lua/traffic/restore_usage_replay.lua"));

    assertThat(lua).contains("redis.call('INCRBY', KEYS[4], total_usage)");
    assertThat(lua).doesNotContain("redis.call('HINCRBY', KEYS[4], 'individual'");
    assertThat(lua).doesNotContain("redis.call('HINCRBY', KEYS[4], 'shared'");
    assertThat(lua).doesNotContain("redis.call('HINCRBY', KEYS[4], 'qos'");
}

@Test
@DisplayName("restore replay Lua는 공유 사용량이 양수일 때만 가족풀 사용량 hash를 생성한다")
void restoreReplayCreatesSharedUsageHashOnlyForPositiveSharedUsage() throws IOException {
    String lua = Files.readString(Path.of("src/main/resources/lua/traffic/restore_usage_replay.lua"));

    assertThat(lua).contains("if shared_usage > 0 then");
    assertThat(lua).contains("redis.call('HINCRBY', KEYS[6], 'usage_amount', shared_usage)");
    assertThat(lua).contains("redis.call('HSET', KEYS[6], 'family_id', family_id)");
    assertThat(lua).contains("redis.call('HINCRBY', KEYS[7], 'usage_amount', shared_usage)");
    assertThat(lua).contains("redis.call('HSET', KEYS[7], 'family_id', family_id)");
}

@Test
@DisplayName("restore correction Lua는 string value와 hash field 보정을 구분한다")
void restoreCorrectionSupportsStringAndHashCorrection() throws IOException {
    String lua = Files.readString(Path.of("src/main/resources/lua/traffic/restore_usage_correction.lua"));

    assertThat(lua).contains("local value_kind = ARGV[1]");
    assertThat(lua).contains("if value_kind == 'string' then");
    assertThat(lua).contains("redis.call('SET', KEYS[1], ARGV[3])");
    assertThat(lua).contains("if value_kind == 'hash' then");
    assertThat(lua).contains("redis.call('HSET', KEYS[1], ARGV[2], ARGV[3])");
}
```

- [ ] **Step 2: 실패 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.runtime.TrafficRestoreLuaContractTest
```

Expected:
- `restoreReplayUsesStringCounterForDailyTotalUsage` 실패
- `restoreReplayCreatesSharedUsageHashOnlyForPositiveSharedUsage` 실패
- `restoreCorrectionSupportsStringAndHashCorrection` 실패

---

## Task 2: restore replay Lua를 정상 차감 Redis 계약으로 수정

**Files:**
- Modify: `src/main/resources/lua/traffic/restore_usage_replay.lua`
- Modify: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreReplayLuaExecutor.java`
- Test: `src/test/java/com/pooli/traffic/service/restore/TrafficRestoreReplayLuaExecutorTest.java`
- Test: `src/test/java/com/pooli/traffic/service/runtime/TrafficRestoreLuaContractTest.java`

- [ ] **Step 1: executor 인자 검증 테스트 추가**

`TrafficRestoreReplayLuaExecutorTest`에 아래 테스트를 추가한다.

```java
@Test
@DisplayName("replay executor는 Lua에 familyId를 여섯 번째 인자로 전달한다")
void passesFamilyIdToReplayLua() {
    String suffix = "p2:done_log:9001";
    when(trafficRedisKeyFactory.restoreIdempotencyKeyFromSuffix(suffix))
            .thenReturn("pooli:restore:idempotency:p2:done_log:9001");
    when(trafficRedisKeyFactory.remainingIndivAmountKey(100L, YearMonth.of(2026, 5)))
            .thenReturn("pooli:remaining_indiv_amount:100:202605");
    when(trafficRedisKeyFactory.remainingSharedAmountKey(200L, YearMonth.of(2026, 5)))
            .thenReturn("pooli:remaining_shared_amount:200:202605");
    when(trafficRedisKeyFactory.dailyTotalUsageKey(100L, LocalDate.of(2026, 5, 27)))
            .thenReturn("pooli:daily_total_usage:100:20260527");
    when(trafficRedisKeyFactory.dailyAppUsageKey(100L, LocalDate.of(2026, 5, 27)))
            .thenReturn("pooli:daily_app_usage:100:20260527");
    when(trafficRedisKeyFactory.dailySharedUsageKey(100L, LocalDate.of(2026, 5, 27)))
            .thenReturn("pooli:daily_shared_usage:100:20260527");
    when(trafficRedisKeyFactory.monthlySharedUsageKey(100L, YearMonth.of(2026, 5)))
            .thenReturn("pooli:monthly_shared_usage:100:202605");
    when(trafficLuaScriptInfraService.executeRestoreUsageReplay(anyList(), anyList()))
            .thenReturn(List.of("APPLIED"));
    TrafficRestoreReplayCommand command = TrafficRestoreReplayCommand.builder()
            .idempotencyKey(suffix)
            .usageDate(LocalDate.of(2026, 5, 27))
            .lineId(100L)
            .familyId(200L)
            .applicationId(20)
            .individualUsageBytes(1L)
            .sharedUsageBytes(2L)
            .qosUsageBytes(3L)
            .expireEpochSeconds(0L)
            .build();

    executor.replay(command);

    ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
    verify(trafficLuaScriptInfraService).executeRestoreUsageReplay(anyList(), argsCaptor.capture());
    assertThat(argsCaptor.getValue()).containsExactly("20", "1", "2", "3", "0", "200");
}
```

- [ ] **Step 2: 실패 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreReplayLuaExecutorTest
```

Expected:
- 새 테스트가 `"200"` 인자 누락으로 실패한다.

- [ ] **Step 3: `TrafficRestoreReplayLuaExecutor.buildArgs` 수정**

`buildArgs`를 아래 형태로 변경한다.

```java
private List<String> buildArgs(TrafficRestoreReplayCommand command) {
    return List.of(
            String.valueOf(command.getApplicationId()),
            String.valueOf(nullToZero(command.getIndividualUsageBytes())),
            String.valueOf(nullToZero(command.getSharedUsageBytes())),
            String.valueOf(nullToZero(command.getQosUsageBytes())),
            String.valueOf(nullToZero(command.getExpireEpochSeconds())),
            String.valueOf(nullToZero(command.getFamilyId()))
    );
}
```

- [ ] **Step 4: `restore_usage_replay.lua` 수정**

`restore_usage_replay.lua`를 아래 계약으로 수정한다.

```lua
-- Redis 장애 복구 replay Lua.
-- KEYS[1]: idempotency key
-- KEYS[2]: individual remaining hash key
-- KEYS[3]: shared remaining hash key
-- KEYS[4]: daily total usage string key
-- KEYS[5]: daily app usage hash key
-- KEYS[6]: daily shared usage hash key
-- KEYS[7]: monthly shared usage hash key
-- ARGV[1]: application id
-- ARGV[2]: individual usage bytes
-- ARGV[3]: shared usage bytes
-- ARGV[4]: qos usage bytes
-- ARGV[5]: expire epoch seconds
-- ARGV[6]: family id

local idempotency_key = KEYS[1]

if redis.call('EXISTS', idempotency_key) == 1 then
    return { 'SKIPPED' }
end

local app_id = ARGV[1]
local individual_usage = tonumber(ARGV[2]) or 0
local shared_usage = tonumber(ARGV[3]) or 0
local qos_usage = tonumber(ARGV[4]) or 0
local expire_at = tonumber(ARGV[5]) or 0
local family_id = tonumber(ARGV[6]) or 0
local total_usage = individual_usage + shared_usage + qos_usage

if individual_usage < 0 or shared_usage < 0 or qos_usage < 0 then
    return { 'ERROR', 'NEGATIVE_USAGE' }
end
if shared_usage > 0 and family_id <= 0 then
    return { 'ERROR', 'MISSING_FAMILY_ID' }
end

local function apply_remaining_delta(key, usage)
    if usage <= 0 then
        return nil
    end

    local amount = tonumber(redis.call('HGET', key, 'amount'))
    if amount == nil then
        return 'MISSING_REMAINING'
    end
    if amount == -1 then
        return nil
    end
    if amount < -1 then
        return 'INVALID_REMAINING'
    end

    local next_amount = amount - usage
    if next_amount < 0 then
        return 'NEGATIVE_REMAINING'
    end
    redis.call('HSET', key, 'amount', tostring(next_amount))
    return nil
end

local individual_error = apply_remaining_delta(KEYS[2], individual_usage)
if individual_error ~= nil then
    return { 'ERROR', individual_error }
end

local shared_error = apply_remaining_delta(KEYS[3], shared_usage)
if shared_error ~= nil then
    return { 'ERROR', shared_error }
end

if total_usage > 0 then
    redis.call('INCRBY', KEYS[4], total_usage)
end
if individual_usage > 0 then
    redis.call('HINCRBY', KEYS[5], 'app:' .. app_id .. ':individual', individual_usage)
end
if shared_usage > 0 then
    redis.call('HINCRBY', KEYS[5], 'app:' .. app_id .. ':shared', shared_usage)
    redis.call('HINCRBY', KEYS[6], 'usage_amount', shared_usage)
    redis.call('HSET', KEYS[6], 'family_id', family_id)
    redis.call('HINCRBY', KEYS[7], 'usage_amount', shared_usage)
    redis.call('HSET', KEYS[7], 'family_id', family_id)
end
if qos_usage > 0 then
    redis.call('HINCRBY', KEYS[5], 'app:' .. app_id .. ':qos', qos_usage)
end

if expire_at > 0 then
    redis.call('EXPIREAT', KEYS[2], expire_at)
    redis.call('EXPIREAT', KEYS[3], expire_at)
    if total_usage > 0 then
        redis.call('EXPIREAT', KEYS[4], expire_at)
    end
    if individual_usage > 0 or shared_usage > 0 or qos_usage > 0 then
        redis.call('EXPIREAT', KEYS[5], expire_at)
    end
    if shared_usage > 0 then
        redis.call('EXPIREAT', KEYS[6], expire_at)
        redis.call('EXPIREAT', KEYS[7], expire_at)
    end
end

redis.call('SET', idempotency_key, '1')
return { 'APPLIED' }
```

- [ ] **Step 5: replay 관련 테스트 통과 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.runtime.TrafficRestoreLuaContractTest --tests com.pooli.traffic.service.restore.TrafficRestoreReplayLuaExecutorTest
```

Expected:
- `BUILD SUCCESSFUL`

---

## Task 3: restore verification SQL을 정상 Redis 계약 기준으로 수정

**Files:**
- Modify: `src/main/resources/mapper/traffic/TrafficRestoreVerificationMapper.xml`
- Modify: `src/main/java/com/pooli/traffic/domain/restore/TrafficRestoreVerificationKeyType.java`
- Test: `src/test/java/com/pooli/traffic/mapper/TrafficRestoreVerificationMapperSqlContractTest.java`

- [ ] **Step 1: mapper SQL contract test 생성**

`src/test/java/com/pooli/traffic/mapper/TrafficRestoreVerificationMapperSqlContractTest.java`를 생성한다.

```java
package com.pooli.traffic.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrafficRestoreVerificationMapperSqlContractTest {

    @Test
    @DisplayName("복구 검증 SQL은 daily total usage를 단일 string counter 기준값으로 산출한다")
    void selectsDailyTotalUsageAsSingleStringCounterTarget() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/mapper/traffic/TrafficRestoreVerificationMapper.xml"));

        assertThat(sql).contains("'DAILY_TOTAL_USAGE' AS key_type");
        assertThat(sql).contains("'__value__' AS field_name");
        assertThat(sql).contains("SUM(individual_usage + shared_usage + qos_usage) AS expected_value");
        assertThat(sql).doesNotContain("'individual' AS field_name,\n            SUM(individual_usage)");
        assertThat(sql).doesNotContain("'shared',\n            SUM(shared_usage)");
        assertThat(sql).doesNotContain("'qos',\n            SUM(qos_usage)");
    }

    @Test
    @DisplayName("복구 검증 SQL은 개인풀 amount와 qos를 함께 산출한다")
    void selectsIndividualAmountAndQosTogether() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/mapper/traffic/TrafficRestoreVerificationMapper.xml"));

        assertThat(sql).contains("'amount' AS field_name");
        assertThat(sql).contains("'qos'");
        assertThat(sql).contains("CASE WHEN p.qos_speed_limit IS NULL OR p.qos_speed_limit < 0 THEN 0 ELSE p.qos_speed_limit * 125 END");
    }

    @Test
    @DisplayName("복구 검증 SQL은 공유 사용량이 양수일 때만 가족풀 사용량 target을 산출한다")
    void selectsSharedUsageTargetsOnlyWhenSharedUsageIsPositive() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/mapper/traffic/TrafficRestoreVerificationMapper.xml"));

        assertThat(sql).contains("HAVING SUM(shared_usage) > 0");
        assertThat(sql).contains("'usage_amount'");
        assertThat(sql).contains("'family_id'");
        assertThat(sql).doesNotContain("'DAILY_SHARED_USAGE',\n            line_id,\n            NULL");
        assertThat(sql).doesNotContain("'MONTHLY_SHARED_USAGE',\n            line_id,\n            NULL");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.mapper.TrafficRestoreVerificationMapperSqlContractTest
```

Expected:
- 새 테스트가 기존 SQL 계약 불일치로 실패한다.

- [ ] **Step 3: `TrafficRestoreVerificationKeyType` 주석 수정**

`DAILY_TOTAL_USAGE` 주석을 아래처럼 수정한다.

```java
/** 회선 일별 전체 사용량 string counter key이다. */
DAILY_TOTAL_USAGE,
```

- [ ] **Step 4: `selectUsageVerificationTargets` 수정**

`selectUsageVerificationTargets`의 `usage_source`에 `family_id`를 포함하고, `DAILY_TOTAL_USAGE`와 공유 사용량 target을 아래 구조로 바꾼다.

```xml
WITH usage_source AS (
    SELECT
        d.usage_date AS usage_date,
        d.line_id AS line_id,
        fl.family_id AS family_id,
        d.application_id AS application_id,
        d.individual_usage_data AS individual_usage,
        d.shared_usage_data AS shared_usage,
        d.qos_usage_data AS qos_usage
    FROM DAILY_APP_TOTAL_DATA d
    LEFT JOIN FAMILY_LINE fl
      ON fl.line_id = d.line_id
    WHERE d.usage_date = #{usageDate}
      AND d.line_id &gt;= #{lineIdStartInclusive}
      AND d.line_id &lt;= #{lineIdEndInclusive}
      AND d.deleted_at IS NULL
    UNION ALL
    SELECT
        DATE(t.enqueued_at) AS usage_date,
        t.line_id AS line_id,
        t.family_id AS family_id,
        t.app_id AS application_id,
        t.deducted_individual_bytes AS individual_usage,
        t.deducted_shared_bytes AS shared_usage,
        t.deducted_qos_bytes AS qos_usage
    FROM TRAFFIC_DEDUCT_DONE t
    WHERE t.enqueued_at &gt;= #{dayStartInclusive}
      AND t.enqueued_at &lt; #{dayEndExclusive}
      AND t.line_id &gt;= #{lineIdStartInclusive}
      AND t.line_id &lt;= #{lineIdEndInclusive}
)
SELECT
    'DAILY_TOTAL_USAGE' AS key_type,
    line_id AS line_id,
    NULL AS family_id,
    NULL AS policy_id,
    usage_date AS usage_date,
    NULL AS month_start,
    NULL AS application_id,
    '__value__' AS field_name,
    SUM(individual_usage + shared_usage + qos_usage) AS expected_value,
    0 AS expire_epoch_seconds
FROM usage_source
GROUP BY line_id, usage_date
HAVING SUM(individual_usage + shared_usage + qos_usage) > 0
UNION ALL
SELECT
    'DAILY_APP_USAGE',
    line_id,
    NULL,
    NULL,
    usage_date,
    NULL,
    application_id,
    CONCAT('app:', application_id, ':individual'),
    SUM(individual_usage),
    0
FROM usage_source
GROUP BY line_id, usage_date, application_id
HAVING SUM(individual_usage) > 0
UNION ALL
SELECT
    'DAILY_APP_USAGE',
    line_id,
    NULL,
    NULL,
    usage_date,
    NULL,
    application_id,
    CONCAT('app:', application_id, ':shared'),
    SUM(shared_usage),
    0
FROM usage_source
GROUP BY line_id, usage_date, application_id
HAVING SUM(shared_usage) > 0
UNION ALL
SELECT
    'DAILY_APP_USAGE',
    line_id,
    NULL,
    NULL,
    usage_date,
    NULL,
    application_id,
    CONCAT('app:', application_id, ':qos'),
    SUM(qos_usage),
    0
FROM usage_source
GROUP BY line_id, usage_date, application_id
HAVING SUM(qos_usage) > 0
UNION ALL
SELECT
    'DAILY_SHARED_USAGE',
    line_id,
    NULL,
    NULL,
    usage_date,
    NULL,
    NULL,
    'usage_amount',
    SUM(shared_usage),
    0
FROM usage_source
WHERE family_id IS NOT NULL
GROUP BY line_id, usage_date
HAVING SUM(shared_usage) > 0
UNION ALL
SELECT
    'DAILY_SHARED_USAGE',
    line_id,
    NULL,
    NULL,
    usage_date,
    NULL,
    NULL,
    'family_id',
    MAX(family_id),
    0
FROM usage_source
WHERE family_id IS NOT NULL
GROUP BY line_id, usage_date
HAVING SUM(shared_usage) > 0
```

- [ ] **Step 5: `selectRemainingVerificationTargets` 수정**

개인풀 `qos`, 월별 공유 사용량 `usage_amount`, `family_id` target을 포함하도록 `selectRemainingVerificationTargets`를 수정한다.

```xml
SELECT
    'REMAINING_INDIVIDUAL' AS key_type,
    u.line_id AS line_id,
    NULL AS family_id,
    NULL AS policy_id,
    NULL AS usage_date,
    u.month_start AS month_start,
    NULL AS application_id,
    'amount' AS field_name,
    l.total_data - u.individual_usage AS expected_value,
    0 AS expire_epoch_seconds
FROM line_month_usage u
JOIN LINE l
  ON l.line_id = u.line_id
 AND l.deleted_at IS NULL
UNION ALL
SELECT
    'REMAINING_INDIVIDUAL',
    u.line_id,
    NULL,
    NULL,
    NULL,
    u.month_start,
    NULL,
    'qos',
    CASE WHEN p.qos_speed_limit IS NULL OR p.qos_speed_limit < 0 THEN 0 ELSE p.qos_speed_limit * 125 END,
    0
FROM line_month_usage u
JOIN LINE l
  ON l.line_id = u.line_id
 AND l.deleted_at IS NULL
JOIN PLAN p
  ON p.plan_id = l.plan_id
 AND p.deleted_at IS NULL
UNION ALL
SELECT
    'REMAINING_SHARED',
    NULL,
    u.family_id,
    NULL,
    NULL,
    u.month_start,
    NULL,
    'amount',
    f.pool_total_data - u.shared_usage,
    0
FROM family_month_usage u
JOIN FAMILY f
  ON f.family_id = u.family_id
 AND f.deleted_at IS NULL
UNION ALL
SELECT
    'MONTHLY_SHARED_USAGE',
    line_id,
    NULL,
    NULL,
    NULL,
    CAST(DATE_FORMAT(usage_date, '%Y-%m-01') AS DATE),
    NULL,
    'usage_amount',
    SUM(shared_usage),
    0
FROM usage_source
WHERE family_id IS NOT NULL
GROUP BY line_id, CAST(DATE_FORMAT(usage_date, '%Y-%m-01') AS DATE)
HAVING SUM(shared_usage) > 0
UNION ALL
SELECT
    'MONTHLY_SHARED_USAGE',
    line_id,
    NULL,
    NULL,
    NULL,
    CAST(DATE_FORMAT(usage_date, '%Y-%m-01') AS DATE),
    NULL,
    'family_id',
    MAX(family_id),
    0
FROM usage_source
WHERE family_id IS NOT NULL
GROUP BY line_id, CAST(DATE_FORMAT(usage_date, '%Y-%m-01') AS DATE)
HAVING SUM(shared_usage) > 0
```

- [ ] **Step 6: SQL contract 테스트 통과 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.mapper.TrafficRestoreVerificationMapperSqlContractTest
```

Expected:
- `BUILD SUCCESSFUL`

---

## Task 4: restore correction을 string/hash 자료형 구분 보정으로 수정

**Files:**
- Modify: `src/main/resources/lua/traffic/restore_usage_correction.lua`
- Modify: `src/main/java/com/pooli/traffic/service/runtime/TrafficLuaScriptInfraService.java`
- Modify: `src/main/java/com/pooli/traffic/service/restore/TrafficRestoreVerificationService.java`
- Test: `src/test/java/com/pooli/traffic/service/restore/TrafficRestoreVerificationServiceTest.java`
- Test: `src/test/java/com/pooli/traffic/service/runtime/TrafficRestoreLuaContractTest.java`

- [ ] **Step 1: verification service 실패 테스트 추가**

`TrafficRestoreVerificationServiceTest`에 아래 테스트를 추가한다.

```java
@Test
@DisplayName("daily total usage는 string counter로 읽고 string mode로 보정한다")
void correctsDailyTotalUsageAsStringCounter() {
    LocalDate anchorDate = LocalDate.of(2026, 5, 29);
    RestoreRange restoreRange = new RestoreRange(LocalDate.of(2026, 5, 27), LocalDate.of(2026, 5, 28));
    TrafficRestoreVerificationTarget target = TrafficRestoreVerificationTarget.builder()
            .keyType(TrafficRestoreVerificationKeyType.DAILY_TOTAL_USAGE)
            .lineId(10L)
            .usageDate(LocalDate.of(2026, 5, 27))
            .field("__value__")
            .expectedValue(125L)
            .expireEpochSeconds(0L)
            .build();
    when(verificationMapper.selectVerificationLineRange(
            restoreRange.startInclusive(),
            restoreRange.endExclusive(),
            restoreRange.startDateTimeInclusive(),
            restoreRange.endDateTimeExclusive()
    )).thenReturn(TrafficRestoreVerificationLineRange.of(10L, 10L));
    when(verificationMapper.selectRemainingVerificationTargets(
            restoreRange.startInclusive(),
            restoreRange.endExclusive(),
            restoreRange.startDateTimeInclusive(),
            restoreRange.endDateTimeExclusive(),
            10L,
            10L
    )).thenReturn(List.of());
    when(verificationMapper.selectUsageVerificationTargets(
            LocalDate.of(2026, 5, 27),
            LocalDate.of(2026, 5, 27).atStartOfDay(),
            LocalDate.of(2026, 5, 28).atStartOfDay(),
            10L,
            10L
    )).thenReturn(List.of(target));
    when(verificationMapper.selectPolicyVerificationTargets()).thenReturn(List.of());
    when(trafficRedisKeyFactory.dailyTotalUsageKey(10L, LocalDate.of(2026, 5, 27)))
            .thenReturn("pooli:daily_total_usage:10:20260527");
    when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("pooli:daily_total_usage:10:20260527")).thenReturn("10");
    when(trafficLuaScriptInfraService.executeRestoreUsageCorrection(
            "pooli:daily_total_usage:10:20260527",
            "string",
            "__value__",
            125L,
            0L
    )).thenReturn(List.of("CORRECTED"));
    when(idempotencyCleanupService.cleanupRestoreIdempotencyKeys()).thenReturn(0L);

    RestoreVerificationResult result = service.verifyAndCorrect(anchorDate, restoreRange);

    assertThat(result.correctedCount()).isEqualTo(1L);
    verify(trafficLuaScriptInfraService).executeRestoreUsageCorrection(
            "pooli:daily_total_usage:10:20260527",
            "string",
            "__value__",
            125L,
            0L
    );
}
```

이 테스트를 위해 `TrafficRestoreVerificationServiceTest`에 다음 mock 필드를 추가한다.

```java
@Mock
private org.springframework.data.redis.core.ValueOperations<String, String> valueOperations;
```

- [ ] **Step 2: 실패 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestoreVerificationServiceTest
```

Expected:
- 새 테스트가 `opsForValue()` 미사용 또는 `executeRestoreUsageCorrection` signature 불일치로 실패한다.

- [ ] **Step 3: correction Lua 수정**

`restore_usage_correction.lua`를 아래 내용으로 교체한다.

```lua
-- Redis 장애 복구 검증 후 보정 Lua.
-- KEYS[1]: 보정할 Redis key
-- ARGV[1]: value kind ('string' 또는 'hash')
-- ARGV[2]: hash field 또는 string sentinel
-- ARGV[3]: 기준값
-- ARGV[4]: expire epoch seconds

local value_kind = ARGV[1]
local field = ARGV[2]
local expected_value = ARGV[3]
local expire_at = tonumber(ARGV[4]) or 0

if value_kind == 'string' then
    local key_type = redis.call('TYPE', KEYS[1]).ok
    if key_type ~= 'none' and key_type ~= 'string' then
        redis.call('DEL', KEYS[1])
    end
    redis.call('SET', KEYS[1], expected_value)
elseif value_kind == 'hash' then
    if not field or field == '' or field == '__value__' then
        return { 'ERROR', 'INVALID_HASH_FIELD' }
    end
    local key_type = redis.call('TYPE', KEYS[1]).ok
    if key_type ~= 'none' and key_type ~= 'hash' then
        redis.call('DEL', KEYS[1])
    end
    redis.call('HSET', KEYS[1], field, expected_value)
else
    return { 'ERROR', 'INVALID_VALUE_KIND' }
end

if expire_at > 0 then
    redis.call('EXPIREAT', KEYS[1], expire_at)
end

return { 'CORRECTED' }
```

- [ ] **Step 4: `TrafficLuaScriptInfraService.executeRestoreUsageCorrection` signature 수정**

기존 method를 아래 signature와 구현으로 수정한다.

```java
public List<String> executeRestoreUsageCorrection(
        String key,
        String valueKind,
        String field,
        long expectedValue,
        long expireEpochSeconds
) {
    List rawResult = executeListSingle(
            TrafficLuaScriptType.RESTORE_USAGE_CORRECTION,
            List.of(key),
            List.of(valueKind, field, String.valueOf(expectedValue), String.valueOf(expireEpochSeconds))
    );
    return rawResult.stream()
            .map(String::valueOf)
            .toList();
}
```

- [ ] **Step 5: `TrafficRestoreVerificationService` 읽기/보정 분기 수정**

`verifyTargets`, `correct`, value 읽기 메서드를 아래 구조로 수정한다.

```java
private void verifyTargets(
        List<TrafficRestoreVerificationTarget> targets,
        LocalDate anchorDate,
        VerificationCounters counters
) {
    for (TrafficRestoreVerificationTarget target : targets) {
        String key = resolveRedisKey(target);
        String field = target.getField();
        long expectedValue = nullToZero(target.getExpectedValue());
        String valueKind = resolveValueKind(target);
        long actualValue = readRedisLong(key, field, valueKind);
        if (actualValue == expectedValue) {
            counters.matchedCount++;
            continue;
        }

        log.warn(
                "traffic_restore_verification_mismatch anchorDate={} key={} field={} expected={} actual={}",
                anchorDate,
                key,
                field,
                expectedValue,
                actualValue
        );
        if (correct(key, valueKind, field, expectedValue, nullToZero(target.getExpireEpochSeconds()))) {
            counters.correctedCount++;
        } else {
            counters.failedCorrectionCount++;
        }
    }
}

private String resolveValueKind(TrafficRestoreVerificationTarget target) {
    return target.getKeyType() == TrafficRestoreVerificationKeyType.DAILY_TOTAL_USAGE ? "string" : "hash";
}

private long readRedisLong(String key, String field, String valueKind) {
    if ("string".equals(valueKind)) {
        return readStringLong(key);
    }
    return readHashLong(key, field);
}

private boolean correct(String key, String valueKind, String field, long expectedValue, long expireEpochSeconds) {
    try {
        List<String> result = trafficLuaScriptInfraService.executeRestoreUsageCorrection(
                key,
                valueKind,
                field,
                expectedValue,
                expireEpochSeconds
        );
        return !result.isEmpty() && "CORRECTED".equals(result.get(0));
    } catch (RuntimeException e) {
        log.error("traffic_restore_correction_failed key={} field={}", key, field, e);
        return false;
    }
}

private long readStringLong(String key) {
    String rawValue = cacheStringRedisTemplate.opsForValue().get(key);
    if (rawValue == null) {
        return 0L;
    }
    return Long.parseLong(rawValue);
}
```

기존 `readHashLong`은 유지한다.

- [ ] **Step 6: compile 오류 수정**

기존 테스트의 `executeRestoreUsageCorrection` stubbing과 verify 호출을 새 signature로 변경한다.

예시:

```java
when(trafficLuaScriptInfraService.executeRestoreUsageCorrection(
        "pooli:daily_app_usage:10:20260527",
        "hash",
        "app:20:individual",
        100L,
        0L
)).thenReturn(List.of("CORRECTED"));
```

- [ ] **Step 7: correction/verification 단위 테스트 통과 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.runtime.TrafficRestoreLuaContractTest --tests com.pooli.traffic.service.restore.TrafficRestoreVerificationServiceTest
```

Expected:
- `BUILD SUCCESSFUL`

---

## Task 5: 복구 후 정상 차감 정합성 acceptance test 갱신

**Files:**
- Modify: `src/test/java/com/pooli/traffic/acceptance/TrafficRestoreBatchAcceptanceTest.java`

- [ ] **Step 1: 기존 잘못된 hash 기반 검증 수정**

`restoresRedisUsageAndBalanceFromDatabaseSources` 테스트에서 `daily_total_usage` hash 검증을 string counter 검증으로 바꾼다.

```java
assertThat(cacheStringRedisTemplate.opsForValue()
        .get(trafficRedisKeyFactory.dailyTotalUsageKey(lineId, usageDate)))
        .isEqualTo("95");
assertThat(readCacheHashLong(
        trafficRedisKeyFactory.dailyAppUsageKey(lineId, usageDate),
        "app:" + appId + ":individual"
)).isEqualTo(70L);
assertThat(readCacheHashLong(
        trafficRedisKeyFactory.dailyAppUsageKey(lineId, usageDate),
        "app:" + appId + ":shared"
)).isEqualTo(20L);
assertThat(readCacheHashLong(
        trafficRedisKeyFactory.dailyAppUsageKey(lineId, usageDate),
        "app:" + appId + ":qos"
)).isEqualTo(5L);
assertThat(readCacheHashLong(
        trafficRedisKeyFactory.dailySharedUsageKey(lineId, usageDate),
        "usage_amount"
)).isEqualTo(20L);
assertThat(readCacheHashLong(
        trafficRedisKeyFactory.dailySharedUsageKey(lineId, usageDate),
        "family_id"
)).isEqualTo(familyId);
assertThat(readCacheHashLong(
        trafficRedisKeyFactory.monthlySharedUsageKey(lineId, targetMonth),
        "usage_amount"
)).isEqualTo(20L);
assertThat(readCacheHashLong(
        trafficRedisKeyFactory.remainingIndivAmountKey(lineId, targetMonth),
        "amount"
)).isEqualTo(930L);
assertThat(cacheStringRedisTemplate.opsForHash()
        .hasKey(trafficRedisKeyFactory.remainingIndivAmountKey(lineId, targetMonth), "qos"))
        .isTrue();
```

- [ ] **Step 2: 공유 사용량 0일 때 가족풀 사용량 key 미생성 테스트 추가**

`TrafficRestoreBatchAcceptanceTest`에 아래 테스트를 추가한다.

```java
@Test
@DisplayName("복구 batch는 공유 사용량이 없으면 가족풀 사용량 key를 만들지 않는다")
void doesNotCreateSharedUsageKeysWhenSharedUsageIsZero() {
    LocalDate usageDate = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
    YearMonth targetMonth = YearMonth.from(usageDate);
    long lineId = LINE_ID_2;
    long familyId = FAMILY_ID_1;
    int appId = fixtureIds.appId();
    cleanupDailyAppFixture(lineId, usageDate, appId);
    setLineSourceTotalData(lineId, 1000L);
    setFamilySourcePoolTotalData(familyId, 1000L);
    insertDailyAppUsage(usageDate, lineId, appId, 70L, 0L, 5L);

    RestoreVerificationResult result = verificationService.verifyAndCorrect(
            usageDate,
            new RestoreRange(usageDate, usageDate.plusDays(1))
    );

    assertThat(result.failedCorrectionCount()).isZero();
    // 공유 사용량이 0이면 정상 차감 계약과 동일하게 key를 만들지 않고 0 초기화도 하지 않는다.
    assertThat(cacheStringRedisTemplate.hasKey(
            trafficRedisKeyFactory.dailySharedUsageKey(lineId, usageDate)
    )).isFalse();
    assertThat(cacheStringRedisTemplate.hasKey(
            trafficRedisKeyFactory.monthlySharedUsageKey(lineId, targetMonth)
    )).isFalse();
    assertThat(cacheStringRedisTemplate.opsForValue()
            .get(trafficRedisKeyFactory.dailyTotalUsageKey(lineId, usageDate)))
            .isEqualTo("75");
}
```

- [ ] **Step 3: 복구 후 정상 차감 재개 테스트 추가**

`TrafficRestoreBatchAcceptanceTest`에 아래 테스트를 추가한다.

```java
@Test
@DisplayName("복구된 Redis 데이터 기준으로 기존 트래픽 차감이 정상 진행된다")
void deductsTrafficConsistentlyAfterRestore() throws Exception {
    LocalDate usageDate = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
    YearMonth targetMonth = YearMonth.from(usageDate);
    long lineId = LINE_ID_3;
    long familyId = FAMILY_ID_1;
    int appId = fixtureIds.appId();
    cleanupDailyAppFixture(lineId, usageDate, appId);
    setLineSourceTotalData(lineId, 1000L);
    setFamilySourcePoolTotalData(familyId, 1000L);
    prepareGlobalPolicySnapshot(false);
    prepareRestorePolicySnapshot(false);
    insertDailyAppUsage(usageDate, lineId, appId, 70L, 0L, 5L);

    RestoreVerificationResult result = verificationService.verifyAndCorrect(
            usageDate,
            new RestoreRange(usageDate, usageDate.plusDays(1))
    );
    assertThat(result.failedCorrectionCount()).isZero();
    // 복구 후 추가 차감도 공유풀을 쓰지 않았다면 가족풀 사용량 key는 계속 없어야 한다.
    assertThat(cacheStringRedisTemplate.hasKey(
            trafficRedisKeyFactory.dailySharedUsageKey(lineId, usageDate)
    )).isFalse();

    String traceId = enqueueTrafficRequest(lineId, familyId, appId, 10L);
    assertDoneLog(traceId, 10L, 0L, 0L, 0L, "SUCCESS", "OK");

    await("restore 이후 daily total usage string counter 증가", () -> readDailyTotalUsage(lineId) == 85L);
    assertThat(readDailyAppUsageBySource(lineId, appId, "individual")).isEqualTo(80L);
    assertThat(readIndividualBalanceAmount(lineId)).isEqualTo(920L);
    assertThat(cacheStringRedisTemplate.opsForHash()
            .hasKey(trafficRedisKeyFactory.remainingIndivAmountKey(lineId, targetMonth), "qos"))
            .isTrue();
    assertThat(cacheStringRedisTemplate.hasKey(
            trafficRedisKeyFactory.dailySharedUsageKey(lineId, usageDate)
    )).isFalse();
}
```

- [ ] **Step 4: acceptance 실패 확인**

Run:

```bash
./gradlew test --tests com.pooli.traffic.acceptance.TrafficRestoreBatchAcceptanceTest
```

Expected before Tasks 2-4 are complete:
- daily total 자료형 또는 shared usage key 생성 문제로 실패한다.

Expected after Tasks 2-4 are complete:
- `BUILD SUCCESSFUL`

---

## Task 6: 관련 회귀 테스트 실행 및 self-review

**Files:**
- No new file

- [ ] **Step 1: 좁은 범위 테스트 실행**

Run:

```bash
./gradlew test --tests com.pooli.traffic.service.runtime.TrafficRestoreLuaContractTest --tests com.pooli.traffic.service.restore.TrafficRestoreReplayLuaExecutorTest --tests com.pooli.traffic.service.restore.TrafficRestoreVerificationServiceTest --tests com.pooli.traffic.mapper.TrafficRestoreVerificationMapperSqlContractTest --tests com.pooli.traffic.acceptance.TrafficRestoreBatchAcceptanceTest
```

Expected:
- `BUILD SUCCESSFUL`

- [ ] **Step 2: 차감/배치 인접 회귀 테스트 실행**

Run:

```bash
./gradlew test --tests com.pooli.traffic.acceptance.TrafficDataDeductAcceptanceTest --tests com.pooli.traffic.acceptance.LineDailyUsageSyncBatchAcceptanceTest --tests com.pooli.traffic.service.batch.LineDailyUsageRedisReaderTest --tests com.pooli.data.service.impl.DataServiceImplTest
```

Expected:
- `BUILD SUCCESSFUL`

- [ ] **Step 3: 전체 테스트 실행**

Run:

```bash
./gradlew test
```

Expected:
- `BUILD SUCCESSFUL`

- [ ] **Step 4: build 실행**

Run:

```bash
./gradlew build
```

Expected:
- `BUILD SUCCESSFUL`

- [ ] **Step 5: self-review 질문에 답하고 필요 시 수정**

구현 완료 보고 전에 아래 질문을 코드 기준으로 확인한다.

```text
1. 개인풀 복구 경로가 amount와 qos를 같은 key에 모두 보장하는가?
2. shared_usage가 0인 replay/verification/correction 경로가 daily_shared_usage 또는 monthly_shared_usage key를 만들지 않고 0 초기화도 하지 않는가?
3. daily_total_usage가 모든 복구 경로에서 string counter인가?
4. 복구 후 deduct_unified.lua가 GET/INCRBY와 hash HINCRBY 계약 충돌 없이 실행되는가?
5. 테스트 편의를 위해 production 구조를 인위적으로 바꾼 부분이 없는가?
6. 새 abstraction 없이 기존 service/Lua/mapper 경계를 최소 수정했는가?
```

Expected:
- 모든 답이 "예"이거나, "아니오"인 항목은 보고 전에 수정하고 최소 테스트를 재실행한다.

---

## 완료 보고 형식

구현이 승인되고 모든 task가 완료되면 한국어로 보고한다.

보고에 포함할 내용:

- 변경 파일
- Redis 계약 변경 요약
- 개인풀 `amount`/`qos` 복구 보장 방식
- 가족풀 사용량 key 미생성 보장 방식
- 복구 후 정상 차감 검증 결과
- 실행한 테스트 명령과 결과
- self-review 결과
- 남은 위험 또는 운영 시 주의점

## 계획 self-review

- Spec coverage:
  - 개인풀 hash 모든 필드 복구: Task 3, Task 4, Task 5에서 `qos` 검증/보정/acceptance 확인으로 대응한다.
  - 존재하지 않는 가족풀 사용량 key 생성 금지: Task 2, Task 3, Task 5에서 `shared_usage > 0` 조건과 key 미생성 및 0 미초기화 acceptance test로 대응한다.
  - 복구 후 기존 트래픽 차감 정합성: Task 2, Task 4, Task 5에서 정상 `deduct_unified.lua` Redis 계약과 동일한 자료형/field로 맞추고 실제 차감 acceptance test로 대응한다.
- Placeholder scan:
  - 미정 상태를 뜻하는 임시 표식이나 보류 문구는 없다.
  - 각 task는 수정 파일, 테스트 코드, 실행 명령, 예상 결과를 포함한다.
- Type consistency:
  - `executeRestoreUsageCorrection` 새 signature는 Task 4의 test, service, infra 변경에서 동일하게 사용한다.
  - `DAILY_TOTAL_USAGE`의 field sentinel은 `__value__`로 통일한다.
  - 공유 사용량 field는 정상 계약인 `usage_amount`, `family_id`로 통일한다.
