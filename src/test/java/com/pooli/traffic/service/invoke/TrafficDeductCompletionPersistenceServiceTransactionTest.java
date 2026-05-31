package com.pooli.traffic.service.invoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.service.outbox.TrafficInFlightDedupeDeleteOutboxService;

/**
 * {@link TrafficDeductCompletionPersistenceService}의 DB 트랜잭션 경계를 실제 Spring proxy와 DB 상태로 검증합니다.
 *
 * <p>done log 저장은 실제 서비스를 사용하고, outbox 생성 단계만 mock으로 실패시켜
 * 두 작업이 같은 트랜잭션 안에서 원자적으로 롤백되는지 확인합니다.</p>
 */
@Tag("local-only")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "app.streams.consumer-enabled=false"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class TrafficDeductCompletionPersistenceServiceTransactionTest {

    @Autowired
    private TrafficDeductCompletionPersistenceService trafficDeductCompletionPersistenceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private TrafficInFlightDedupeDeleteOutboxService trafficInFlightDedupeDeleteOutboxService;

    private String traceId;

    /**
     * 테스트별 traceId를 분리해 기존 local-only 데이터와 충돌하지 않게 준비합니다.
     */
    @BeforeEach
    void setUp() {
        traceId = "completion-tx-" + UUID.randomUUID();
        deleteRowsByTraceId();
    }

    /**
     * 실패 검증 후에도 같은 traceId의 잔여 데이터가 남지 않도록 정리합니다.
     */
    @AfterEach
    void tearDown() {
        deleteRowsByTraceId();
    }

    /**
     * done log insert 이후 outbox 생성이 실패할 때 전체 트랜잭션이 롤백되는지 검증합니다.
     */
    @Test
    @DisplayName("outbox 생성 실패 시 같은 트랜잭션의 done log 저장도 롤백한다")
    void rollsBackDoneLogWhenDeferredOutboxCreationFails() {
        TrafficPayloadReqDto payload = payload(traceId);
        TrafficDeductResultResDto result = result(traceId);

        // done log 저장은 실제 DB에 시도되고, 다음 outbox 단계에서 예외가 발생하도록 경계를 만든다.
        when(trafficInFlightDedupeDeleteOutboxService.createPendingDeferred(traceId, "tx-1"))
                .thenThrow(new RuntimeException("outbox failed"));

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> trafficDeductCompletionPersistenceService.persistCompletion(payload, result, "tx-1", 10L)
        );

        // outbox 예외가 호출부로 전파되면서 선행 done log insert도 commit되지 않아야 한다.
        assertEquals("outbox failed", thrown.getMessage());
        assertEquals(0L, countRows("TRAFFIC_DEDUCT_DONE"));
        assertEquals(0L, countRows("TRAFFIC_REDIS_OUTBOX"));
    }

    /**
     * persistCompletion 입력에 필요한 최소 traffic payload를 구성합니다.
     */
    private TrafficPayloadReqDto payload(String traceId) {
        return TrafficPayloadReqDto.builder()
                .traceId(traceId)
                .lineId(11L)
                .familyId(22L)
                .appId(33)
                .apiTotalData(100L)
                .enqueuedAt(1_700_000_000_000L)
                .build();
    }

    /**
     * done log insert가 가능한 성공 차감 결과를 구성합니다.
     */
    private TrafficDeductResultResDto result(String traceId) {
        LocalDateTime now = LocalDateTime.now();
        return TrafficDeductResultResDto.builder()
                .traceId(traceId)
                .apiTotalData(100L)
                .deductedIndividualBytes(70L)
                .deductedSharedBytes(30L)
                .deductedQosBytes(0L)
                .apiRemainingData(0L)
                .finalStatus(TrafficFinalStatus.SUCCESS)
                .lastLuaStatus(TrafficLuaStatus.OK)
                .createdAt(now)
                .finishedAt(now)
                .build();
    }

    /**
     * rollback 검증 대상 테이블에서 현재 테스트 traceId의 row 수를 조회합니다.
     */
    private long countRows(String tableName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE trace_id = ?",
                Long.class,
                traceId
        );
        return count == null ? 0L : count;
    }

    /**
     * 외래키 관계가 없더라도 후속 검증 혼선을 막기 위해 outbox, done log 순서로 삭제합니다.
     */
    private void deleteRowsByTraceId() {
        if (traceId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM TRAFFIC_REDIS_OUTBOX WHERE trace_id = ?", traceId);
        jdbcTemplate.update("DELETE FROM TRAFFIC_DEDUCT_DONE WHERE trace_id = ?", traceId);
    }
}
