package com.pooli.traffic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.pooli.traffic.domain.dto.response.TrafficGenerateResDto;

/**
 * 트래픽 요청 API의 controller validation과 enqueue 이후 consumer 계약을 검증하는 인수테스트입니다.
 *
 * <p>요청이 stream에 들어가기 전에 막혀야 하는 입력과, stream에 들어간 뒤 hydrate/consumer 단계에서 처리되는
 * 입력을 구분해 API 경계의 책임을 문서화합니다.</p>
 */
class TrafficDataApiAcceptanceTest extends TrafficAcceptanceTestSupport {

    /**
     * 음수 사용량은 controller validation 책임으로 거부되고 비동기 처리 경로를 만들지 않아야 함을 검증합니다.
     */
    @Test
    @DisplayName("[API-01] 음수 apiTotalData는 controller validation에서 거부되고 stream에 적재되지 않는다")
    void shouldRejectNegativeApiTotalDataBeforeStreamEnqueue() throws Exception {
        assertBadRequestWithoutStreamEnqueue("""
                {
                  "lineId": %d,
                  "familyId": %d,
                  "appId": %d,
                  "apiTotalData": -1
                }
                """.formatted(LINE_ID_1, FAMILY_ID_1, fixtureIds.appId()));
    }

    /**
     * 필수 식별자인 lineId가 없으면 요청 생성 자체가 실패해야 함을 검증합니다.
     */
    @Test
    @DisplayName("[API-02] lineId 누락 요청은 controller validation에서 거부되고 stream에 적재되지 않는다")
    void shouldRejectMissingLineIdBeforeStreamEnqueue() throws Exception {
        assertBadRequestWithoutStreamEnqueue("""
                {
                  "familyId": %d,
                  "appId": %d,
                  "apiTotalData": 10
                }
                """.formatted(FAMILY_ID_1, fixtureIds.appId()));
    }

    /**
     * 잘못된 familyId 값은 stream consumer가 아니라 controller validation에서 차단해야 함을 검증합니다.
     */
    @Test
    @DisplayName("[API-03] 음수 familyId는 controller validation에서 거부되고 stream에 적재되지 않는다")
    void shouldRejectNegativeFamilyIdBeforeStreamEnqueue() throws Exception {
        assertBadRequestWithoutStreamEnqueue("""
                {
                  "lineId": %d,
                  "familyId": -1,
                  "appId": %d,
                  "apiTotalData": 10
                }
                """.formatted(LINE_ID_1, fixtureIds.appId()));
    }

    /**
     * 잘못된 appId 값이 downstream 차감 로직에 전달되지 않는 API 입력 계약을 검증합니다.
     */
    @Test
    @DisplayName("[API-04] 음수 appId는 controller validation에서 거부되고 stream에 적재되지 않는다")
    void shouldRejectNegativeAppIdBeforeStreamEnqueue() throws Exception {
        assertBadRequestWithoutStreamEnqueue("""
                {
                  "lineId": %d,
                  "familyId": %d,
                  "appId": -1,
                  "apiTotalData": 10
                }
                """.formatted(LINE_ID_1, FAMILY_ID_1));
    }

    /**
     * API validation을 통과한 식별자가 hydrate 단계에서 유효하지 않게 판정될 때 DLQ로 종결되는 경로를 검증합니다.
     */
    @Test
    @DisplayName("[API-05] 삭제된 line 식별자는 enqueue 후 hydrate 단계에서 SNAPSHOT_NOT_FOUND DLQ로 종결한다")
    void shouldRouteDeletedLineIdentifierToDlqAfterEnqueue() throws Exception {
        long lineId = LINE_ID_12;
        long familyId = FAMILY_ID_3;
        markLineDeleted(lineId);

        String traceId = enqueueTrafficRequestThroughApi(lineId, familyId, fixtureIds.appId(), 10L);

        Map<String, String> dlq = awaitDlqRecord();
        assertNoDoneLog(traceId);
        assertThat(dlq.get("reason")).isEqualTo("invalid/failure result: SNAPSHOT_NOT_FOUND");
    }

    /**
     * 현재 계약상 line-family 소유 관계를 API에서 재검증하지 않고 payload familyId로 처리하는 동작을 고정합니다.
     */
    @Test
    @DisplayName("[API-06] line-family 관계 불일치는 현재 API/consumer 계약상 거부하지 않고 payload familyId 기준으로 처리한다")
    void shouldProcessMismatchedLineAndFamilyByPayloadFamilyIdUnderCurrentContract() throws Exception {
        long lineId = LINE_ID_1;
        long payloadFamilyId = FAMILY_ID_2;
        int appId = fixtureIds.appId();
        setLineSourceTotalData(lineId, DEFAULT_INDIVIDUAL_SOURCE_BYTES);
        setFamilySourcePoolTotalData(payloadFamilyId, DEFAULT_SHARED_SOURCE_BYTES);
        putIndividualBalance(lineId, 0L);
        putSharedBalance(payloadFamilyId, 100L);

        String traceId = enqueueTrafficRequestThroughApi(lineId, payloadFamilyId, appId, 30L);

        assertDoneLog(traceId, 0L, 30L, 0L, 0L, "SUCCESS", "OK");
        assertThat(readIndividualBalanceAmount(lineId)).isZero();
        assertThat(readSharedBalanceAmount(payloadFamilyId)).isEqualTo(70L);
        assertThat(readMonthlySharedUsage(lineId)).isEqualTo(30L);
    }

    /**
     * bad request 응답과 함께 request/DLQ stream 모두 비어 있어야 하는 validation 실패 공통 검증입니다.
     */
    private void assertBadRequestWithoutStreamEnqueue(String requestBody) throws Exception {
        mockMvc.perform(
                        post("/api/traffic/requests")
                                .contentType("application/json")
                                .content(requestBody.getBytes(StandardCharsets.UTF_8))
                )
                .andExpect(status().isBadRequest());

        assertThat(streamRecordCount(appStreamsProperties.getKeyTrafficRequest())).isZero();
        assertThat(streamRecordCount(appStreamsProperties.getKeyTrafficDlq())).isZero();
    }

    /**
     * API를 통해 정상 요청을 넣고 이후 consumer 결과 검증에 사용할 traceId를 추출합니다.
     */
    private String enqueueTrafficRequestThroughApi(long lineId, long familyId, int appId, long apiTotalData) throws Exception {
        String requestBody = """
                {
                  "lineId": %d,
                  "familyId": %d,
                  "appId": %d,
                  "apiTotalData": %d
                }
                """.formatted(lineId, familyId, appId, apiTotalData);

        MvcResult mvcResult = mockMvc.perform(
                        post("/api/traffic/requests")
                                .contentType("application/json")
                                .content(requestBody.getBytes(StandardCharsets.UTF_8))
                )
                .andExpect(status().isOk())
                .andReturn();

        TrafficGenerateResDto response = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8),
                TrafficGenerateResDto.class
        );
        assertThat(response.getTraceId()).isNotBlank();
        return response.getTraceId();
    }

    /**
     * hydrate 실패 시나리오를 만들기 위해 acceptance fixture의 line을 논리 삭제 상태로 전환합니다.
     */
    private void markLineDeleted(long lineId) {
        int updatedRows = jdbcTemplate.update(
                """
                UPDATE LINE
                SET deleted_at = NOW(6),
                    updated_at = NOW(6)
                WHERE line_id = ?
                """,
                lineId
        );
        assertThat(updatedRows).isEqualTo(1);
    }
}
