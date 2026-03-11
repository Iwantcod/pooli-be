package com.pooli.traffic.service;

import java.time.YearMonth;

import com.pooli.traffic.domain.TrafficDbRefillClaimResult;
import com.pooli.traffic.domain.TrafficRefillPlan;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficPoolType;

/**
 * HYDRATE/REFILL 과정에서 필요한 원천 데이터(DB 등) 조회 포트입니다.
 * 현재 단계에서는 기본 구현을 사용하고, 이후 실제 저장소 어댑터로 교체합니다.
 */
public interface TrafficQuotaSourcePort {

    long loadInitialAmount(TrafficPoolType poolType, TrafficPayloadReqDto payload, YearMonth targetMonth);

    TrafficRefillPlan resolveRefillPlan(TrafficPoolType poolType, TrafficPayloadReqDto payload);

    default long resolveRefillUnit(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        TrafficRefillPlan refillPlan = resolveRefillPlan(poolType, payload);
        if (refillPlan == null || refillPlan.getRefillUnit() == null || refillPlan.getRefillUnit() <= 0) {
            return 0L;
        }
        return refillPlan.getRefillUnit();
    }

    default long resolveRefillThreshold(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        TrafficRefillPlan refillPlan = resolveRefillPlan(poolType, payload);
        if (refillPlan == null || refillPlan.getThreshold() == null || refillPlan.getThreshold() <= 0) {
            return 0L;
        }
        return refillPlan.getThreshold();
    }

    /**
     * DB 원천 잔량에서 실제 리필 가능량을 차감하고 결과를 반환합니다.
     * 계약:
     * - actualRefillAmount = min(requestedRefillAmount, dbRemaining)
     * - Redis 충전은 반드시 actualRefillAmount를 사용해야 합니다.
     */
    TrafficDbRefillClaimResult claimRefillAmountFromDb(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            long requestedRefillAmount
    );
}
