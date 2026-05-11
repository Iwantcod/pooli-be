package com.pooli.traffic.service.runtime;

import java.time.YearMonth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 가족/개인 조회 API에서 사용할 "실제 잔량(DB + Redis)" 계산 전용 서비스다.
 * traffic 프로필 비활성 환경에서는 Redis 조회를 생략하고 DB 값만 그대로 반환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrafficRemainingBalanceQueryService {

    private final ObjectProvider<TrafficRedisKeyFactory> trafficRedisKeyFactoryProvider;
    private final ObjectProvider<TrafficRedisRuntimePolicy> trafficRedisRuntimePolicyProvider;
    private final ObjectProvider<TrafficRemainingBalanceCacheService> trafficRemainingBalanceCacheServiceProvider;

    public Long resolveIndividualActualRemaining(Long lineId, Long dbRemaining) {
        return resolveActualRemaining(
                dbRemaining,
                lineId,
                (trafficRedisKeyFactory, targetMonth, ownerId) ->
                        trafficRedisKeyFactory.remainingIndivAmountKey(ownerId, targetMonth)
        );
    }

    public Long resolveSharedActualRemaining(Long familyId, Long dbRemaining) {
        return resolveActualRemaining(
                dbRemaining,
                familyId,
                (trafficRedisKeyFactory, targetMonth, ownerId) ->
                        trafficRedisKeyFactory.remainingSharedAmountKey(ownerId, targetMonth)
        );
    }

    private Long resolveActualRemaining(
            Long dbRemaining,
            Long ownerId,
            BalanceKeyResolver balanceKeyResolver
    ) {
        if (dbRemaining == null) {
            return null;
        }
        if (dbRemaining == -1L) {
            return -1L;
        }

        long normalizedDbRemaining = Math.max(0L, dbRemaining);
        if (ownerId == null || ownerId <= 0) {
            return normalizedDbRemaining;
        }

        TrafficRedisKeyFactory trafficRedisKeyFactory = trafficRedisKeyFactoryProvider.getIfAvailable();
        TrafficRedisRuntimePolicy trafficRedisRuntimePolicy = trafficRedisRuntimePolicyProvider.getIfAvailable();
        TrafficRemainingBalanceCacheService trafficRemainingBalanceCacheService = trafficRemainingBalanceCacheServiceProvider.getIfAvailable();
        if (trafficRedisKeyFactory == null
                || trafficRedisRuntimePolicy == null
                || trafficRemainingBalanceCacheService == null) {
            return normalizedDbRemaining;
        }

        YearMonth targetMonth = YearMonth.now(trafficRedisRuntimePolicy.zoneId());
        String balanceKey = balanceKeyResolver.resolve(trafficRedisKeyFactory, targetMonth, ownerId);
        try {
            long redisRemaining = Math.max(0L, trafficRemainingBalanceCacheService.readAmountOrDefault(balanceKey, 0L));
            return safeAdd(normalizedDbRemaining, redisRemaining);
        } catch (RuntimeException e) {
            log.warn("traffic_remaining_balance_query_redis_failed key={} ownerId={}", balanceKey, ownerId, e);
            return normalizedDbRemaining;
        }
    }

    private long safeAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    @FunctionalInterface
    private interface BalanceKeyResolver {
        String resolve(TrafficRedisKeyFactory trafficRedisKeyFactory, YearMonth targetMonth, long ownerId);
    }
}
