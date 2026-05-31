# 트래픽 캐시 스냅샷 Hydrate 검증 고도화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redis 월별 잔량 스냅샷 해시가 불완전하거나 깨진 경우(단순 Key는 존재하나 내부 필드가 누락된 경우) 조기 종료 처리되는 현상을 방지하기 위해 필수 필드의 존재성 및 무공백 상태를 검사하도록 전/후 검증 로직을 개선합니다.

**Architecture:** `TrafficRemainingBalanceCacheService`에 각각 개인(`amount`, `qos` 필드 검사) 및 공유(`amount` 필드 검사) 스냅샷의 준비 여부를 판별하는 `isIndividualReady`와 `isSharedReady`를 추가합니다. `TrafficBalanceSnapshotHydrateService`에서는 `hydrateWithLock` 공통 잠금 메서드에 `BooleanSupplier`를 전달하여 락 획득 전후로 동일한 필드 수준 유효성 검증이 실행되도록 리팩토링합니다.

**Tech Stack:** Java 21, Spring Boot 3.5.10, Spring Data Redis 3.5.8, JUnit Jupiter 5.12.2, Mockito 5.17.0

**Dependency Source:** `docs/context7-dependencies.yaml` 기준 Spring Boot 3.5.10, Spring Data Redis 3.5.8, JUnit Jupiter 5.12.2, Mockito 5.17.0을 사용합니다. 단위 테스트 작성 방식은 `docs/junit-unit-test-guide.md`를 따릅니다.

**Scope Note:** 이 계획은 `TrafficBalanceSnapshotHydrateService` 내부의 `hasKey` 기반 조기 종료와 lock 내부 재확인을 field-level readiness 검사로 바꾸는 데 한정합니다. `TrafficDeductOrchestratorService`의 preflight `EXISTS` 최적화는 이번 범위에서 변경하지 않으며, 최종 차감 Lua의 field-level hydrate fallback은 그대로 유지합니다.

---

### Task 1: TrafficRemainingBalanceCacheService 스냅샷 준비성 검증 메서드 구현

**Files:**
- Modify: `src/main/java/com/pooli/traffic/service/runtime/TrafficRemainingBalanceCacheService.java`
- Test: `src/test/java/com/pooli/traffic/service/runtime/TrafficRemainingBalanceCacheServiceTest.java`

