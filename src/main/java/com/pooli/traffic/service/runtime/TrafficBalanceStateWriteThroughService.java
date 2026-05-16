package com.pooli.traffic.service.runtime;

import java.time.YearMonth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Family DB 변경 후 family meta Redis 캐시에 필요한 write-through를 수행합니다.
 *
 * <p>실시간 잔량 차감은 통합 Lua가 remaining hash에서 처리합니다. 이 서비스는 기여/임계치 변경처럼
 * Family 도메인에서 발생한 메타데이터 변경만 commit 이후 캐시에 반영합니다.
 */
@Slf4j
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficBalanceStateWriteThroughService {

    private final TrafficFamilyMetaCacheService trafficFamilyMetaCacheService;
    private final TrafficRemainingBalanceCacheService trafficRemainingBalanceCacheService;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    /**
     * 공유풀 기여 성공 후 현재월 Redis 잔량 snapshot이 있는 경우에만 개인/공유 amount를 보정합니다.
     *
     * <p>snapshot key가 없으면 RDB source 변경만 유지하고 다음 조회 hydrate가 최신 source를 적재합니다.
     * write-through 실행 중 Redis 오류가 나면 호출자에게 전파해 Redis-Only 화면 정합성 문제를 숨기지 않습니다.
     */
    public void markSharedPoolContribution(
            long lineId,
            long familyId,
            long amount,
            boolean individualUnlimited
    ) {
        if (lineId <= 0 || familyId <= 0 || amount <= 0) {
            return;
        }

        executeAfterCommitStrict(
                "shared_pool_contribution lineId=" + lineId + " familyId=" + familyId + " amount=" + amount,
                () -> {
                    YearMonth targetMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId());
                    if (!individualUnlimited) {
                        String individualBalanceKey =
                                trafficRedisKeyFactory.remainingIndivAmountKey(lineId, targetMonth);
                        trafficRemainingBalanceCacheService.incrementAmountIfPresent(
                                individualBalanceKey,
                                -amount
                        );
                    }

                    String sharedBalanceKey = trafficRedisKeyFactory.remainingSharedAmountKey(familyId, targetMonth);
                    trafficRemainingBalanceCacheService.incrementAmountIfPresent(sharedBalanceKey, amount);
                    trafficFamilyMetaCacheService.increasePoolTotal(familyId, amount);
                }
        );
    }

    /**
     * 공유풀 임계치 설정 변경을 family meta 캐시에 반영합니다.
     *
     * <p>임계치 변경 역시 Family DB가 기준이므로 commit 이후 캐시를 갱신합니다.
     */
    public void markSharedMetaThresholdUpdated(long familyId, long familyThreshold, boolean thresholdActive) {
        if (familyId <= 0) {
            return;
        }

        executeAfterCommit(
                "shared_meta_threshold familyId=" + familyId + " threshold=" + familyThreshold,
                () -> trafficFamilyMetaCacheService.updateThreshold(familyId, familyThreshold, thresholdActive)
        );
    }

    /**
     * 트랜잭션 경계에 맞춰 Redis write-through 실행 시점을 제어합니다.
     *
     * <p>커밋 이후 반영에 실패해도 사용자 요청 전체를 실패시키지 않도록 로그로 남기고 종료합니다.
     */
    private void executeAfterCommit(String operationName, Runnable operation) {
        Runnable wrappedOperation = () -> {
            try {
                operation.run();
            } catch (RuntimeException e) {
                log.error("traffic_balance_state_write_through_failed operation={}", operationName, e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wrappedOperation.run();
                }
            });
            return;
        }

        wrappedOperation.run();
    }

    private void executeAfterCommitStrict(String operationName, Runnable operation) {
        Runnable wrappedOperation = () -> {
            try {
                operation.run();
            } catch (RuntimeException e) {
                log.error("traffic_balance_state_write_through_failed operation={}", operationName, e);
                throw e;
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wrappedOperation.run();
                }
            });
            return;
        }

        wrappedOperation.run();
    }
}
