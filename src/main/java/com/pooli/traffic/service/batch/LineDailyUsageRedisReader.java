package com.pooli.traffic.service.batch;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.batch.LineDailyBatchTarget;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

import lombok.RequiredArgsConstructor;

/**
 * worker가 선점한 LINE_DAILY_BATCH_TARGET row에 대응하는 일별 사용량 Redis key들을 읽는다.
 *
 * <p>이 클래스의 책임은 Redis 조회와 Redis hash/string 값을 배치 처리용 read result로 변환하는 것까지만이다.
 * MySQL insert, target row DONE/SKIPPED 전환, metadata count 증가는 후속 트랜잭션 처리 단계의 책임이다.
 */
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class LineDailyUsageRedisReader {

    static final String DAILY_SHARED_USAGE_AMOUNT_FIELD = "usage_amount";
    static final String DAILY_SHARED_FAMILY_ID_FIELD = "family_id";

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;

    /**
     * target row의 line_id와 usage_date를 기준으로 세 종류의 일별 사용량 Redis key를 조회한다.
     *
     * <p>처리 순서:
     * 1. target row에서 usage_date와 line_id를 꺼낸다.
     * 2. 일별 총 사용량 string key를 읽는다.
     * 3. 앱별 사용량 hash key를 읽고 app_id 단위로 개인/공유/QoS 사용량을 묶는다.
     * 4. 공유풀 일별 사용량 hash key를 읽고 family_id와 usage_amount를 묶는다.
     * 5. 세 조회 결과를 하나의 read result로 반환한다.
     */
    public LineDailyUsageReadResult read(LineDailyBatchTarget target) {
        LocalDate usageDate = target.getUsageDate();
        Long lineId = target.getLineId();

        Long totalUsageData = readDailyTotalUsage(lineId, usageDate);
        List<DailyAppUsage> appUsages = readDailyAppUsages(lineId, usageDate);
        DailySharedUsage sharedUsage = readDailySharedUsage(lineId, usageDate);

        return new LineDailyUsageReadResult(totalUsageData, appUsages, sharedUsage);
    }

    /**
     * `pooli:daily_total_usage:{lineId}:{yyyymmdd}` string counter를 읽는다.
     *
     * <p>key가 없으면 해당 line의 일별 총 사용량이 없는 것으로 보고 null을 반환한다.
     * 값이 있으면 MySQL insert 단계에서 그대로 사용할 long 값으로 변환한다.
     */
    private Long readDailyTotalUsage(Long lineId, LocalDate usageDate) {
        String key = trafficRedisKeyFactory.dailyTotalUsageKey(lineId, usageDate);
        String rawValue = cacheStringRedisTemplate.opsForValue().get(key);
        if (rawValue == null) {
            return null;
        }
        return Long.parseLong(rawValue);
    }

    /**
     * `pooli:daily_app_usage:{lineId}:{yyyymmdd}` hash를 읽어 앱별 사용량 목록으로 변환한다.
     *
     * <p>처리 순서:
     * 1. Redis hash 전체 field를 조회한다.
     * 2. hash가 비어 있으면 앱별 사용량이 없는 것으로 보고 빈 목록을 반환한다.
     * 3. `app:{appId}:{source}` field에서 app_id와 source를 파싱한다.
     * 4. 같은 app_id의 individual/shared/qos 값을 하나의 accumulator에 합산한다.
     * 5. accumulator를 MySQL 앱별 사용량 insert에 필요한 read result 목록으로 변환한다.
     */
    private List<DailyAppUsage> readDailyAppUsages(Long lineId, LocalDate usageDate) {
        String key = trafficRedisKeyFactory.dailyAppUsageKey(lineId, usageDate);
        Map<Object, Object> entries = cacheStringRedisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return List.of();
        }

        Map<Integer, DailyAppUsageAccumulator> appUsageByAppId = new HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String[] field = parseDailyAppUsageField(String.valueOf(entry.getKey()));
            int appId = Integer.parseInt(field[0]);
            String source = field[1];
            long amount = Long.parseLong(String.valueOf(entry.getValue()));
            DailyAppUsageAccumulator usage =
                    appUsageByAppId.computeIfAbsent(appId, ignored -> new DailyAppUsageAccumulator());
            addDailyAppUsage(usage, source, amount);
        }

        List<DailyAppUsage> appUsages = new ArrayList<>(appUsageByAppId.size());
        for (Map.Entry<Integer, DailyAppUsageAccumulator> entry : appUsageByAppId.entrySet()) {
            DailyAppUsageAccumulator usage = entry.getValue();
            appUsages.add(new DailyAppUsage(
                    entry.getKey(),
                    usage.individualUsageData,
                    usage.sharedUsageData,
                    usage.qosUsageData
            ));
        }
        return List.copyOf(appUsages);
    }

    /**
     * `pooli:daily_shared_usage:{lineId}:{yyyymmdd}` hash를 읽어 공유풀 일별 사용량으로 변환한다.
     *
     * <p>hash가 없으면 공유풀 사용량이 없는 것으로 보고 null을 반환한다.
     * hash가 있으면 `usage_amount`와 `family_id`가 모두 있어야 한다.
     * 둘 중 하나라도 없으면 Redis 적재 계약이 깨진 상태이므로 조용히 SKIPPED/DONE으로 넘기지 않고 실패로 드러낸다.
     */
    private DailySharedUsage readDailySharedUsage(Long lineId, LocalDate usageDate) {
        String key = trafficRedisKeyFactory.dailySharedUsageKey(lineId, usageDate);
        Map<Object, Object> entries = cacheStringRedisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return null;
        }

        Object rawUsageAmount = entries.get(DAILY_SHARED_USAGE_AMOUNT_FIELD);
        Object rawFamilyId = entries.get(DAILY_SHARED_FAMILY_ID_FIELD);
        if (rawUsageAmount == null || rawFamilyId == null) {
            throw new IllegalStateException("Daily shared usage hash is missing required fields. key=" + key);
        }

        return new DailySharedUsage(
                Long.parseLong(String.valueOf(rawFamilyId)),
                Long.parseLong(String.valueOf(rawUsageAmount))
        );
    }

    /**
     * 앱 사용량 hash field 이름을 `app_id`와 source로 분해한다.
     *
     * <p>허용 형식은 `app:{appId}:individual`, `app:{appId}:shared`, `app:{appId}:qos`이다.
     * prefix나 토큰 수가 다르면 Redis field 계약 위반이므로 즉시 실패시킨다.
     */
    private String[] parseDailyAppUsageField(String rawField) {
        String[] tokens = rawField.split(":");
        if (tokens.length != 3 || !"app".equals(tokens[0])) {
            throw new IllegalStateException("Invalid daily app usage field. field=" + rawField);
        }
        return new String[] {tokens[1], tokens[2]};
    }

    /**
     * 파싱된 앱 사용량 source에 맞는 accumulator field에 사용량을 더한다.
     *
     * <p>지원 source는 individual/shared/qos 세 가지뿐이다.
     * 다른 source는 이후 DB 컬럼에 매핑할 수 없으므로 실패로 처리한다.
     */
    private void addDailyAppUsage(DailyAppUsageAccumulator usage, String source, long amount) {
        switch (source) {
            case "individual" -> usage.individualUsageData += amount;
            case "shared" -> usage.sharedUsageData += amount;
            case "qos" -> usage.qosUsageData += amount;
            default -> throw new IllegalStateException("Invalid daily app usage source. source=" + source);
        }
    }

}
