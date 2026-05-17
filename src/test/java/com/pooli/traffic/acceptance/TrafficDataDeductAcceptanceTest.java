package com.pooli.traffic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;

class TrafficDataDeductAcceptanceTest extends TrafficAcceptanceTestSupport {

    @Test
    @DisplayName("[M1-SMOKE] 개인풀 hydrate 후 Redis-only 차감 완료 로그를 기록한다")
    /*
     * 테스트 시나리오:
     * 1. 현재 월 기준으로 개인풀 RDB source 잔량 200, 공유풀 RDB source 잔량 100을 준비한다.
     * 2. API로 50 bytes 차감 요청을 enqueue하고 실제 stream consumer가 처리할 때까지 기다린다.
     * 3. done log에는 개인풀 50 bytes 차감, 공유풀/QoS 0 bytes, 최종 SUCCESS/OK가 기록되어야 한다.
     * 4. Redis 개인풀 잔량과 usage counter만 갱신되고, RDB source 잔량은 차감되지 않아야 한다.
     */
    void shouldDeductIndividualBalanceThroughStreamConsumer() throws Exception {
        long lineId = LINE_ID_1;
        long familyId = FAMILY_ID_1;
        int appId = fixtureIds.appId();

        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(familyId, DEFAULT_SHARED_SOURCE_BYTES);

        String traceId = enqueueTrafficRequest(lineId, familyId, appId, 50L);

        TrafficDeductDoneLog doneLog = assertDoneLog(
                traceId,
                50L,
                0L,
                0L,
                0L,
                "SUCCESS",
                "OK"
        );

        await("individual balance is decremented in Redis", () -> readIndividualBalanceAmount(lineId) == 150L);
        assertThat(readSharedBalanceAmount(familyId)).isEqualTo(0L);
        assertThat(readDailyTotalUsage(lineId)).isEqualTo(50L);
        assertThat(readDailyAppUsage(lineId, appId)).isEqualTo(50L);
        assertThat(readMonthlySharedUsage(lineId)).isEqualTo(0L);
        assertThat(readLineSourceTotalData(lineId)).isEqualTo(DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        assertThat(readFamilySourcePoolTotalData(familyId)).isEqualTo(DEFAULT_SHARED_SOURCE_BYTES);
        assertThat(doneLog.getLineId()).isEqualTo(lineId);
        assertThat(doneLog.getFamilyId()).isEqualTo(familyId);
        assertThat(doneLog.getAppId()).isEqualTo(appId);
    }
}
