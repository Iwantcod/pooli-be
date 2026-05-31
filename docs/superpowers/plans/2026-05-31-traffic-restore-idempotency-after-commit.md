# Traffic Restore Idempotency Cleanup Exception Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring TransactionSynchronization.afterCommit()의 멱등키 삭제 중 발생한 예외 전파를 막아 복구 Phase 1/2 워커 중단을 방지하고, 실패 로그 및 모니터링 메트릭을 안전하게 수집한다.

**Architecture:** 
1. `TrafficRedisAvailabilityMetrics`에 멱등키 삭제 실패 계측을 위한 카운터 메트릭을 추가한다.
2. `TrafficRestorePhase1ReplayService` 및 `TrafficRestorePhase2ReplayService`에 메트릭 객체를 주입하고, `afterCommit()` 동작을 `try-catch` 블록으로 감싸서 예외를 삼키고 경고 로깅 및 메트릭 카운트를 증가시킨다.
3. 해당 서비스들의 단위 테스트를 수정하여 신규 주입된 의존성을 Mocking하고 예외 포획 동작이 보장되는지 검증한다.

**Tech Stack:** Spring Boot 3.5.10, Micrometer, JUnit 5, Mockito

---

### Task 1: 메트릭 수집을 위한 신규 카운터 정의

**Files:**
- Modify: `src/main/java/com/pooli/monitoring/metrics/TrafficRedisAvailabilityMetrics.java`

- [ ] **Step 1: TrafficRedisAvailabilityMetrics에 멱등키 정리 실패 메트릭 메서드 추가**
  
  `TrafficRedisAvailabilityMetrics` 내부에 다음 메서드를 추가한다.
  ```java
  /**
   * 복구 작업 완료 후 멱등키 정리(afterCommit) 과정에서 발생한 실패 횟수를 기록합니다.
   */
  public void incrementIdempotencyCleanupFailure() {
      meterRegistry.counter(
              "traffic_restore_idempotency_cleanup_failures_total",
              "redis", RedisTarget.CACHE.tagValue()
      ).increment();
  }
  ```

- [ ] **Step 2: 로컬 빌드 및 컴파일 확인**
  
  Run: `./gradlew compileJava`
  Expected: BUILD SUCCESSFUL

---

### Task 2: Phase 2 복구 서비스의 예외 전파 차단 및 메트릭 연동

**Files:**
- Modify: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayService.java`
- Test: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase2ReplayServiceTest.java`

- [ ] **Step 1: TrafficRestorePhase2ReplayService에 메트릭 의존성 주입 및 try-catch 감싸기**
  
  `redisMetrics` 주입 및 `registerIdempotencyCleanupAfterCommit` 메서드를 아래와 같이 수정한다.
  ```java
  private final TrafficRedisAvailabilityMetrics redisMetrics;

  private void registerIdempotencyCleanupAfterCommit(String idempotencyKey) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
          @Override
          public void afterCommit() {
              try {
                  replayLuaExecutor.deleteIdempotencyKey(idempotencyKey);
              } catch (Exception cleanupFailure) {
                  org.slf4j.LoggerFactory.getLogger(TrafficRestorePhase2ReplayService.class)
                          .warn("Failed to delete phase 2 idempotency key: {}", idempotencyKey, cleanupFailure);
                  redisMetrics.incrementIdempotencyCleanupFailure();
              }
          }
      });
  }
  ```

- [ ] **Step 2: TrafficRestorePhase2ReplayServiceTest에 Mock 추가 및 예외 포획 테스트 추가**
  
  기존 테스트 클래스에 `@Mock private TrafficRedisAvailabilityMetrics redisMetrics;` 의존성을 추가하고, `afterCommit` 시 예외가 발생해도 서비스 바깥으로 예외가 전파되지 않는지 검증하는 테스트 케이스를 신설한다.
  
  ```java
  @Mock
  private TrafficRedisAvailabilityMetrics redisMetrics;

  @Test
  @DisplayName("phase 2 worker는 afterCommit 중 멱등키 삭제 예외가 발생하더라도 예외를 포획하고 모니터링 메트릭을 기록한다")
  void swallowsExceptionAndIncrementsMetricWhenCleanupFails() {
      TrafficDeductDoneLog log = doneLog(10L, "PROCESSING");
      when(trafficRedisKeyFactory.restoreIdempotencyKey("p2:done_log", "10"))
              .thenReturn("pooli:restore:idempotency:p2:done_log:10");
      when(replayLuaExecutor.replay(org.mockito.ArgumentMatchers.any()))
              .thenReturn(new TrafficRestoreReplayResult("APPLIED", null));
      when(doneLogMapper.markRestoreDoneIfProcessing(10L, WORKER_ID)).thenReturn(1);
      
      org.mockito.doThrow(new RuntimeException("Redis connection error"))
              .when(replayLuaExecutor).deleteIdempotencyKey("pooli:restore:idempotency:p2:done_log:10");

      TransactionSynchronizationManager.initSynchronization();
      try {
          service.replay(log, WORKER_ID);

          // afterCommit 실행 트리거 시 예외가 발생해도 밖으로 예외가 던져지지 않아야 함
          org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
              TransactionSynchronizationManager.getSynchronizations()
                      .forEach(TransactionSynchronization::afterCommit);
          });

          // 에러 메트릭 기록을 호출했는지 검증
          verify(redisMetrics).incrementIdempotencyCleanupFailure();
      } finally {
          TransactionSynchronizationManager.clearSynchronization();
      }
  }
  ```

