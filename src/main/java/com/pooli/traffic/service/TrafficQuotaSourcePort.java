package com.pooli.traffic.service;

import java.time.YearMonth;

import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficPoolType;

/**
 * HYDRATE/REFILL 과정에서 필요한 원천 데이터(DB 등) 조회 포트입니다.
 * 현재 단계에서는 기본 구현을 사용하고, 이후 실제 저장소 어댑터로 교체합니다.
 */
public interface TrafficQuotaSourcePort {

    long loadInitialAmount(TrafficPoolType poolType, TrafficPayloadReqDto payload, YearMonth targetMonth);

    long resolveRefillUnit(TrafficPoolType poolType, TrafficPayloadReqDto payload);

    long resolveRefillThreshold(TrafficPoolType poolType, TrafficPayloadReqDto payload);
}