- [x] **Step 1: Write the failing test**
  `TrafficRemainingBalanceCacheServiceTest.java` 내부에 새로 정의할 `@Nested class ReadinessCheckTest` 테스트를 아래 내용으로 추가합니다.
  
  ```java
      @Nested
      class ReadinessCheckTest {

          @Test
          @DisplayName("개인 스냅샷 해시에 amount와 qos가 정상 존재하면 true 반환")
          void isIndividualReadyReturnsTrueWhenFieldsExistAndNonEmpty() {
              when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
              when(hashOperations.get("pooli:remaining_indiv_amount:11:202605", "amount")).thenReturn("300");
              when(hashOperations.get("pooli:remaining_indiv_amount:11:202605", "qos")).thenReturn("250");

              boolean result = trafficRemainingBalanceCacheService.isIndividualReady("pooli:remaining_indiv_amount:11:202605");

              assertTrue(result);
          }

          @Test
          @DisplayName("개인 스냅샷 해시에 amount가 누락되면 false 반환")
          void isIndividualReadyReturnsFalseWhenAmountMissing() {
              when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
              when(hashOperations.get("pooli:remaining_indiv_amount:11:202605", "amount")).thenReturn(null);
              when(hashOperations.get("pooli:remaining_indiv_amount:11:202605", "qos")).thenReturn("250");

              boolean result = trafficRemainingBalanceCacheService.isIndividualReady("pooli:remaining_indiv_amount:11:202605");

              assertFalse(result);
          }

          @Test
          @DisplayName("개인 스냅샷 해시에 qos가 누락되면 false 반환")
          void isIndividualReadyReturnsFalseWhenQosMissing() {
              when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
              when(hashOperations.get("pooli:remaining_indiv_amount:11:202605", "amount")).thenReturn("300");
              when(hashOperations.get("pooli:remaining_indiv_amount:11:202605", "qos")).thenReturn(null);

              boolean result = trafficRemainingBalanceCacheService.isIndividualReady("pooli:remaining_indiv_amount:11:202605");

              assertFalse(result);
          }

          @Test
          @DisplayName("개인 스냅샷 해시의 amount가 공백 문자열이면 false 반환")
          void isIndividualReadyReturnsFalseWhenAmountIsBlank() {
              when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
              when(hashOperations.get("pooli:remaining_indiv_amount:11:202605", "amount")).thenReturn(" ");
              when(hashOperations.get("pooli:remaining_indiv_amount:11:202605", "qos")).thenReturn("250");

              boolean result = trafficRemainingBalanceCacheService.isIndividualReady("pooli:remaining_indiv_amount:11:202605");

              assertFalse(result);
          }

          @Test
          @DisplayName("개인 스냅샷 해시의 qos가 공백 문자열이면 false 반환")
          void isIndividualReadyReturnsFalseWhenQosIsBlank() {
              when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
              when(hashOperations.get("pooli:remaining_indiv_amount:11:202605", "amount")).thenReturn("300");
              when(hashOperations.get("pooli:remaining_indiv_amount:11:202605", "qos")).thenReturn(" ");

              boolean result = trafficRemainingBalanceCacheService.isIndividualReady("pooli:remaining_indiv_amount:11:202605");

              assertFalse(result);
          }

          @Test
          @DisplayName("공유 스냅샷 해시에 amount가 정상 존재하면 true 반환")
          void isSharedReadyReturnsTrueWhenAmountExistsAndNonEmpty() {
              when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
              when(hashOperations.get("pooli:remaining_shared_amount:22:202605", "amount")).thenReturn("500");

              boolean result = trafficRemainingBalanceCacheService.isSharedReady("pooli:remaining_shared_amount:22:202605");

              assertTrue(result);
          }

          @Test
          @DisplayName("공유 스냅샷 해시에 amount가 누락되면 false 반환")
          void isSharedReadyReturnsFalseWhenAmountMissing() {
              when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
              when(hashOperations.get("pooli:remaining_shared_amount:22:202605", "amount")).thenReturn(null);

              boolean result = trafficRemainingBalanceCacheService.isSharedReady("pooli:remaining_shared_amount:22:202605");

              assertFalse(result);
          }

          @Test
          @DisplayName("공유 스냅샷 해시의 amount가 공백 문자열이면 false 반환")
          void isSharedReadyReturnsFalseWhenAmountIsBlank() {
              when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
              when(hashOperations.get("pooli:remaining_shared_amount:22:202605", "amount")).thenReturn(" ");

              boolean result = trafficRemainingBalanceCacheService.isSharedReady("pooli:remaining_shared_amount:22:202605");

              assertFalse(result);
          }
      }
  ```

- [x] **Step 2: Run test to verify it fails**
  Run: `./gradlew test --tests 'com.pooli.traffic.service.runtime.TrafficRemainingBalanceCacheServiceTest$ReadinessCheckTest'`
  Expected: 컴파일 실패 혹은 메서드가 정의되지 않아 실패

- [x] **Step 3: Write minimal implementation**
  `TrafficRemainingBalanceCacheService.java` 파일에 아래 두 메서드를 구현합니다.
  
  ```java
      /**
       * 개인 snapshot Redis hash의 필수 필드(amount, qos)가 모두 존재하고 비어있지 않은지 검증합니다.
       */
      public boolean isIndividualReady(String balanceKey) {
          if (balanceKey == null) {
              return false;
          }
          Object amountObj = cacheStringRedisTemplate.opsForHash().get(balanceKey, "amount");
          Object qosObj = cacheStringRedisTemplate.opsForHash().get(balanceKey, "qos");
          if (amountObj == null || qosObj == null) {
              return false;
          }
          String amount = String.valueOf(amountObj).trim();
          String qos = String.valueOf(qosObj).trim();
          return !amount.isEmpty() && !qos.isEmpty();
      }

      /**
       * 공유 snapshot Redis hash의 필수 필드(amount)가 존재하고 비어있지 않은지 검증합니다.
       */
      public boolean isSharedReady(String balanceKey) {
          if (balanceKey == null) {
              return false;
          }
          Object amountObj = cacheStringRedisTemplate.opsForHash().get(balanceKey, "amount");
          if (amountObj == null) {
              return false;
          }
          return !String.valueOf(amountObj).trim().isEmpty();
      }
  ```