- [ ] **Step 3: Phase 2 replay 서비스 테스트 실행**
  
  Run: `./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestorePhase2ReplayServiceTest`
  Expected: BUILD SUCCESSFUL (3개 테스트 모두 통과)

---

### Task 3: Phase 1 복구 서비스의 예외 전파 차단 및 메트릭 연동

**Files:**
- Modify: `src/main/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayService.java`
- Test: `src/test/java/com/pooli/traffic/service/restore/TrafficRestorePhase1ReplayServiceTest.java`

- [ ] **Step 1: TrafficRestorePhase1ReplayService에 메트릭 의존성 주입 및 try-catch 감싸기**
  
  `redisMetrics` 주입 및 `registerIdempotencyCleanupAfterCommit` 메서드를 아래와 같이 수정한다.
  ```java
  private final TrafficRedisAvailabilityMetrics redisMetrics;

  private void registerIdempotencyCleanupAfterCommit(String idempotencyKey) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
          @Override
          public void afterCommit() {
              try {
                  replayLuaExecutor.deleteIdempotencyKey(idempotencyKey);
              } catch (Exception cleanupFailure) {
                  org.slf4j.LoggerFactory.getLogger(TrafficRestorePhase1ReplayService.class)
                          .warn("Failed to delete phase 1 idempotency key: {}", idempotencyKey, cleanupFailure);
                  redisMetrics.incrementIdempotencyCleanupFailure();
              }
          }
      });
  }
  ```

- [ ] **Step 2: TrafficRestorePhase1ReplayServiceTest에 Mock 추가 및 예외 포획 테스트 추가**
  
  기존 테스트 클래스에 `@Mock private TrafficRedisAvailabilityMetrics redisMetrics;` 의존성을 추가하고, 예외 발생 시 전파가 차단되는지 검증하는 테스트 케이스를 신설한다.
  
  ```java
  @Mock
  private TrafficRedisAvailabilityMetrics redisMetrics;

  @Test
  @DisplayName("phase 1 worker는 afterCommit 중 멱등키 삭제 예외가 발생하더라도 예외를 포획하고 모니터링 메트릭을 기록한다")
  void swallowsExceptionAndIncrementsMetricWhenCleanupFails() {
      TrafficRestoreDailyAppTarget target = target();
      TrafficRestoreReplayCommand command = command();
      when(dailyAppTargetMapper.selectReplayCommand(target.getId())).thenReturn(command);
      when(replayLuaExecutor.replay(command)).thenReturn(new TrafficRestoreReplayResult("APPLIED", null));
      when(dailyAppTargetMapper.markTargetTerminalIfProcessing(
              target.getId(),
              TrafficRestoreTargetStatus.DONE,
              WORKER_ID
      )).thenReturn(1);

      org.mockito.doThrow(new RuntimeException("Redis connection error"))
              .when(replayLuaExecutor).deleteIdempotencyKey(command.getIdempotencyKey());

      TransactionSynchronizationManager.initSynchronization();
      try {
          service.replay(target, WORKER_ID);

          org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
              TransactionSynchronizationManager.getSynchronizations()
                      .forEach(TransactionSynchronization::afterCommit);
          });

          verify(redisMetrics).incrementIdempotencyCleanupFailure();
      } finally {
          TransactionSynchronizationManager.clearSynchronization();
      }
  }
  ```

- [ ] **Step 3: Phase 1 replay 서비스 테스트 실행**
  
  Run: `./gradlew test --tests com.pooli.traffic.service.restore.TrafficRestorePhase1ReplayServiceTest`
  Expected: BUILD SUCCESSFUL (전체 테스트 통과)

---

### Task 4: 통합 빌드 및 검증

- [ ] **Step 1: 전체 빌드 및 테스트 수행**
  
  Run: `./gradlew clean test`
  Expected: BUILD SUCCESSFUL
