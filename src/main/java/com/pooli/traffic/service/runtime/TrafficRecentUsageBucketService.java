package com.pooli.traffic.service.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.TrafficRefillPlan;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.enums.TrafficPoolType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 理쒓렐 李④컧??踰꾪궥??Redis??湲곕줉?섍퀬 由ы븘 怨꾩궛媛?delta/unit/threshold)???쒓났?⑸땲??
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficRecentUsageBucketService {

    private static final long RECENT_WINDOW_SECONDS = 10L;
    private static final long SPEED_BUCKET_TTL_SECONDS = 15L;
    private static final long REFILL_UNIT_MULTIPLIER = 10L;
    private static final long THRESHOLD_NUMERATOR = 3L;
    private static final long THRESHOLD_DENOMINATOR = 10L;

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;

    /**
     * ?꾩옱 event???ㅼ젣 李④컧?됱쓣 "珥??⑥쐞 ?띾룄 踰꾪궥"??湲곕줉?⑸땲??
     *
     * <p>?숈옉 洹쒖튃:
     * 1) `usedBytes > 0`??寃쎌슦?먮쭔 湲곕줉?⑸땲??
     * 2) ? ?좏삎???곕씪 owner(lineId/familyId)瑜??좏깮?⑸땲??
     * 3) 媛숈? 珥?epochSec)濡??ㅼ뼱?ㅻ뒗 媛믪? `INCRBY`濡??꾩쟻?⑸땲??
     * 4) 踰꾪궥 ??TTL? 湲곕줉 ?쒖젏留덈떎 15珥덈줈 媛깆떊?⑸땲??
     *
     * <p>湲곕줉 ?ㅽ뙣??由ы븘 怨꾩궛 ?뺥솗?꾩뿉留??곹뼢??二쇰?濡? ?꾩껜 李④컧 ?먮쫫??以묐떒?섏? ?딄쾶
     * WARN 濡쒓렇留??④린怨??덉쇅瑜??쇳궢?덈떎.
     *
     * @param poolType 媛쒖씤/怨듭쑀 ? 援щ텇
     * @param payload ?붿껌 而⑦뀓?ㅽ듃(traceId, lineId, familyId ?ы븿)
     * @param usedBytes ?꾩옱 event ?ㅼ젣 李④컧??Byte)
     */
    public void recordUsage(TrafficPoolType poolType, TrafficPayloadReqDto payload, long usedBytes) {
        if (poolType == null || payload == null || usedBytes <= 0) {
            return;
        }

        Long ownerId = resolveOwnerId(poolType, payload);
        if (ownerId == null || ownerId <= 0) {
            return;
        }

        String bucketKey = resolveBucketKey(poolType, ownerId, Instant.now().getEpochSecond());
        if (bucketKey == null || bucketKey.isBlank()) {
            return;
        }

        try {
            Long updatedValue = cacheStringRedisTemplate.opsForValue().increment(bucketKey, usedBytes);
            if (updatedValue != null) {
                cacheStringRedisTemplate.expire(bucketKey, Duration.ofSeconds(SPEED_BUCKET_TTL_SECONDS));
            }
        } catch (Exception e) {
            log.warn(
                    "traffic_speed_bucket_record_failed poolType={} ownerId={} usedBytes={}",
                    poolType,
                    ownerId,
                    usedBytes,
                    e
            );
        }
    }

    /**
     * 理쒖떊 踰꾪궥 ?곗씠?곕? 湲곗??쇰줈 由ы븘 怨꾪쉷(delta/unit/threshold)??怨꾩궛?⑸땲??
     *
     * <p>怨꾩궛 ?곗꽑?쒖쐞:
     * 1) 理쒓렐 10珥?踰꾪궥 吏묎퀎(RECENT_10S)
     * 2) 理쒓렐 10珥덇? 鍮꾨㈃ TTL ???꾩껜 踰꾪궥 吏묎퀎(ALL_BUCKETS)
     * 3) ????鍮꾨㈃ apiTotalData fallback(API_TOTAL_DATA)
     *
     * <p>?곗떇:
     * - delta = ceil(sum / bucketCount)
     * - refillUnit = delta * 10
     * - threshold = ceil(refillUnit * 3 / 10), 理쒖냼 1 蹂댁젙
     *
     * @param poolType 媛쒖씤/怨듭쑀 ? 援щ텇
     * @param payload ?붿껌 而⑦뀓?ㅽ듃(apiTotalData ?ы븿)
     * @return 由ы븘 ?먮떒???꾩슂??怨꾩궛 寃곌낵
     */
    public TrafficRefillPlan resolveRefillPlan(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        long apiTotalData = normalizeNonNegative(payload == null ? null : payload.getApiTotalData());
        Long ownerId = resolveOwnerId(poolType, payload);
        if (poolType == null || ownerId == null || ownerId <= 0) {
            return buildFallbackPlan(apiTotalData);
        }

        BucketAggregate recentAggregate = aggregateRecentWindow(poolType, ownerId);
        if (recentAggregate.bucketCount > 0) {
            long delta = divideCeil(recentAggregate.bucketSum, recentAggregate.bucketCount);
            long refillUnit = safeMultiply(delta, REFILL_UNIT_MULTIPLIER);
            long threshold = divideCeil(
                    safeMultiply(refillUnit, THRESHOLD_NUMERATOR),
                    THRESHOLD_DENOMINATOR
            );
            return TrafficRefillPlan.builder()
                    .delta(delta)
                    .bucketCount((int) recentAggregate.bucketCount)
                    .bucketSum(recentAggregate.bucketSum)
                    .refillUnit(refillUnit)
                    .threshold(Math.max(1L, threshold))
                    .source("RECENT_10S")
                    .build();
        }

        BucketAggregate allAggregate = aggregateAllBuckets(poolType, ownerId);
        if (allAggregate.bucketCount > 0) {
            long delta = divideCeil(allAggregate.bucketSum, allAggregate.bucketCount);
            long refillUnit = safeMultiply(delta, REFILL_UNIT_MULTIPLIER);
            long threshold = divideCeil(
                    safeMultiply(refillUnit, THRESHOLD_NUMERATOR),
                    THRESHOLD_DENOMINATOR
            );
            return TrafficRefillPlan.builder()
                    .delta(delta)
                    .bucketCount((int) allAggregate.bucketCount)
                    .bucketSum(allAggregate.bucketSum)
                    .refillUnit(refillUnit)
                    .threshold(Math.max(1L, threshold))
                    .source("ALL_BUCKETS")
                    .build();
        }

        return buildFallbackPlan(apiTotalData);
    }

    /**
     * 踰꾪궥 ?곗씠?곌? ?놁쓣 ???곸슜?섎뒗 fallback 由ы븘 怨꾪쉷???앹꽦?⑸땲??
     *
     * <p>fallback 洹쒖튃:
     * - refillUnit = max(apiTotalData, 0)
     * - threshold = ceil(refillUnit * 3 / 10), 理쒖냼 1 蹂댁젙
     *
     * @param apiTotalData ?붿껌 珥앸웾(Byte)
     * @return source=API_TOTAL_DATA ??fallback 由ы븘 怨꾪쉷
     */
    private TrafficRefillPlan buildFallbackPlan(long apiTotalData) {
        long refillUnit = Math.max(0L, apiTotalData);
        long threshold = divideCeil(
                safeMultiply(refillUnit, THRESHOLD_NUMERATOR),
                THRESHOLD_DENOMINATOR
        );
        return TrafficRefillPlan.builder()
                .delta(refillUnit)
                .bucketCount(0)
                .bucketSum(0L)
                .refillUnit(refillUnit)
                .threshold(Math.max(1L, threshold))
                .source("API_TOTAL_DATA")
                .build();
    }

    /**
     * ?꾩옱 ?쒓컖 湲곗? 理쒓렐 10珥?踰꾪궥 ??紐⑸줉??留뚮뱾怨??⑷퀎/媛쒖닔瑜?吏묎퀎?⑸땲??
     *
     * @param poolType 媛쒖씤/怨듭쑀 ? 援щ텇
     * @param ownerId lineId ?먮뒗 familyId
     * @return 吏묎퀎 寃곌낵(sum, bucketCount)
     */
    private BucketAggregate aggregateRecentWindow(TrafficPoolType poolType, long ownerId) {
        long nowSec = Instant.now().getEpochSecond();
        List<String> keys = new ArrayList<>();
        for (long i = 0; i < RECENT_WINDOW_SECONDS; i++) {
            keys.add(resolveBucketKey(poolType, ownerId, nowSec - i));
        }
        return aggregateKeys(keys);
    }

    /**
     * TTL ???⑥븘 ?덈뒗 ?꾩껜 踰꾪궥???⑦꽩 議고쉶???⑷퀎/媛쒖닔瑜?吏묎퀎?⑸땲??
     *
     * <p>理쒓렐 10珥?踰꾪궥??鍮꾩뿀???뚯쓽 2李?fallback 吏묎퀎濡??ъ슜?⑸땲??
     *
     * @param poolType 媛쒖씤/怨듭쑀 ? 援щ텇
     * @param ownerId lineId ?먮뒗 familyId
     * @return 吏묎퀎 寃곌낵(sum, bucketCount)
     */
    private BucketAggregate aggregateAllBuckets(TrafficPoolType poolType, long ownerId) {
        String pattern = resolveBucketPattern(poolType, ownerId);
        if (pattern == null || pattern.isBlank()) {
            return BucketAggregate.empty();
        }

        Set<String> keys = cacheStringRedisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return BucketAggregate.empty();
        }
        return aggregateKeys(new ArrayList<>(keys));
    }

    /**
     * ?꾨떖諛쏆? 踰꾪궥 ??紐⑸줉?먯꽌 媛믪쓣 ?쎌뼱 ?⑷퀎/媛쒖닔瑜?怨꾩궛?⑸땲??
     *
     * <p>`multiGet` 寃곌낵 以??묒닔 媛믩쭔 ?좏슚 踰꾪궥?쇰줈 媛꾩＜?⑸땲??
     * 媛믪씠 ?녾굅???뚯떛 ?ㅽ뙣, 0/?뚯닔 媛믪? 吏묎퀎?먯꽌 ?쒖쇅?⑸땲??
     *
     * @param keys 踰꾪궥 ??紐⑸줉
     * @return 吏묎퀎 寃곌낵(sum, bucketCount)
     */
    private BucketAggregate aggregateKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return BucketAggregate.empty();
        }

        List<String> values = cacheStringRedisTemplate.opsForValue().multiGet(keys);
        if (values == null || values.isEmpty()) {
            return BucketAggregate.empty();
        }

        long sum = 0L;
        long count = 0L;
        for (String value : values) {
            long parsedValue = parsePositiveLong(value);
            if (parsedValue <= 0) {
                continue;
            }
            sum += parsedValue;
            count++;
        }

        if (count <= 0) {
            return BucketAggregate.empty();
        }
        return new BucketAggregate(sum, count);
    }

    /**
     * ? ?좏삎??留욌뒗 踰꾪궥 owner ?앸퀎?먮? 諛섑솚?⑸땲??
     *
     * <p>INDIVIDUAL -> lineId, SHARED -> familyId
     *
     * @param poolType 媛쒖씤/怨듭쑀 ? 援щ텇
     * @param payload ?붿껌 而⑦뀓?ㅽ듃
     * @return ownerId(lineId/familyId), ?놁쑝硫?null
     */
    private Long resolveOwnerId(TrafficPoolType poolType, TrafficPayloadReqDto payload) {
        if (poolType == null || payload == null) {
            return null;
        }

        return switch (poolType) {
            case INDIVIDUAL -> payload.getLineId();
            case SHARED -> payload.getFamilyId();
        };
    }

    /**
     * ? ?좏삎??留욌뒗 "?⑥씪 珥?踰꾪궥 ??瑜??앹꽦?⑸땲??
     *
     * @param poolType 媛쒖씤/怨듭쑀 ? 援щ텇
     * @param ownerId lineId ?먮뒗 familyId
     * @param epochSecond ???珥?
     * @return 踰꾪궥 ??臾몄옄??
     */
    private String resolveBucketKey(TrafficPoolType poolType, long ownerId, long epochSecond) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.speedBucketIndividualKey(ownerId, epochSecond);
            case SHARED -> trafficRedisKeyFactory.speedBucketSharedKey(ownerId, epochSecond);
        };
    }

    /**
     * ? ?좏삎??留욌뒗 踰꾪궥 寃???⑦꽩(`...:*`)???앹꽦?⑸땲??
     *
     * @param poolType 媛쒖씤/怨듭쑀 ? 援щ텇
     * @param ownerId lineId ?먮뒗 familyId
     * @return ???⑦꽩 臾몄옄??
     */
    private String resolveBucketPattern(TrafficPoolType poolType, long ownerId) {
        return switch (poolType) {
            case INDIVIDUAL -> trafficRedisKeyFactory.speedBucketIndividualPattern(ownerId);
            case SHARED -> trafficRedisKeyFactory.speedBucketSharedPattern(ownerId);
        };
    }

    /**
     * ?묒닔 ?뺤닔 ?섎닓??寃곌낵瑜??щ┝(ceil)?쇰줈 怨꾩궛?⑸땲??
     *
     * <p>遺꾨え/遺꾩옄媛 0 ?댄븯??寃쎌슦 0??諛섑솚???꾩냽 怨꾩궛???덉쟾?섍쾶 ?좎??⑸땲??
     */
    private long divideCeil(long numerator, long denominator) {
        if (numerator <= 0 || denominator <= 0) {
            return 0L;
        }
        long quotient = numerator / denominator;
        long remainder = numerator % denominator;
        if (remainder == 0) {
            return quotient;
        }
        return quotient + 1L;
    }

    /**
     * Long ?ㅻ쾭?뚮줈?곕? 諛⑹뼱?섎ŉ 怨깆뀍???섑뻾?⑸땲??
     *
     * <p>怨깆뀍 踰붿쐞瑜?珥덇낵?섎㈃ Long.MAX_VALUE濡??ы솕?쒖폒 怨꾩궛 ?덉쇅瑜?諛⑹??⑸땲??
     */
    private long safeMultiply(long left, long right) {
        if (left <= 0 || right <= 0) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    /**
     * NULL/?뚯닔 媛믪쓣 0?쇰줈 蹂댁젙??non-negative 媛믪쑝濡??뺢퇋?뷀빀?덈떎.
     */
    private long normalizeNonNegative(Long value) {
        if (value == null || value <= 0) {
            return 0L;
        }
        return value;
    }

    /**
     * Redis 臾몄옄??媛믪쓣 ?묒닔 long?쇰줈 ?뚯떛?⑸땲??
     *
     * <p>鍮?媛? ?뚯떛 ?ㅽ뙣, 0/?뚯닔??紐⑤몢 0?쇰줈 諛섑솚?⑸땲??
     */
    private long parsePositiveLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }

        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 踰꾪궥 吏묎퀎(sum/count)瑜??④퍡 ?꾨떖?섍린 ?꾪븳 寃쎈웾 媛?媛앹껜?낅땲??
     */
    private record BucketAggregate(long bucketSum, long bucketCount) {
        /**
         * ?좏슚 踰꾪궥???놁쓣 ???ъ슜?섎뒗 鍮?吏묎퀎媛믪쓣 諛섑솚?⑸땲??
         */
        private static BucketAggregate empty() {
            return new BucketAggregate(0L, 0L);
        }
    }
}
