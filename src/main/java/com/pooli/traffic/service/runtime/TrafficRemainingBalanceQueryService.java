package com.pooli.traffic.service.runtime;

import java.time.YearMonth;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.TrafficBalanceSnapshotHydrateResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 가족/개인 조회 API에서 사용할 Redis amount-only 잔량 조회 서비스입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrafficRemainingBalanceQueryService {

    private final ObjectProvider<TrafficRedisKeyFactory> trafficRedisKeyFactoryProvider;
    private final ObjectProvider<TrafficRedisRuntimePolicy> trafficRedisRuntimePolicyProvider;
    private final ObjectProvider<TrafficRemainingBalanceCacheService> trafficRemainingBalanceCacheServiceProvider;
    private final ObjectProvider<TrafficBalanceSnapshotHydrateService> trafficBalanceSnapshotHydrateServiceProvider;

    /**
     * 개인 회선의 현재월 Redis amount를 조회합니다.
     *
     * <p>Redis amount가 없으면 공용 hydrate 서비스를 호출한 뒤 같은 key를 다시 읽습니다.
     */
    public Long resolveIndividualActualRemaining(Long lineId) {
        return resolveActualRemaining(
                lineId,
                (trafficRedisKeyFactory, targetMonth, ownerId) ->
                        trafficRedisKeyFactory.remainingIndivAmountKey(ownerId, targetMonth),
                (hydrateService, targetMonth, ownerId) ->
                        hydrateService.hydrateIndividualSnapshot(ownerId, targetMonth)
        );
    }

    /**
     * 가족 공유풀의 현재월 Redis amount를 조회합니다.
     *
     * <p>개인 잔량과 동일한 amount-only 계약을 사용하되 familyId 기준 key와 hydrate 경로를 선택합니다.
     */
    public Long resolveSharedActualRemaining(Long familyId) {
        return resolveActualRemaining(
                familyId,
                (trafficRedisKeyFactory, targetMonth, ownerId) ->
                        trafficRedisKeyFactory.remainingSharedAmountKey(ownerId, targetMonth),
                (hydrateService, targetMonth, ownerId) ->
                        hydrateService.hydrateSharedSnapshot(ownerId, targetMonth)
        );
    }

    /**
     * owner 타입별 key 생성/hydrate 전략을 받아 Redis amount-only 조회 흐름을 공통 처리합니다.
     *
     * <p>Redis 관련 bean이 없거나 hydrate/재조회가 실패하면 DB fallback 없이 null을 반환합니다.
     */
    private Long resolveActualRemaining(
            Long ownerId,
            BalanceKeyResolver balanceKeyResolver,
            BalanceHydrator balanceHydrator
    ) {
        if (ownerId == null || ownerId <= 0) {
            return null;
        }

        TrafficRedisKeyFactory trafficRedisKeyFactory = trafficRedisKeyFactoryProvider.getIfAvailable();
        TrafficRedisRuntimePolicy trafficRedisRuntimePolicy = trafficRedisRuntimePolicyProvider.getIfAvailable();
        TrafficRemainingBalanceCacheService trafficRemainingBalanceCacheService = trafficRemainingBalanceCacheServiceProvider.getIfAvailable();
        if (trafficRedisKeyFactory == null
                || trafficRedisRuntimePolicy == null
                || trafficRemainingBalanceCacheService == null) {
            return null;
        }

        YearMonth targetMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        String balanceKey = balanceKeyResolver.resolve(trafficRedisKeyFactory, targetMonth, ownerId);
        try {
            Optional<Long> cachedAmount = trafficRemainingBalanceCacheService.readAmount(balanceKey);
            if (cachedAmount.isPresent()) {
                return normalizeAmount(cachedAmount.get());
            }

            TrafficBalanceSnapshotHydrateService hydrateService =
                    trafficBalanceSnapshotHydrateServiceProvider.getIfAvailable();
            TrafficBalanceSnapshotHydrateResult hydrateResult = hydrateService == null
                    ? TrafficBalanceSnapshotHydrateResult.notReady()
                    : balanceHydrator.hydrate(hydrateService, targetMonth, ownerId);
            if (!hydrateResult.isHydrated()) {
                return null;
            }

            return trafficRemainingBalanceCacheService.readAmount(balanceKey)
                    .map(this::normalizeAmount)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("traffic_remaining_balance_query_redis_failed key={} ownerId={}", balanceKey, ownerId, e);
            return null;
        }
    }

    /**
     * Redis amount 표현을 API 표시 계약에 맞게 정규화합니다.
     *
     * <p>음수는 무제한 sentinel `-1`로 통일하고, 0 이상 값은 실제 잔량으로 그대로 반환합니다.
     */
    private Long normalizeAmount(Long amount) {
        if (amount == null) {
            return null;
        }
        if (amount < 0L) {
            return -1L;
        }
        return amount;
    }

    @FunctionalInterface
    private interface BalanceKeyResolver {
        /**
         * ownerId와 현재월을 기준으로 개인/공유 잔량 Redis key를 생성합니다.
         */
        String resolve(TrafficRedisKeyFactory trafficRedisKeyFactory, YearMonth targetMonth, long ownerId);
    }

    @FunctionalInterface
    private interface BalanceHydrator {
        /**
         * owner 타입에 맞는 공용 snapshot hydrate 메서드를 호출합니다.
         */
        TrafficBalanceSnapshotHydrateResult hydrate(
                TrafficBalanceSnapshotHydrateService hydrateService,
                YearMonth targetMonth,
                long ownerId
        );
    }
}
