# 8번 전역 정책 연동 및 복구 배치 플래그 DB-Redis 순차 갱신 구현 계획서

장애 복구 전역 플래그 정책(`policy_id = 8`)을 시스템 전역 정책으로 정식 편입하고, 복구 배치 오케스트레이터 제어 흐름에서 MySQL 상태 테이블을 선행 업데이트한 후 Redis에 동기화/갱신하여 데이터 정합성을 확보합니다.

## User Review Required

> [!IMPORTANT]
> **MySQL 선행 업데이트 방식**
> 복구 배치 시작(`activateRestoreFlag`) 및 종료(`deactivateRestoreFlag`) 시 MySQL `POLICY` 테이블의 `is_active` 값을 각각 `true` / `false`로 먼저 변경한 뒤, Redis 데이터를 갱신합니다. 이로 인해 DB 상태와 캐시 상태의 일관성이 항상 보장됩니다.
>
> **차감 전 preflight 키 검증 범위 확장**
> 8번 정책 키도 필수 전역 정책으로 간주되어, `TrafficDeductOrchestratorService` 사전 검사 리스트에 추가됩니다. 이로 인해 평시 트래픽 처리 중에도 8번 키 누락 시 자동으로 복구 배치 락 기반 동기화(`hydrate`)가 실행됩니다.

## Open Questions

> [!NOTE]
> 추가 확인이 필요한 개방형 질문은 없으며, 이전 답변을 통해 설계 방향에 대한 최종 합의가 완료되었습니다.

---

## Proposed Changes

### 1. Database & Policy Mapper Component

MySQL `POLICY` 테이블에 대한 단건 업데이트 쿼리를 추가하고, 스냅샷 목록 조회 시 8번 정책도 포함하도록 수정합니다.

#### [MODIFY] [PolicyBackOfficeMapper.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/policy/mapper/PolicyBackOfficeMapper.java)
* 복구 프로세스 상태 갱신을 위해 `updatePolicyActiveStatus` 메서드 선언을 추가합니다.
  ```java
  int updatePolicyActiveStatus(@Param("policyId") int policyId, @Param("isActive") boolean isActive);
  ```

#### [MODIFY] [PolicyBackOfficeMapper.xml](file:///Users/kjh/Documents/pooli-be/src/main/resources/mapper/policy/PolicyBackOfficeMapper.xml)
* `selectPolicyActivationSnapshot` 쿼리에서 `WHERE policy_id != 8` 조건을 제거합니다.
* `updatePolicyActiveStatus` 갱신 쿼리를 새로 추가합니다.
  ```xml
  <update id="updatePolicyActiveStatus">
      UPDATE POLICY
      SET is_active = #{isActive},
          updated_at = CURRENT_TIMESTAMP(6)
      WHERE policy_id = #{policyId}
  </update>
  ```

---

### 2. Traffic Policy & Deduct Component

필수 전역 정책 리스트와 preflight 확인 키 목록에 8번 정책 정보를 포함시킵니다.

#### [MODIFY] [TrafficPolicyBootstrapService.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/service/policy/TrafficPolicyBootstrapService.java)
* `REQUIRED_POLICY_MAPPING` 상수에 `entry(8, "TRAFFIC_RESTORE_IN_PROGRESS")`를 등록합니다.

#### [MODIFY] [TrafficDeductOrchestratorService.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/service/decision/TrafficDeductOrchestratorService.java)
* `GLOBAL_POLICY_IDS`에 `8L`을 추가합니다.
  ```java
  private static final List<Long> GLOBAL_POLICY_IDS = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
  ```

---

### 3. Restore Lifecycle Control Component

복구 배치 플래그 활성화/비활성화 시점에 MySQL 갱신과 Redis 직접 쓰기를 차례로 실행합니다.

#### [MODIFY] [TrafficRestorePolicyFlagService.java](file:///Users/kjh/Documents/pooli-be/src/main/java/com/pooli/traffic/service/restore/TrafficRestorePolicyFlagService.java)
* `PolicyBackOfficeMapper` 의존성을 생성자로 주입받습니다.
* `activateRestoreFlag()` 메서드를 수정하여 MySQL 상태 값을 `true`로 선행 갱신한 후, 전체 동기화 및 Redis 직접 입력을 수행합니다.
* `deactivateRestoreFlag()` 메서드를 수정하여 MySQL 상태 값을 `false`로 선행 갱신한 후, Redis 직접 입력을 수행합니다.

---

### 4. Tests Component

전역 정책 개수 증가와 상태 업데이트에 대한 단위/통합 테스트 검증 코드를 갱신합니다.

#### [MODIFY] [TrafficPolicyBootstrapServiceTest.java](file:///Users/kjh/Documents/pooli-be/src/test/java/com/pooli/traffic/service/policy/TrafficPolicyBootstrapServiceTest.java)
* `allPolicySnapshots()` 헬퍼 메서드에 `snapshot(8, true, now)`를 추가하여 필수 정책 8개 존재 여부를 검증하는 로직과 모의 객체 일관성을 유지합니다.

#### [MODIFY] [TrafficRestorePolicyFlagServiceTest.java](file:///Users/kjh/Documents/pooli-be/src/test/java/com/pooli/traffic/service/restore/TrafficRestorePolicyFlagServiceTest.java)
* `PolicyBackOfficeMapper` 모의 객체를 주입하고, `activateRestoreFlag()` 및 `deactivateRestoreFlag()` 동작 시 mapper의 `updatePolicyActiveStatus`가 호출되는지 검증하는 테스트 케이스를 보완 및 추가합니다.

---

## Verification Plan

### Automated Tests
필수 정책 개수 추가 및 DB 순차 갱신 로직이 기존 로직의 다른 영역을 깨뜨리지 않는지 단위/통합 테스트로 빌드 검증합니다.
* **전체 단위 테스트 빌드 검증:**
  ```bash
  ./gradlew test
  ```
* **정책 연동 관련 특정 테스트 실행:**
  ```bash
  ./gradlew test --tests "com.pooli.traffic.service.policy.TrafficPolicyBootstrapServiceTest"
  ./gradlew test --tests "com.pooli.traffic.service.restore.TrafficRestorePolicyFlagServiceTest"
  ```

### Manual Verification
* `TrafficRestoreOrchestratorService`를 통해 복구 배치 제어 흐름이 수행될 때 MySQL 데이터베이스 레코드(`is_active` 값 변화)와 Redis hash 값의 동기화 순서 및 일관성이 올바르게 맞아떨어지는지 디버그 로그와 mock/H2 테스트 결과 분석을 통해 교차 확인합니다.
