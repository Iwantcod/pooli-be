package com.pooli.traffic.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.pooli.common.config.AppRedisProperties;

import lombok.RequiredArgsConstructor;

/**
 * 트래픽 처리에서 사용하는 Redis 키를 명세 규칙대로 생성하는 팩토리입니다.
 * app.redis.namespace를 앞에 붙여 환경별 키 충돌을 방지합니다.
 */
@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficRedisKeyFactory {

    private final AppRedisProperties appRedisProperties;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    public String policyKey(long policyId) {
        return namespaced("policy:" + policyId);
    }

    public String dailyTotalLimitKey(long lineId) {
        return namespaced("daily_total_limit:" + lineId);
    }

    public String appDataDailyLimitKey(long lineId) {
        return namespaced("app_data_daily_limit:" + lineId);
    }

    public String appSpeedLimitKey(long lineId) {
        return namespaced("app_speed_limit:" + lineId);
    }

    public String appWhitelistKey(long lineId) {
        return namespaced("app_whitelist:" + lineId);
    }

    public String monthlySharedLimitKey(long lineId) {
        return namespaced("monthly_shared_limit:" + lineId);
    }

    public String dailyTotalUsageKey(long lineId, LocalDate targetDate) {
        // 일별 집계 키는 yyyymmdd suffix를 사용한다.
        String yyyymmdd = trafficRedisRuntimePolicy.formatYyyyMmDd(targetDate);
        return namespaced("daily_total_usage:" + lineId + ":" + yyyymmdd);
    }

    public String dailyAppUsageKey(long lineId, LocalDate targetDate) {
        // 일별 집계 키는 yyyymmdd suffix를 사용한다.
        String yyyymmdd = trafficRedisRuntimePolicy.formatYyyyMmDd(targetDate);
        return namespaced("daily_app_usage:" + lineId + ":" + yyyymmdd);
    }

    public String monthlySharedUsageKey(long lineId, YearMonth targetMonth) {
        // 월별 집계 키는 yyyymm suffix를 사용한다.
        String yyyymm = trafficRedisRuntimePolicy.formatYyyyMm(targetMonth);
        return namespaced("monthly_shared_usage:" + lineId + ":" + yyyymm);
    }

    public String immediatelyBlockEndKey(long lineId) {
        return namespaced("immediately_block_end:" + lineId);
    }

    public String repeatBlockKey(long lineId) {
        return namespaced("repeat_block:" + lineId);
    }

    public String remainingIndivAmountKey(long lineId, YearMonth targetMonth) {
        // 잔량 해시 키는 월 단위(yyyymm)로 관리한다.
        String yyyymm = trafficRedisRuntimePolicy.formatYyyyMm(targetMonth);
        return namespaced("remaining_indiv_amount:" + lineId + ":" + yyyymm);
    }

    public String remainingSharedAmountKey(long familyId, YearMonth targetMonth) {
        // 잔량 해시 키는 월 단위(yyyymm)로 관리한다.
        String yyyymm = trafficRedisRuntimePolicy.formatYyyyMm(targetMonth);
        return namespaced("remaining_shared_amount:" + familyId + ":" + yyyymm);
    }

    public String indivRefillLockKey(long lineId) {
        return namespaced("indiv_refill_lock:" + lineId);
    }

    public String sharedRefillLockKey(long familyId) {
        return namespaced("shared_refill_lock:" + familyId);
    }

    public String qosKey(long lineId) {
        return namespaced("qos:" + lineId);
    }

    public String speedBucketIndividualKey(long lineId, long epochSecond) {
        // 속도 버킷은 초 단위로 키를 분리한다.
        return namespaced("speed_bucket:individual:" + lineId + ":" + epochSecond);
    }

    public String dedupeRunKey(String traceId) {
        // traceId는 필수이므로 빈 문자열은 허용하지 않는다.
        String normalizedTraceId = Objects.requireNonNull(traceId, "traceId must not be null").trim();
        if (normalizedTraceId.isEmpty()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        return namespaced("dedupe:run:" + normalizedTraceId);
    }

    private String namespaced(String keyBody) {
        // namespace가 비어 있으면 원본 키를 그대로 사용한다.
        String namespace = appRedisProperties.getNamespace();
        if (namespace == null || namespace.isBlank()) {
            return keyBody;
        }
        return namespace + ":" + keyBody;
    }
}
