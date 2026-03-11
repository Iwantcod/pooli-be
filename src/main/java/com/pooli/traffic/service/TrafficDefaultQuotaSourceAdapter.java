package com.pooli.traffic.service;

import java.time.YearMonth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.traffic.domain.TrafficDbRefillClaimResult;
import com.pooli.traffic.domain.TrafficRefillPlan;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.mapper.TrafficRefillSourceMapper;

import lombok.RequiredArgsConstructor;

/**
 * HYDRATE/REFILL 원천 데이터를 DB에서 조회/차감하는 기본 어댑터입니다.
 * 리필량은 명세에 따라 `actual=min(requested, dbRemaining)` 계약으로 계산합니다.
 */
@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficDefaultQuotaSourceAdapter implements TrafficQuotaSourcePort {

    private final TrafficRefillSourceMapper trafficRefillSourceMapper;
    private final TrafficRecentUsageBucketService trafficRecentUsageBucketService;

    @Override
    public long loadInitialAmount(TrafficPoolType poolType, TrafficPayloadReqDto payload, YearMonth targetMonth) {
        // hydrate 시점의 원천 잔량을 DB에서 읽어 Redis 초기값으로 사용한다.
        return readRemainingAmount(poolType, payload);
    }

    @Override
    public TrafficRefillPlan resolveRefillPlan(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        return trafficRecentUsageBucketService.resolveRefillPlan(poolType, payload);
    }

    @Override
    @Transactional
    public TrafficDbRefillClaimResult claimRefillAmountFromDb(
            TrafficPoolType poolType,
            TrafficPayloadReqDto payload,
            YearMonth targetMonth,
            long requestedRefillAmount
    ) {
        long normalizedRequestedAmount = Math.max(0L, requestedRefillAmount);
        long dbRemainingBefore = normalizePositive(readRemainingAmountForUpdate(poolType, payload));
        if (normalizedRequestedAmount <= 0 || dbRemainingBefore <= 0) {
            return buildClaimResult(normalizedRequestedAmount, dbRemainingBefore, 0L, dbRemainingBefore);
        }

        long actualRefillAmount = Math.min(normalizedRequestedAmount, dbRemainingBefore);
        int updatedRows = deductRemainingAmount(poolType, payload, actualRefillAmount);
        if (updatedRows <= 0) {
            long reloadedRemaining = readRemainingAmount(poolType, payload);
            return buildClaimResult(normalizedRequestedAmount, dbRemainingBefore, 0L, reloadedRemaining);
        }

        long dbRemainingAfter = Math.max(0L, dbRemainingBefore - actualRefillAmount);
        return buildClaimResult(normalizedRequestedAmount, dbRemainingBefore, actualRefillAmount, dbRemainingAfter);
    }

    private TrafficDbRefillClaimResult buildClaimResult(
            long requestedRefillAmount,
            long dbRemainingBefore,
            long actualRefillAmount,
            long dbRemainingAfter
    ) {
        return TrafficDbRefillClaimResult.builder()
                .requestedRefillAmount(requestedRefillAmount)
                .dbRemainingBefore(dbRemainingBefore)
                .actualRefillAmount(actualRefillAmount)
                .dbRemainingAfter(dbRemainingAfter)
                .build();
    }

    private long readRemainingAmount(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        return normalizePositive(readRemainingAmountRaw(poolType, payload));
    }

    private Long readRemainingAmountForUpdate(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        if (payload == null) {
            return null;
        }

        return switch (poolType) {
            case INDIVIDUAL -> payload.getLineId() == null
                    ? null
                    : trafficRefillSourceMapper.selectIndividualRemainingForUpdate(payload.getLineId());
            case SHARED -> payload.getFamilyId() == null
                    ? null
                    : trafficRefillSourceMapper.selectSharedRemainingForUpdate(payload.getFamilyId());
        };
    }

    private Long readRemainingAmountRaw(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        if (payload == null) {
            return null;
        }

        return switch (poolType) {
            case INDIVIDUAL -> payload.getLineId() == null
                    ? null
                    : trafficRefillSourceMapper.selectIndividualRemaining(payload.getLineId());
            case SHARED -> payload.getFamilyId() == null
                    ? null
                    : trafficRefillSourceMapper.selectSharedRemaining(payload.getFamilyId());
        };
    }

    private int deductRemainingAmount(TrafficPoolType poolType, TrafficPayloadReqDto payload, long deductAmount) {
        if (deductAmount <= 0 || payload == null) {
            return 0;
        }

        return switch (poolType) {
            case INDIVIDUAL -> payload.getLineId() == null
                    ? 0
                    : trafficRefillSourceMapper.deductIndividualRemaining(payload.getLineId(), deductAmount);
            case SHARED -> payload.getFamilyId() == null
                    ? 0
                    : trafficRefillSourceMapper.deductSharedRemaining(payload.getFamilyId(), deductAmount);
        };
    }

    private long normalizePositive(Long value) {
        if (value == null || value <= 0) {
            return 0L;
        }
        return value;
    }
}
