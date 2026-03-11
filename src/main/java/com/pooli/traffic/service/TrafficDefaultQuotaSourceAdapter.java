package com.pooli.traffic.service;

import java.time.YearMonth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficPoolType;

/**
 * HYDRATE/REFILL 원천 데이터의 임시 기본 어댑터입니다.
 * DB 연동 전까지는 apiTotalData를 기준으로 초기량/리필량/임계치를 계산합니다.
 */
@Component
@Profile({"local", "traffic"})
public class TrafficDefaultQuotaSourceAdapter implements TrafficQuotaSourcePort {

    @Override
    public long loadInitialAmount(TrafficPoolType poolType, TrafficPayloadReqDto payload, YearMonth targetMonth) {
        // 임시 정책:
        // DB hydrate가 아직 연결되지 않았으므로 apiTotalData를 초기 잔량으로 사용한다.
        return normalizePositive(payload == null ? null : payload.getApiTotalData());
    }

    @Override
    public long resolveRefillUnit(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        // 명세 fallback 규칙:
        // delta 정보가 없을 때는 apiTotalData를 refill unit으로 사용한다.
        return normalizePositive(payload == null ? null : payload.getApiTotalData());
    }

    @Override
    public long resolveRefillThreshold(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        // 명세 fallback 규칙:
        // delta 정보가 없을 때는 apiTotalData의 30%를 threshold로 사용한다.
        long refillUnit = resolveRefillUnit(poolType, payload);
        long threshold = Math.round(refillUnit * 0.3d);
        return Math.max(1L, threshold);
    }

    private long normalizePositive(Long value) {
        if (value == null || value <= 0) {
            return 0L;
        }
        return value;
    }
}
