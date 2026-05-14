package com.pooli.traffic.service.runtime;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.TrafficBalanceSnapshotHydrateResult;
import com.pooli.traffic.domain.TrafficIndividualBalanceSnapshot;
import com.pooli.traffic.domain.TrafficSharedBalanceSnapshot;
import com.pooli.traffic.mapper.TrafficRefillSourceMapper;

import lombok.RequiredArgsConstructor;

/**
 * 개인/공유 RDB source를 Redis 월별 잔량 snapshot으로 적재하는 공용 hydrate 서비스입니다.
 *
 * <p>차감 stream과 화면 조회 경로가 함께 사용합니다. 이 클래스는 RDB snapshot 조회, stale month refresh,
 * Redis hash 적재, owner 단위 hydrate lock만 담당하고, 차감 Lua 재시도/metrics/API fallback 정책은 호출자가 결정합니다.
 */
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficBalanceSnapshotHydrateService {

    private static final long QOS_UPLOAD_MULTIPLIER = 125L;

    private final TrafficRefillSourceMapper trafficRefillSourceMapper;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final TrafficRemainingBalanceCacheService trafficRemainingBalanceCacheService;
    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;
    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;

    /**
     * 개인 회선의 월별 잔량/QoS snapshot을 hydrate합니다.
     *
     * <p>유효성 검증 후 lineId 단위 lock을 획득한 worker만 RDB source 조회와 Redis 적재를 수행합니다.
     */
    public TrafficBalanceSnapshotHydrateResult hydrateIndividualSnapshot(Long lineId, YearMonth targetMonth) {
        if (lineId == null || lineId <= 0 || targetMonth == null) {
            return TrafficBalanceSnapshotHydrateResult.invalidOwner();
        }

        return hydrateWithLock(
                trafficRedisKeyFactory.indivHydrateLockKey(lineId),
                () -> hydrateIndividualSnapshotUnlocked(lineId, targetMonth)
        );
    }

    /**
     * 가족 공유풀의 월별 잔량 snapshot을 hydrate합니다.
     *
     * <p>유효성 검증 후 familyId 단위 lock을 획득한 worker만 RDB source 조회와 Redis 적재를 수행합니다.
     */
    public TrafficBalanceSnapshotHydrateResult hydrateSharedSnapshot(Long familyId, YearMonth targetMonth) {
        if (familyId == null || familyId <= 0 || targetMonth == null) {
            return TrafficBalanceSnapshotHydrateResult.invalidOwner();
        }

        return hydrateWithLock(
                trafficRedisKeyFactory.sharedHydrateLockKey(familyId),
                () -> hydrateSharedSnapshotUnlocked(familyId, targetMonth)
        );
    }

    /**
     * lock 보유 상태에서 개인 snapshot을 조회하고, 준비된 snapshot이면 Redis hash에 amount/qos를 적재합니다.
     */
    private TrafficBalanceSnapshotHydrateResult hydrateIndividualSnapshotUnlocked(
            Long lineId,
            YearMonth targetMonth
    ) {
        SnapshotDecision<TrafficIndividualBalanceSnapshot> decision =
                resolveIndividualSnapshot(lineId, targetMonth);
        if (decision.status() != SnapshotStatus.READY) {
            return toHydrateResult(decision.status());
        }

        TrafficIndividualBalanceSnapshot snapshot = decision.snapshot();
        String balanceKey = trafficRedisKeyFactory.remainingIndivAmountKey(lineId, targetMonth);
        trafficRemainingBalanceCacheService.hydrateIndividualSnapshot(
                balanceKey,
                snapshot.getAmount() == null ? 0L : snapshot.getAmount(),
                resolveQosSpeedLimit(snapshot),
                trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth)
        );
        return TrafficBalanceSnapshotHydrateResult.hydrated();
    }

    /**
     * lock 보유 상태에서 공유 snapshot을 조회하고, 준비된 snapshot이면 Redis hash에 amount를 적재합니다.
     */
    private TrafficBalanceSnapshotHydrateResult hydrateSharedSnapshotUnlocked(
            Long familyId,
            YearMonth targetMonth
    ) {
        SnapshotDecision<TrafficSharedBalanceSnapshot> decision = resolveSharedSnapshot(familyId, targetMonth);
        if (decision.status() != SnapshotStatus.READY) {
            return toHydrateResult(decision.status());
        }

        TrafficSharedBalanceSnapshot snapshot = decision.snapshot();
        String balanceKey = trafficRedisKeyFactory.remainingSharedAmountKey(familyId, targetMonth);
        trafficRemainingBalanceCacheService.hydrateSharedSnapshot(
                balanceKey,
                snapshot.getAmount() == null ? 0L : snapshot.getAmount(),
                trafficRedisRuntimePolicy.resolveMonthlyExpireAtEpochSeconds(targetMonth)
        );
        return TrafficBalanceSnapshotHydrateResult.hydrated();
    }

    /**
     * owner 단위 Redis lock을 감싼 공통 실행부입니다.
     *
     * <p>lock 획득 실패는 호출자의 retry 정책으로 넘기고, 획득한 lock은 Lua compare-and-delete로 해제합니다.
     */
    private TrafficBalanceSnapshotHydrateResult hydrateWithLock(
            String lockKey,
            SnapshotHydrateAction hydrateAction
    ) {
        String lockValue = "hydrate:" + UUID.randomUUID();
        if (!tryAcquireHydrateLock(lockKey, lockValue)) {
            return TrafficBalanceSnapshotHydrateResult.notReady();
        }

        try {
            return hydrateAction.hydrate();
        } finally {
            trafficLuaScriptInfraService.executeLockRelease(lockKey, lockValue);
        }
    }

    /**
     * 짧은 TTL의 Redis lock을 선점합니다.
     *
     * <p>lock value는 해제 시 소유자 확인에 사용되며, Redis 응답이 true일 때만 획득으로 인정합니다.
     */
    private boolean tryAcquireHydrateLock(String lockKey, String lockValue) {
        Boolean acquired = cacheStringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockValue,
                Duration.ofMillis(TrafficRedisRuntimePolicy.LOCK_TTL_MS)
        );
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 개인 RDB source snapshot을 targetMonth 기준으로 판정합니다.
     *
     * <p>과거월 snapshot이면 조건부 월초 refresh 후 한 번 재조회하여 다른 worker와의 경합도 수렴시킵니다.
     */
    private SnapshotDecision<TrafficIndividualBalanceSnapshot> resolveIndividualSnapshot(
            Long lineId,
            YearMonth targetMonth
    ) {
        TrafficIndividualBalanceSnapshot snapshot =
                trafficRefillSourceMapper.selectIndividualBalanceSnapshot(lineId);
        SnapshotDecision<TrafficIndividualBalanceSnapshot> initialDecision =
                decideSnapshot(snapshot, targetMonth);
        if (initialDecision.status() != SnapshotStatus.STALE) {
            return initialDecision;
        }

        trafficRefillSourceMapper.refreshIndividualBalanceIfBeforeTargetMonth(
                lineId,
                targetMonth.atDay(1).atStartOfDay()
        );
        return decideSnapshot(
                trafficRefillSourceMapper.selectIndividualBalanceSnapshot(lineId),
                targetMonth
        );
    }

    /**
     * 공유 RDB source snapshot을 targetMonth 기준으로 판정합니다.
     *
     * <p>과거월 snapshot이면 조건부 월초 refresh 후 한 번 재조회하여 Redis에 적재 가능한 상태인지 다시 판정합니다.
     */
    private SnapshotDecision<TrafficSharedBalanceSnapshot> resolveSharedSnapshot(Long familyId, YearMonth targetMonth) {
        TrafficSharedBalanceSnapshot snapshot =
                trafficRefillSourceMapper.selectSharedBalanceSnapshot(familyId);
        SnapshotDecision<TrafficSharedBalanceSnapshot> initialDecision =
                decideSnapshot(snapshot, targetMonth);
        if (initialDecision.status() != SnapshotStatus.STALE) {
            return initialDecision;
        }

        trafficRefillSourceMapper.refreshSharedBalanceIfBeforeTargetMonth(
                familyId,
                targetMonth.atDay(1).atStartOfDay()
        );
        return decideSnapshot(
                trafficRefillSourceMapper.selectSharedBalanceSnapshot(familyId),
                targetMonth
        );
    }

    /**
     * RDB snapshot의 마지막 refresh 월과 요청 월을 비교해 hydrate 가능 여부를 결정합니다.
     */
    private <T> SnapshotDecision<T> decideSnapshot(T snapshot, YearMonth targetMonth) {
        LocalDateTime lastBalanceRefreshedAt = extractLastBalanceRefreshedAt(snapshot);
        if (snapshot == null || lastBalanceRefreshedAt == null) {
            return SnapshotDecision.snapshotNotFound();
        }

        YearMonth refreshedMonth = YearMonth.from(lastBalanceRefreshedAt);
        if (refreshedMonth.isAfter(targetMonth)) {
            return SnapshotDecision.staleTargetMonth();
        }
        if (refreshedMonth.isBefore(targetMonth)) {
            return SnapshotDecision.stale();
        }
        return SnapshotDecision.ready(snapshot);
    }

    /**
     * 내부 snapshot 판정값을 외부 호출자가 해석할 hydrate 결과 타입으로 변환합니다.
     */
    private TrafficBalanceSnapshotHydrateResult toHydrateResult(SnapshotStatus status) {
        return switch (status) {
            case STALE -> TrafficBalanceSnapshotHydrateResult.notReady();
            case SNAPSHOT_NOT_FOUND -> TrafficBalanceSnapshotHydrateResult.snapshotNotFound();
            case STALE_TARGET_MONTH -> TrafficBalanceSnapshotHydrateResult.staleTargetMonth();
            case READY -> TrafficBalanceSnapshotHydrateResult.hydrated();
        };
    }

    /**
     * 개인/공유 snapshot 타입에서 공통 판정 기준인 lastBalanceRefreshedAt을 추출합니다.
     */
    private LocalDateTime extractLastBalanceRefreshedAt(Object snapshot) {
        if (snapshot instanceof TrafficIndividualBalanceSnapshot individualSnapshot) {
            return individualSnapshot.getLastBalanceRefreshedAt();
        }
        if (snapshot instanceof TrafficSharedBalanceSnapshot sharedSnapshot) {
            return sharedSnapshot.getLastBalanceRefreshedAt();
        }
        return null;
    }

    /**
     * DB에 저장된 QoS 속도 단위를 Redis 차감 Lua가 사용하는 byte 단위 한도로 변환합니다.
     */
    private long resolveQosSpeedLimit(TrafficIndividualBalanceSnapshot snapshot) {
        Long rawQosSpeedLimit = snapshot == null ? null : snapshot.getQosSpeedLimit();
        if (rawQosSpeedLimit == null || rawQosSpeedLimit < 0) {
            return 0L;
        }
        return rawQosSpeedLimit * QOS_UPLOAD_MULTIPLIER;
    }

    /**
     * snapshot 객체와 판정 상태를 함께 운반해 null snapshot과 실패 사유를 명확히 구분합니다.
     */
    private record SnapshotDecision<T>(T snapshot, SnapshotStatus status) {
        /**
         * targetMonth에 맞는 snapshot이 준비되어 Redis hydrate를 진행할 수 있음을 표시합니다.
         */
        static <T> SnapshotDecision<T> ready(T snapshot) {
            return new SnapshotDecision<>(snapshot, SnapshotStatus.READY);
        }

        /**
         * snapshot이 targetMonth보다 과거라 refresh 후 재판정이 필요함을 표시합니다.
         */
        static <T> SnapshotDecision<T> stale() {
            return new SnapshotDecision<>(null, SnapshotStatus.STALE);
        }

        /**
         * RDB source가 없거나 refresh 기준 시간이 없어 hydrate source로 사용할 수 없음을 표시합니다.
         */
        static <T> SnapshotDecision<T> snapshotNotFound() {
            return new SnapshotDecision<>(null, SnapshotStatus.SNAPSHOT_NOT_FOUND);
        }

        /**
         * 요청 월이 RDB source의 refresh 월보다 과거라 현재 source로 되돌릴 수 없음을 표시합니다.
         */
        static <T> SnapshotDecision<T> staleTargetMonth() {
            return new SnapshotDecision<>(null, SnapshotStatus.STALE_TARGET_MONTH);
        }
    }

    private enum SnapshotStatus {
        READY,
        STALE,
        SNAPSHOT_NOT_FOUND,
        STALE_TARGET_MONTH
    }

    @FunctionalInterface
    private interface SnapshotHydrateAction {
        /**
         * hydrate lock을 보유한 상태에서 실제 snapshot 조회와 Redis 적재를 수행합니다.
         */
        TrafficBalanceSnapshotHydrateResult hydrate();
    }
}
