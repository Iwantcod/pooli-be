package com.pooli.traffic.service.runtime;

import java.time.YearMonth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * DB 잔량 상태 변경을 Redis 잔량 해시(is_empty)에 즉시 반영하는 write-through 서비스입니다.
 *
 * <p>현재는 공유풀 충전 시점에 is_empty를 0으로 되돌리는 경로를 제공합니다.
 * 트랜잭션이 존재하면 커밋 성공 이후에 반영해 DB/Redis 순서를 보장합니다.
 */
@Slf4j
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficBalanceStateWriteThroughService {

    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final TrafficQuotaCacheService trafficQuotaCacheService;
    private final TrafficFamilyMetaCacheService trafficFamilyMetaCacheService;

    /**
     * 공유풀 충전 성공 후 해당 월 잔량 키의 DB 고갈 플래그(is_empty)를 0으로 복구합니다.
     */
    public void markSharedBalanceNotEmpty(long familyId) {
        if (familyId <= 0) {
            return;
        }

        YearMonth targetMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        String balanceKey = trafficRedisKeyFactory.remainingSharedAmountKey(familyId, targetMonth);
        executeAfterCommit(
                "shared_balance_db_empty_reset familyId=" + familyId + " key=" + balanceKey,
                () -> trafficQuotaCacheService.writeDbEmptyFlag(balanceKey, false)
        );
    }

    /**
     * 공유풀 기여 성공 후 family meta 캐시의 총량/DB잔량을 함께 증가시킵니다.
     */
    public void markSharedMetaContribution(long familyId, long amount) {
        if (familyId <= 0 || amount <= 0) {
            return;
        }

        executeAfterCommit(
                "shared_meta_contribution familyId=" + familyId + " amount=" + amount,
                () -> trafficFamilyMetaCacheService.increaseTotalAndDbRemaining(familyId, amount)
        );
    }

    /**
     * 공유풀 임계치 설정 변경을 family meta 캐시에 반영합니다.
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
     * 공유풀 DB claim 성공 후 family meta 캐시의 DB 잔량을 감소시킵니다.
     */
    public void markSharedMetaClaimed(long familyId, long amount) {
        if (familyId <= 0 || amount <= 0) {
            return;
        }

        executeAfterCommit(
                "shared_meta_claim familyId=" + familyId + " amount=" + amount,
                () -> trafficFamilyMetaCacheService.decreaseDbRemaining(familyId, amount)
        );
    }

    /**
     * 공유풀 DB restore 성공 후 family meta 캐시의 DB 잔량을 복구합니다.
     */
    public void markSharedMetaRestored(long familyId, long amount) {
        if (familyId <= 0 || amount <= 0) {
            return;
        }

        executeAfterCommit(
                "shared_meta_restore familyId=" + familyId + " amount=" + amount,
                () -> trafficFamilyMetaCacheService.increaseDbRemaining(familyId, amount)
        );
    }

    /**
     * DB fallback 공유풀 차감 성공 후 family meta 캐시의 DB 잔량을 감소시킵니다.
     */
    public void markSharedMetaDbFallbackDeducted(long familyId, long amount) {
        if (familyId <= 0 || amount <= 0) {
            return;
        }

        executeAfterCommit(
                "shared_meta_db_fallback_deduct familyId=" + familyId + " amount=" + amount,
                () -> trafficFamilyMetaCacheService.decreaseDbRemaining(familyId, amount)
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
}
