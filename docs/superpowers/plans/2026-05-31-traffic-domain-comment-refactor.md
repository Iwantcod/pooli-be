# Traffic Domain 주석 보강 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `com.pooli.traffic.domain` 패키지 내 주석이 미작성된 28개 파일(클래스/메서드/필드 단위)에 대해 AGENTS.md 규정에 적합한 한국어 주석을 완비하여 문서화 및 유지보수성을 극대화합니다.

**Architecture:** 프로덕션 코드의 실행 비즈니스 로직은 전혀 수정하지 않는 순수 주석(Javadoc 및 한 줄 주석) 보강 리팩토링입니다. 도메인 성격에 따라 4개의 Task(Core, DTO, Enums & Restore, Outbox)로 모듈화하여 순차적으로 반영하고 컴파일 및 Git Diff 검증을 거칩니다.

**Tech Stack:** Java 17, Spring Boot, Gradle

---

## Proposed Changes

### Task 1: Core Domain 파일 주석 보강 (9개 파일)

**Files:**
- Modify: [TrafficDeductExecutionContext.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/TrafficDeductExecutionContext.java)
- Modify: [TrafficFamilyMetaSnapshot.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/TrafficFamilyMetaSnapshot.java)
- Modify: [TrafficInFlightIdempotencyEntry.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/TrafficInFlightIdempotencyEntry.java)
- Modify: [TrafficIndividualBalanceSnapshot.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/TrafficIndividualBalanceSnapshot.java)
- Modify: [TrafficLuaDeductExecutionResult.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/TrafficLuaDeductExecutionResult.java)
- Modify: [TrafficPolicyCheckLayerResult.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/TrafficPolicyCheckLayerResult.java)
- Modify: [TrafficSharedBalanceSnapshot.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/TrafficSharedBalanceSnapshot.java)
- Modify: [TrafficSharedPoolContributionLuaResult.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/TrafficSharedPoolContributionLuaResult.java)
- Modify: [TrafficStreamFields.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/TrafficStreamFields.java)