- [x] **Step 4: Run test to verify it passes**
  Run: `./gradlew test --tests 'com.pooli.traffic.service.runtime.TrafficRemainingBalanceCacheServiceTest$ReadinessCheckTest'`
  Expected: PASS

- [x] **Step 5: Stage changes and request commit confirmation**
  ```bash
  git add src/main/java/com/pooli/traffic/service/runtime/TrafficRemainingBalanceCacheService.java src/test/java/com/pooli/traffic/service/runtime/TrafficRemainingBalanceCacheServiceTest.java
  ```
  Expected: 코드 변경 커밋은 `AGENTS.md §11.6`에 따라 사용자 확인 후 수행합니다. 승인받은 commit message는 `Feat(traffic): 캐시 준비성 검증 헬퍼 구현`입니다.

---

### Task 2: TrafficBalanceSnapshotHydrateService 리팩토링 및 검증 로직 전환

**Files:**
- Modify: `src/main/java/com/pooli/traffic/service/runtime/TrafficBalanceSnapshotHydrateService.java`
- Modify: `src/test/java/com/pooli/traffic/service/runtime/TrafficBalanceSnapshotHydrateServiceTest.java`

- [x] **Step 1: Write the failing/compilation-broken test**
  `TrafficBalanceSnapshotHydrateServiceTest.java` 파일에서 기존 `trafficRemainingBalanceCacheService.hasKey(...)`에 대한 모킹 설정을 모두 `isIndividualReady(...)` 혹은 `isSharedReady(...)`로 교체합니다. `verify(..., times(...))`를 사용하므로 아래 static import도 추가합니다.

  ```java
  import static org.mockito.Mockito.times;
  ```

  기존 테스트의 stubbing은 아래 규칙으로 바꿉니다.

  ```java
  // 개인 snapshot 경로
  when(trafficRemainingBalanceCacheService.hasKey("indiv:11")).thenReturn(false);
  // ->
  when(trafficRemainingBalanceCacheService.isIndividualReady("indiv:11")).thenReturn(false);

  // 공유 snapshot 경로
  when(trafficRemainingBalanceCacheService.hasKey("shared:22")).thenReturn(false);
  // ->
  when(trafficRemainingBalanceCacheService.isSharedReady("shared:22")).thenReturn(false);

  // lock 획득 전에는 미준비, lock 획득 후에는 다른 worker가 완성한 상태
  when(trafficRemainingBalanceCacheService.hasKey("shared:22")).thenReturn(false, true);
  // ->
  when(trafficRemainingBalanceCacheService.isSharedReady("shared:22")).thenReturn(false, true);
  ```

  기존 테스트명과 `@DisplayName` 중 key 존재 여부를 성공 조건으로 표현한 문구는 필드 준비성 기준으로 함께 바꿉니다.

  - `hydrateIndividualSnapshot_returnsHydratedWhenKeyAlreadyExistsBeforeLock` -> `hydrateIndividualSnapshot_returnsHydratedWhenRequiredFieldsReadyBeforeLock`
  - `@DisplayName("개인 잔량 key가 이미 있으면 hydrate lock 없이 HYDRATED를 반환한다")` -> `@DisplayName("개인 잔량 필수 필드가 이미 준비되어 있으면 hydrate lock 없이 HYDRATED를 반환한다")`
  - `hydrateSharedSnapshot_returnsHydratedWhenKeyExistsAfterLock` -> `hydrateSharedSnapshot_returnsHydratedWhenRequiredFieldsReadyAfterLock`
  - `@DisplayName("공유 잔량 key가 lock 획득 후 생성되면 RDB 조회 없이 HYDRATED를 반환한다")` -> `@DisplayName("공유 잔량 필수 필드가 lock 획득 후 준비되면 RDB 조회 없이 HYDRATED를 반환한다")`

  또한, 해시 필드 일부분이 비어 캐시가 깨졌을 때 정상적으로 Hydrate로 흘러가는지를 검증하는 아래 테스트를 추가합니다.
  
  ```java
      @Test
      @DisplayName("개인 잔량 key가 존재하지만 필수 필드가 누락된 경우(손상 상태) RDB 조회 후 정상 적재")
      void hydrateIndividualSnapshot_hydratesWhenKeyExistsButCorrupted() {
          YearMonth targetMonth = YearMonth.of(2026, 5);
          TrafficIndividualBalanceSnapshot snapshot = TrafficIndividualBalanceSnapshot.builder()
                  .lineId(11L)
                  .amount(300L)
                  .qosSpeedLimit(2L)
                  .lastBalanceRefreshedAt(LocalDateTime.of(2026, 5, 1, 0, 0))
                  .build();
          stubIndividualHydrateLockAcquired(11L);
          when(trafficBalanceSnapshotSourceMapper.selectIndividualBalanceSnapshot(11L)).thenReturn(snapshot);
          when(trafficRedisKeyFactory.remainingIndivAmountKey(11L, targetMonth)).thenReturn("indiv:11");
          // 첫 진입 시 검사 및 lock 내부 이중 검증에서 모두 준비되지 않음(필드 누락 상태)으로 판정합니다.
          when(trafficRemainingBalanceCacheService.isIndividualReady("indiv:11")).thenReturn(false, false);
          when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth)).thenReturn(1_779_033_599L);

          TrafficBalanceSnapshotHydrateResult result = service.hydrateIndividualSnapshot(11L, targetMonth);

          assertThat(result.status()).isEqualTo(Status.HYDRATED);
          verify(trafficRemainingBalanceCacheService, times(2)).isIndividualReady("indiv:11");
          verify(trafficRemainingBalanceCacheService, never()).hasKey("indiv:11");
          verify(trafficRemainingBalanceCacheService)
                  .hydrateIndividualSnapshot("indiv:11", 300L, 250L, 1_779_033_599L);
      }

      @Test
      @DisplayName("공유 잔량 key가 존재하지만 amount가 누락된 경우(손상 상태) RDB 조회 후 정상 적재")
      void hydrateSharedSnapshot_hydratesWhenKeyExistsButCorrupted() {
          YearMonth targetMonth = YearMonth.of(2026, 5);
          TrafficSharedBalanceSnapshot snapshot = TrafficSharedBalanceSnapshot.builder()
                  .familyId(22L)
                  .amount(500L)
                  .lastBalanceRefreshedAt(LocalDateTime.of(2026, 5, 1, 0, 0))
                  .build();
          stubSharedHydrateLockAcquired(22L);
          when(trafficBalanceSnapshotSourceMapper.selectSharedBalanceSnapshot(22L)).thenReturn(snapshot);
          when(trafficRedisKeyFactory.remainingSharedAmountKey(22L, targetMonth)).thenReturn("shared:22");
          // 첫 진입 시 검사 및 lock 내부 이중 검증에서 모두 준비되지 않음(amount 누락 상태)으로 판정합니다.
          when(trafficRemainingBalanceCacheService.isSharedReady("shared:22")).thenReturn(false, false);
          when(trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth)).thenReturn(1_779_033_599L);

          TrafficBalanceSnapshotHydrateResult result = service.hydrateSharedSnapshot(22L, targetMonth);

          assertThat(result.status()).isEqualTo(Status.HYDRATED);
          verify(trafficRemainingBalanceCacheService, times(2)).isSharedReady("shared:22");
          verify(trafficRemainingBalanceCacheService, never()).hasKey("shared:22");
          verify(trafficRemainingBalanceCacheService)
                  .hydrateSharedSnapshot("shared:22", 500L, 1_779_033_599L);
      }
  ```

