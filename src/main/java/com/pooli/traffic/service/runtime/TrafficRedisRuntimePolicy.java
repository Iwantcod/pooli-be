package com.pooli.traffic.service.runtime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 트래픽 차감 Redis 키의 시간/TTL 규칙을 계산하는 정책 컴포넌트입니다.
 * 명세 고정값(Asia/Seoul, 일말+8h, 월말+10d, lock/inflight 상수)을 한 곳에서 관리합니다.
 */
@Component
@Profile({"local", "traffic", "api"})
public class TrafficRedisRuntimePolicy {

    public static final long LOCK_TTL_MS = 3000L;
    public static final long LOCK_HEARTBEAT_MS = 1000L;
    public static final long INFLIGHT_TTL_SEC = 60L;
    public static final long APP_SPEED_USED_TTL_SEC = 3L;

    private static final ZoneId ASIA_SEOUL_ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter YYYYMM_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    /**
      * `zoneId` 처리 목적에 맞는 핵심 로직을 수행합니다.
     */
    public ZoneId zoneId() {
        return ASIA_SEOUL_ZONE_ID;
    }

    /**
     * 도메인 규칙에 맞는 문자열 형식으로 변환합니다.
     */
    public String formatYyyyMmDd(LocalDate targetDate) {
        // 키 suffix(yyyymmdd) 규칙을 일관되게 유지한다.
        return Objects.requireNonNull(targetDate, "targetDate must not be null").format(YYYYMMDD_FORMATTER);
    }

    /**
     * 도메인 규칙에 맞는 문자열 형식으로 변환합니다.
     */
    public String formatYyyyMm(YearMonth targetMonth) {
        // 키 suffix(yyyymm) 규칙을 일관되게 유지한다.
        return Objects.requireNonNull(targetMonth, "targetMonth must not be null").format(YYYYMM_FORMATTER);
    }

    /**
     * 입력값과 정책을 바탕으로 최종 사용 값을 계산해 반환합니다.
     */
    public Instant resolveDailyExpireAt(LocalDate targetDate) {
        // "일말 + 8h" 규칙:
        // 1) targetDate의 일말(23:59:59.999...) 계산
        // 2) +8시간 적용
        // 3) Asia/Seoul 기준 Instant로 변환
        LocalDateTime dayEnd = Objects.requireNonNull(targetDate, "targetDate must not be null").atTime(LocalTime.MAX);
        return dayEnd.plusHours(8).atZone(ASIA_SEOUL_ZONE_ID).toInstant();
    }

    /**
     * 입력값과 정책을 바탕으로 최종 사용 값을 계산해 반환합니다.
     */
    public long resolveDailyExpireAtEpochSeconds(LocalDate targetDate) {
        // Redis EXPIREAT는 epoch seconds를 사용하므로 변환값을 제공한다.
        return resolveDailyExpireAt(targetDate).getEpochSecond();
    }

    /**
     * 입력값과 정책을 바탕으로 최종 사용 값을 계산해 반환합니다.
     */
    public Instant resolveMonthlyExpireAt(YearMonth targetMonth) {
        // "월말 + 10d" 규칙:
        // 1) targetMonth의 월말(23:59:59.999...) 계산
        // 2) +10일 적용
        // 3) Asia/Seoul 기준 Instant로 변환
        LocalDateTime monthEnd = Objects.requireNonNull(targetMonth, "targetMonth must not be null")
                .atEndOfMonth()
                .atTime(LocalTime.MAX);
        return monthEnd.plusDays(10).atZone(ASIA_SEOUL_ZONE_ID).toInstant();
    }

    /**
     * 입력값과 정책을 바탕으로 최종 사용 값을 계산해 반환합니다.
     */
    public long resolveMonthlyExpireAtEpochSeconds(YearMonth targetMonth) {
        // Redis EXPIREAT는 epoch seconds를 사용하므로 변환값을 제공한다.
        return resolveMonthlyExpireAt(targetMonth).getEpochSecond();
    }
}