- [ ] **Step 1: TrafficDeductExecutionContext 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 트래픽 차감 요청의 고유 식별자(Trace ID) */
      private final String traceId;
      /** 차단성 정책 검증에 대한 Lua 스크립트 실행 결과 캐시 */
      private TrafficLuaExecutionResult blockingPolicyCheckResult;
  
      /**
       * 지정된 Trace ID를 사용하여 컨텍스트 인스턴스를 초기화하는 private 생성자
       */
      private TrafficDeductExecutionContext(String traceId) {
  ```

- [ ] **Step 2: TrafficFamilyMetaSnapshot 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 공유 풀을 공유하는 가족(Family) 그룹 식별자 */
      private final Long familyId;
      /** 가족 공유 풀에 할당된 전체 데이터량 (Byte 단위) */
      private final Long poolTotalData;
      /** 가족 공유 풀 사용량 경고/알림 임계값 비율 */
      private final Double familyThreshold;
      /** 가족 공유 풀 임계값 검증 정책의 활성화 여부 */
      private final Boolean thresholdActive;
  ```

- [ ] **Step 3: TrafficInFlightIdempotencyEntry 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /**
       * 중복 요청 방지를 위한 In-Flight 멱등성 엔트리를 생성합니다.
       */
      public static TrafficInFlightIdempotencyEntry of(String traceId, String rawLockValue) {
  ```

- [ ] **Step 4: TrafficIndividualBalanceSnapshot 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 회선 고유 식별자 */
      private final Long lineId;
      /** 회선별 개인 잔여 데이터량 (Byte 단위) */
      private final Long amount;
      /** 데이터 소진 시 적용될 QOS 제한 속도 (bps 단위) */
      private final Integer qosSpeedLimit;
      /** Redis 잔액 스냅샷이 최종 동기화/갱신된 Epoch 밀리초 시각 */
      private final Long lastBalanceRefreshedAt;
  ```

- [ ] **Step 5: TrafficLuaDeductExecutionResult 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 개인 풀에서 차감된 데이터양 (Byte 단위) */
      private final Long indivDeducted;
      /** 가족 공유 풀에서 차감된 데이터양 (Byte 단위) */
      private final Long sharedDeducted;
      /** QoS 제어 조건 하에 차감된 데이터양 (Byte 단위) */
      private final Long qosDeducted;
      /** 스크립트 실행이 완료된 Epoch 밀리초 시각 */
      private final Long finishedAtEpochMillis;
      /** Lua 스크립트 실행 결과 상태 정보 */
      private final TrafficLuaStatus status;
      /** 정책 검증 실패 또는 에러 발생 시 상세 원인 코드 */
      private final TrafficPolicyCheckFailureCause failureReason;
  
      /**
       * 개인, 공유, QoS 풀 전체에서 차감된 총 데이터 합계를 반환합니다.
       */
      public Long getTotalDeducted() {
          return safeNonNegative(indivDeducted) + safeNonNegative(sharedDeducted) + safeNonNegative(qosDeducted);
      }
  
      /**
       * 음수 값을 방지하고 최소 0 이상의 값을 보장하기 위한 유틸리티 메서드
       */
      private long safeNonNegative(Long value) {
  ```

- [ ] **Step 6: TrafficPolicyCheckLayerResult 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /**
       * 각 정책 검증 레이어의 실행 상태 및 최종 검증 결과를 생성하는 생성자입니다.
       */
      public TrafficPolicyCheckLayerResult(
  ```

- [ ] **Step 7: TrafficSharedBalanceSnapshot 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 가족(공유 풀) 식별자 */
      private final Long familyId;
      /** 가족 공유 풀의 잔여 데이터량 (Byte 단위) */
      private final Long amount;
      /** 공유 풀 Redis 잔액 스냅샷이 최종 갱신된 Epoch 밀리초 시각 */
      private final Long lastBalanceRefreshedAt;
  ```

- [ ] **Step 8: TrafficSharedPoolContributionLuaResult 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 공유 풀 기여도 반영 Lua 스크립트의 실행 상태 결과 */
      private final TrafficLuaStatus status;
      /** 개인 한도 반영 적용량 (Byte 단위) */
      private final Long individualApplied;
      /** 공유 한도 반영 적용량 (Byte 단위) */
      private final Long sharedApplied;
  ```

- [ ] **Step 9: TrafficStreamFields 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** Redis Stream 메시지에 담길 페이로드 필드의 Key 명칭 */
      public static final String PAYLOAD = "payload";
  
      /**
       * 상수 제공용 유틸리티 클래스의 인스턴스화 방지를 위한 private 생성자
       */
      private TrafficStreamFields() {}
  ```

- [ ] **Step 10: 컴파일 검증 및 Diff 확인**
  프로덕션 컴파일 실행: `./gradlew compileJava`
  컴파일 결과: SUCCESS
  로직 변화가 없는지 `git diff`를 통해 검증하고, 변경사항을 커밋 대기합니다.

---

### Task 2: DTO & Entity 주석 보강 (6개 파일)

**Files:**
- Modify: [TrafficGenerateReqDto.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/dto/request/TrafficGenerateReqDto.java)
- Modify: [TrafficPayloadReqDto.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/dto/request/TrafficPayloadReqDto.java)
- Modify: [TrafficDeductResultResDto.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/dto/response/TrafficDeductResultResDto.java)
- Modify: [TrafficGenerateResDto.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/dto/response/TrafficGenerateResDto.java)
- Modify: [TrafficLuaDeductResDto.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/dto/response/TrafficLuaDeductResDto.java)
- Modify: [TrafficDeductDoneLog.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/entity/TrafficDeductDoneLog.java)

- [ ] **Step 1: TrafficGenerateReqDto 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      @Schema(description = "회선 ID", example = "1001")
      @NotNull(message = "lineId는 필수입니다.")
      @Positive(message = "lineId는 1 이상이어야 합니다.")
      /** 트래픽 발생 대상 회선 식별자 */
      private Long lineId;
  
      @Schema(description = "가족 ID", example = "77")
      @NotNull(message = "familyId는 필수입니다.")
      @Positive(message = "familyId는 1 이상이어야 합니다.")
      /** 트래픽 발생 대상 가족 식별자 */
      private Long familyId;
  
      @Schema(description = "애플리케이션 ID", example = "12")
      @NotNull(message = "appId는 필수입니다.")
      @Positive(message = "appId는 1 이상이어야 합니다.")
      /** 트래픽을 발생시킨 애플리케이션 ID */
      private Integer appId;
  
      @Schema(description = "요청 이벤트 데이터량(Byte)", example = "1048576")
      @NotNull(message = "apiTotalData는 필수입니다.")
      @PositiveOrZero(message = "apiTotalData는 0 이상이어야 합니다.")
      /** 발생한 트래픽 총량 (Byte 단위) */
      private Long apiTotalData;
  ```

- [ ] **Step 2: TrafficPayloadReqDto 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      @NotBlank(message = "traceId는 필수입니다.")
      /** 트래픽 차감 트랜잭션 추적을 위한 고유 식별자 */
      private String traceId;
  
      @NotNull(message = "lineId는 필수입니다.")
      @Positive(message = "lineId는 1 이상이어야 합니다.")
      /** 회선 ID */
      private Long lineId;
  
      @NotNull(message = "familyId는 필수입니다.")
      @Positive(message = "familyId는 1 이상이어야 합니다.")
      /** 가족 ID */
      private Long familyId;
  
      @NotNull(message = "appId는 필수입니다.")
      @Positive(message = "appId는 1 이상이어야 합니다.")
      /** 애플리케이션 ID */
      private Integer appId;
  
      @NotNull(message = "apiTotalData는 필수입니다.")
      @PositiveOrZero(message = "apiTotalData는 0 이상이어야 합니다.")
      /** 처리 요청 데이터량 (Byte 단위) */
      private Long apiTotalData;
  
      @NotNull(message = "enqueuedAt은 필수입니다.")
      @Positive(message = "enqueuedAt은 양수여야 합니다.")
      /** 메시지가 큐에 인입된 Epoch 밀리초 시각 */
      private Long enqueuedAt;
  ```

- [ ] **Step 3: TrafficDeductResultResDto 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 요청 Trace ID */
      private String traceId;
      /** 최초 요청한 트래픽 총량 (Byte 단위) */
      private Long apiTotalData;
      /** 개인 풀 차감 바이트 수 */
      private Long deductedIndividualBytes;
      /** 가족 공유 풀 차감 바이트 수 */
      private Long deductedSharedBytes;
      /** QoS 제어 하에 차감된 바이트 수 */
      private Long deductedQosBytes;
      /** 차감 완료 후 최종 남은 API 잔여 데이터량 (Byte 단위) */
      private Long apiRemainingData;
      /** 트래픽 처리 최종 상태 코드 */
      private TrafficFinalStatus finalStatus;
      /** 최종 수행된 Lua 스크립트 결과 상태 */
      private TrafficLuaStatus lastLuaStatus;
      /** 차감 실패 시 원인 코드 */
      private TrafficPolicyCheckFailureCause failureReason;
      /** 트래픽 처리 기록 생성 시각 */
      private LocalDateTime createdAt;
      /** 트래픽 차감 처리 완료 시각 */
      private LocalDateTime finishedAt;
  
      /**
       * 0 이상의 유효한 바이트 수를 보장하기 위한 헬퍼 메서드
       */
      private long safeNonNegative(Long value) {
  ```

- [ ] **Step 4: TrafficGenerateResDto 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      @Schema(description = "요청 추적 ID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
      /** 요청 추적용 Trace ID */
      private String traceId;
  
      @Schema(description = "큐 인입 시각 (밀리초)", example = "1717124400000")
      /** 큐 인입 시각 (밀리초) */
      private Long enqueuedAt;
  ```

- [ ] **Step 5: TrafficLuaDeductResDto 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 개인 풀 차감 바이트 수 */
      private final Long indivDeducted;
      /** 가족 공유 풀 차감 바이트 수 */
      private final Long sharedDeducted;
      /** QoS 적용 차감 바이트 수 */
      private final Long qosDeducted;
      /** 스크립트 처리 완료 시각 */
      private final Long finishedAtEpochMillis;
      /** 실행 결과 상태 코드 */
      private final TrafficLuaStatus status;
  ```

- [ ] **Step 6: TrafficDeductDoneLog 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      @Id
      @GeneratedValue(strategy = GenerationType.IDENTITY)
      @Column(name = "traffic_deduct_done_id")
      /** 영속 로그 레코드 식별자 PK */
      private Long trafficDeductDoneId;
  
      @Column(name = "trace_id", nullable = false, unique = true)
      /** 요청 추적용 고유 Trace ID */
      private String traceId;
  
      @Column(name = "record_id", nullable = false)
      /** 이벤트를 기록한 DB 레코드 ID */
      private String recordId;
  
      @Column(name = "line_id", nullable = false)
      /** 대상 회선 ID */
      private Long lineId;
  
      @Column(name = "family_id", nullable = false)
      /** 대상 가족 ID */
      private Long familyId;
  
      @Column(name = "app_id", nullable = false)
      /** 애플리케이션 ID */
      private Integer appId;
  
      @Column(name = "enqueued_at", nullable = false)
      /** 최초 요청 큐 적재 시각 */
      private LocalDateTime enqueuedAt;
  
      @Column(name = "api_total_data", nullable = false)
      /** 요청 트래픽 총량 (Byte 단위) */
      private Long apiTotalData;
  
      @Column(name = "deducted_individual_bytes", nullable = false)
      /** 개인 풀 차감 바이트 수 */
      private Long deductedIndividualBytes;
  
      @Column(name = "deducted_shared_bytes", nullable = false)
      /** 가족 공유 풀 차감 바이트 수 */
      private Long deductedSharedBytes;
  
      @Column(name = "deducted_qos_bytes", nullable = false)
      /** QoS 적용 차감 바이트 수 */
      private Long deductedQosBytes;
  
      @Column(name = "api_remaining_data", nullable = false)
      /** 최종 잔여 데이터량 (Byte 단위) */
      private Long apiRemainingData;
  
      @Column(name = "final_status", nullable = false)
      @Enumerated(EnumType.STRING)
      /** 트래픽 처리 최종 완료 상태 */
      private TrafficFinalStatus finalStatus;
  
      @Column(name = "last_lua_status", nullable = false)
      @Enumerated(EnumType.STRING)
      /** 마지막 실행된 Lua 스크립트 상태 */
      private TrafficLuaStatus lastLuaStatus;
  
      @Column(name = "failure_reason")
      @Enumerated(EnumType.STRING)
      /** 차감 실패 원인 정보 */
      private TrafficPolicyCheckFailureCause failureReason;
  
      @Column(name = "created_at", nullable = false)
      /** 엔티티 로그 생성 시각 */
      private LocalDateTime createdAt;
  
      @Column(name = "started_at")
      /** 차감 처리 시작 시각 */
      private LocalDateTime startedAt;
  
      @Column(name = "finished_at")
      /** 차감 처리 종료 시각 */
      private LocalDateTime finishedAt;
  
      @Column(name = "latency")
      /** 처리 소요 시간 (밀리초 단위) */
      private Long latency;
  
      @Column(name = "restore_status", nullable = false)
      @Enumerated(EnumType.STRING)
      /** 트래픽 복구(Restore) 상태 정보 */
      private TrafficRestoreTargetStatus restoreStatus;
  
      @Column(name = "restore_status_updated_at", nullable = false)
      /** 복구 상태 최종 업데이트 시각 */
      private LocalDateTime restoreStatusUpdatedAt;
  
      @Column(name = "restore_retry_count", nullable = false)
      /** 복구 재시도 횟수 */
      private Integer restoreRetryCount;
  
      @Column(name = "restore_last_error_message")
      /** 복구 실패 시 마지막 에러 메시지 */
      private String restoreLastErrorMessage;
  
      /**
       * 0 이상의 유효한 데이터를 반환하기 위한 헬퍼 메서드
       */
      private long safeNonNegative(Long value) {
  ```

- [ ] **Step 7: 컴파일 검증 및 Diff 확인**
  프로덕션 컴파일 실행: `./gradlew compileJava`
  컴파일 결과: SUCCESS
  로직 변화가 없는지 `git diff`를 통해 검증하고, 변경사항을 커밋 대기합니다.

---

### Task 3: Enums & Restore 주석 보강 (3개 파일)

**Files:**
- Modify: [TrafficLuaScriptType.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/enums/TrafficLuaScriptType.java)
- Modify: [TrafficPolicyLuaScriptType.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/enums/TrafficPolicyLuaScriptType.java)
- Modify: [TrafficRestoreTargetStatus.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/restore/TrafficRestoreTargetStatus.java)

- [ ] **Step 1: TrafficLuaScriptType 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 차단 정책 검증 스크립트 매핑 인스턴스 */
      BLOCK_POLICY_CHECK("block_policy_check", "lua/block_policy_check.lua"),
      /** 키 사전 존재 여부 검증 스크립트 매핑 인스턴스 */
      PREFLIGHT_KEY_EXISTENCE("preflight_key_existence", "lua/preflight_key_existence.lua"),
      /** 트래픽 차감 통합 스크립트 매핑 인스턴스 */
      DEDUCT_UNIFIED("deduct_unified", "lua/deduct_unified.lua"),
      /** 개인 잔액 스냅샷 동기화 스크립트 매핑 인스턴스 */
      HYDRATE_INDIVIDUAL_SNAPSHOT("hydrate_individual_snapshot", "lua/hydrate_individual_snapshot.lua"),
      /** 공유 잔액 스냅샷 동기화 스크립트 매핑 인스턴스 */
      HYDRATE_SHARED_SNAPSHOT("hydrate_shared_snapshot", "lua/hydrate_shared_snapshot.lua"),
      /** 분산 락 해제 스크립트 매핑 인스턴스 */
      LOCK_RELEASE("lock_release", "lua/lock_release.lua"),
      /** In-Flight 중복 방지 키 생성 스크립트 매핑 인스턴스 */
      IN_FLIGHT_CREATE_IF_ABSENT("in_flight_create_if_absent", "lua/in_flight_create_if_absent.lua"),
      /** In-Flight 키 재시도 카운트 증가 스크립트 매핑 인스턴스 */
      IN_FLIGHT_INCREMENT_RETRY_WITH_INIT("in_flight_increment_retry_with_init", "lua/in_flight_increment_retry_with_init.lua"),
      /** 공유 풀 기여도 반영 스크립트 매핑 인스턴스 */
      SHARED_POOL_CONTRIBUTION_APPLY("shared_pool_contribution_apply", "lua/shared_pool_contribution_apply.lua"),
      /** 공유 풀 기여도 복구 스크립트 매핑 인스턴스 */
      SHARED_POOL_CONTRIBUTION_RECOVER("shared_pool_contribution_recover", "lua/shared_pool_contribution_recover.lua"),
      /** 공유 풀 기여 데이터 정리 스크립트 매핑 인스턴스 */
      SHARED_POOL_CONTRIBUTION_CLEANUP("shared_pool_contribution_cleanup", "lua/shared_pool_contribution_cleanup.lua"),
      /** 데이터 복원 재실행 스크립트 매핑 인스턴스 */
      RESTORE_USAGE_REPLAY("restore_usage_replay", "lua/restore_usage_replay.lua"),
      /** 사용량 정정 스크립트 매핑 인스턴스 */
      RESTORE_USAGE_CORRECTION("restore_usage_correction", "lua/restore_usage_correction.lua");
  
      /** Lua 스크립트 파일명 식별 상수 */
      private final String scriptName;
      /** classpath 내 Lua 파일 상대 경로 */
      private final String resourcePath;
  ```

- [ ] **Step 2: TrafficPolicyLuaScriptType 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 정책값 CAS 연산 스크립트 인스턴스 */
      POLICY_VALUE_CAS("policy_value_cas", "lua/policy_value_cas.lua"),
      /** 차단 스냅샷 CAS 스크립트 인스턴스 */
      REPEAT_BLOCK_SNAPSHOT_CAS("repeat_block_snapshot_cas", "lua/repeat_block_snapshot_cas.lua"),
      /** 단건 앱 정책 CAS 스크립트 인스턴스 */
      APP_POLICY_SINGLE_CAS("app_policy_single_cas", "lua/app_policy_single_cas.lua"),
      /** 앱 정책 스냅샷 일괄 CAS 스크립트 인스턴스 */
      APP_POLICY_SNAPSHOT_CAS("app_policy_snapshot_cas", "lua/app_policy_snapshot_cas.lua");
  
      /** 스크립트 파일 이름 */
      private final String scriptName;
      /** 리소스 파일 경로 */
      private final String resourcePath;
  ```

- [ ] **Step 3: TrafficRestoreTargetStatus 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 복구 작업을 클레임(시작)할 수 있는 상태인지 여부 */
      private final boolean claimable;
      /** 최종 종료(복구 성공 또는 영구 실패) 상태인지 여부 */
      private final boolean terminal;
  
      /**
       * 상태 속성을 매핑하는 생성자
       */
      TrafficRestoreTargetStatus(boolean claimable, boolean terminal) {
  ```

- [ ] **Step 4: 컴파일 검증 및 Diff 확인**
  프로덕션 컴파일 실행: `./gradlew compileJava`
  컴파일 결과: SUCCESS
  로직 변화가 없는지 `git diff`를 통해 검증하고, 변경사항을 커밋 대기합니다.

---

### Task 4: Outbox 관련 파일 주석 보강 (10개 파일)

**Files:**
- Modify: [OutboxCreateResult.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/outbox/OutboxCreateResult.java)
- Modify: [RedisOutboxRecord.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/outbox/RedisOutboxRecord.java)
- Modify: [AppPolicyOutboxPayload.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/outbox/payload/AppPolicyOutboxPayload.java)
- Modify: [ImmediateBlockOutboxPayload.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/outbox/payload/ImmediateBlockOutboxPayload.java)
- Modify: [InFlightDedupeDeleteOutboxPayload.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/outbox/payload/InFlightDedupeDeleteOutboxPayload.java)
- Modify: [LineLimitOutboxPayload.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/outbox/payload/LineLimitOutboxPayload.java)
- Modify: [LineScopedOutboxPayload.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/outbox/payload/LineScopedOutboxPayload.java)
- Modify: [PolicyActivationOutboxPayload.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/outbox/payload/PolicyActivationOutboxPayload.java)
- Modify: [SharedPoolContributionOutboxPayload.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/outbox/payload/SharedPoolContributionOutboxPayload.java)
- Modify: [SharedPoolThresholdOutboxPayload.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/domain/outbox/payload/SharedPoolThresholdOutboxPayload.java)

- [ ] **Step 1: OutboxCreateResult 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /**
       * Outbox 레코드가 성공적으로 신규 생성되었음을 나타내는 팩토리 메서드
       */
      public static OutboxCreateResult created(Long id) {
          return new OutboxCreateResult(id, false);
      }
  
      /**
       * Outbox 레코드가 이미 존재하여 중복 처리되었음을 나타내는 팩토리 메서드
       */
      public static OutboxCreateResult duplicate(Long id) {
          return new OutboxCreateResult(id, true);
      }
  ```

- [ ] **Step 2: RedisOutboxRecord 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** Outbox 레코드 고유 ID */
      private final Long id;
      /** Outbox 이벤트 유형 */
      private final OutboxEventType eventType;
      /** 직렬화된 이벤트 데이터 페이로드 */
      private final String payload;
      /** 이벤트 추적용 Trace ID */
      private final String traceId;
      /** 아웃박스 동기화 상태 */
      private final OutboxStatus status;
      /** 동기화 재시도 횟수 */
      private final Integer retryCount;
      /** 아웃박스 레코드 생성 시각 */
      private final LocalDateTime createdAt;
      /** 아웃박스 상태 최종 변경 시각 */
      private final LocalDateTime statusUpdatedAt;
  ```

- [ ] **Step 3: AppPolicyOutboxPayload 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 회선 ID */
      private Long lineId;
      /** 애플리케이션 ID */
      private Integer appId;
      /** 정책 버전 번호 */
      private Long version;
  ```

- [ ] **Step 4: ImmediateBlockOutboxPayload 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 회선 ID */
      private Long lineId;
      /** 차단 해제 예정 Epoch 초 단위 시각 */
      private Long blockEndEpochSecond;
      /** 차단 정책 버전 */
      private Long version;
  ```

- [ ] **Step 5: InFlightDedupeDeleteOutboxPayload 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 멱등 키 제거 식별 고유 UUID */
      private String uuid;
      /** 원본 트래픽 로그 레코드 ID */
      private String sourceRecordId;
      /** 삭제 요청 등록 Epoch 밀리초 시각 */
      private Long requestedAtEpochMillis;
  ```

- [ ] **Step 6: LineLimitOutboxPayload 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 회선 ID */
      private Long lineId;
      /** 회선 일일 기본 한도 (Byte 단위) */
      private Long dailyLimit;
      /** 일일 한도 차감 정책의 활성화 상태 */
      private Boolean isDailyActive;
      /** 회선 공유 풀 한도 (Byte 단위) */
      private Long sharedLimit;
      /** 공유 풀 한도 차감 정책의 활성화 상태 */
      private Boolean isSharedActive;
      /** 제한 정보 설정 버전 */
      private Long version;
  ```

- [ ] **Step 7: LineScopedOutboxPayload 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 회선 ID */
      private Long lineId;
      /** 회선 범위 동기화 설정 버전 */
      private Long version;
  ```

- [ ] **Step 8: PolicyActivationOutboxPayload 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 활성화/비활성화 대상 정책 ID */
      private Long policyId;
      /** 정책의 활성화 상태 값 */
      private Boolean active;
      /** 정책 버전 정보 */
      private Long version;
  ```

- [ ] **Step 9: SharedPoolContributionOutboxPayload 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 회선 ID */
      private Long lineId;
      /** 가족(공유 풀) ID */
      private Long familyId;
      /** 공유 풀 기여 데이터양 (Byte 단위) */
      private Long amount;
      /** 개인 회선 무제한 요금제 적용 여부 */
      private Boolean individualUnlimited;
      /** 기여도가 반영될 대상 년월 */
      private String targetMonth;
      /** 기여도가 반영될 구체적 대상 날짜 */
      private String usageDate;
  ```

- [ ] **Step 10: SharedPoolThresholdOutboxPayload 주석 보강**
  다음 내용으로 코드 수정:
  ```java
      /** 알림 식별용 UUID */
      private String uuid;
      /** 가족 ID */
      private Long familyId;
      /** 공유 풀 경고 임계 비율 (예: 80%의 경우 0.8) */
      private Double thresholdPct;
      /** 정책 적용 년월 */
      private String targetMonth;
      /** 임계치 동기화 요청 Epoch 밀리초 시각 */
      private Long createdAtEpochMillis;
  ```

- [ ] **Step 11: 컴파일 검증 및 Diff 확인**
  프로덕션 컴파일 실행: `./gradlew compileJava`
  컴파일 결과: SUCCESS
  로직 변화가 없는지 `git diff`를 통해 검증하고, 변경사항을 커밋 대기합니다.

---

## Verification Plan

### Automated Tests
- 각 단계가 끝날 때마다 빌드 컴파일을 수행하여 컴파일 에러 발생 여부를 엄격히 확인합니다.
  `./gradlew compileJava`
- 도메인 단위의 기본 테스트 코드들이 정상적으로 작동하는지 빌드 전체 테스트를 수행합니다.
  `./gradlew test`

### Manual Verification
- `git diff`를 통해 기존 소스 코드 파일(Java)의 비즈니스 로직에 어떤 기능적 변화도 발생하지 않았음을 교차 확인합니다.
- `git status`를 수행하여 리팩토링 대상 파일 28개 이외의 불필요한 설정 파일 등이 수정되지 않았는지 확인합니다.