- [x] **Step 2: Run test to verify it fails**
  Run: `./gradlew test --tests "com.pooli.traffic.service.runtime.TrafficBalanceSnapshotHydrateServiceTest"`
  Expected: 컴파일 에러 혹은 Stubbing이 맞지 않아 검증 실패

- [x] **Step 3: Write minimal implementation**
  `TrafficBalanceSnapshotHydrateService.java`에서 `BooleanSupplier` import를 추가하고, `hydrateWithLock`의 시그니처와 구현부를 리팩토링하며, 개별/공유 진입점을 각각 맞춤형 검증 메서드로 갱신합니다.
  
  ```java
  import java.util.function.BooleanSupplier;
  ```

  ```java
      /**
       * 개인 회선의 월별 잔량/QoS snapshot을 hydrate합니다.
       *
       * <p>Redis hash에 amount와 qos 필드가 모두 준비되어 있으면 성공으로 종료하고, 누락 시 lineId 단위 lock 전후로
       * 동일한 필드 준비성 검사를 다시 수행한 뒤 lock을 획득한 worker만 RDB source 조회와 Redis 적재를 수행합니다.
       */
      public TrafficBalanceSnapshotHydrateResult hydrateIndividualSnapshot(Long lineId, YearMonth targetMonth) {
          if (lineId == null || lineId <= 0 || targetMonth == null) {
              return TrafficBalanceSnapshotHydrateResult.invalidOwner();
          }

          String balanceKey = trafficRedisKeyFactory.remainingIndivAmountKey(lineId, targetMonth);
          if (trafficRemainingBalanceCacheService.isIndividualReady(balanceKey)) {
              return TrafficBalanceSnapshotHydrateResult.hydrated();
          }
          return hydrateWithLock(
                  trafficRedisKeyFactory.indivHydrateLockKey(lineId),
                  () -> trafficRemainingBalanceCacheService.isIndividualReady(balanceKey),
                  () -> hydrateIndividualSnapshotUnlocked(lineId, targetMonth, balanceKey)
          );
      }

      /**
       * 가족 공유풀의 월별 잔량 snapshot을 hydrate합니다.
       *
       * <p>Redis hash에 amount 필드가 준비되어 있으면 성공으로 종료하고, 누락 시 familyId 단위 lock 전후로
       * 동일한 필드 준비성 검사를 다시 수행한 뒤 lock을 획득한 worker만 RDB source 조회와 Redis 적재를 수행합니다.
       */
      public TrafficBalanceSnapshotHydrateResult hydrateSharedSnapshot(Long familyId, YearMonth targetMonth) {
          if (familyId == null || familyId <= 0 || targetMonth == null) {
              return TrafficBalanceSnapshotHydrateResult.invalidOwner();
          }

          String balanceKey = trafficRedisKeyFactory.remainingSharedAmountKey(familyId, targetMonth);
          if (trafficRemainingBalanceCacheService.isSharedReady(balanceKey)) {
              return TrafficBalanceSnapshotHydrateResult.hydrated();
          }
          return hydrateWithLock(
                  trafficRedisKeyFactory.sharedHydrateLockKey(familyId),
                  () -> trafficRemainingBalanceCacheService.isSharedReady(balanceKey),
                  () -> hydrateSharedSnapshotUnlocked(familyId, targetMonth, balanceKey)
          );
      }

      /**
       * owner 단위 Redis lock을 감싼 공통 실행부입니다.
       *
       * <p>lock 획득 실패는 호출자의 retry 정책으로 넘기고, lock 획득 후 RDB 조회 전 동일한 준비성 검사를 다시 수행합니다.
       * 획득한 lock은 Lua compare-and-delete로 해제합니다.
       */
      private TrafficBalanceSnapshotHydrateResult hydrateWithLock(
              String lockKey,
              BooleanSupplier readinessChecker,
              SnapshotHydrateAction hydrateAction
      ) {
          Optional<TrafficLuaScriptInfraService.HydrateLockHandle> lockHandle =
                  trafficLuaScriptInfraService.tryAcquireHydrateLock(lockKey);
          if (lockHandle.isEmpty()) {
              return TrafficBalanceSnapshotHydrateResult.notReady();
          }

          try {
              // lock 대기 중 다른 worker가 snapshot을 완벽하게 만들 수 있으므로 RDB 조회 전에 한 번 더 확인합니다.
              if (readinessChecker.getAsBoolean()) {
                  return TrafficBalanceSnapshotHydrateResult.hydrated();
              }
              return hydrateAction.hydrate();
          } finally {
              trafficLuaScriptInfraService.releaseHydrateLock(lockHandle.get());
          }
      }
  ```

- [x] **Step 4: Run test to verify it passes**
  Run: `./gradlew test --tests "com.pooli.traffic.service.runtime.TrafficBalanceSnapshotHydrateServiceTest"`
  Expected: PASS

- [x] **Step 5: Stage changes and request commit confirmation**
  ```bash
  git add src/main/java/com/pooli/traffic/service/runtime/TrafficBalanceSnapshotHydrateService.java src/test/java/com/pooli/traffic/service/runtime/TrafficBalanceSnapshotHydrateServiceTest.java
  ```
  Expected: 코드 변경 커밋은 `AGENTS.md §11.6`에 따라 사용자 확인 후 수행합니다. 승인받은 commit message는 `Fix(traffic): 캐시 준비성 상태 기반 트래픽 Hydrate 유효성 검증 적용`입니다.
