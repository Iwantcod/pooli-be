package com.pooli.family.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.YearMonth;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import com.pooli.family.service.FamilySharedPoolContributionTransactionService.ProcessingResult;
import com.pooli.traffic.domain.outbox.OutboxCreateResult;
import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.domain.outbox.OutboxRetryResult;
import com.pooli.traffic.domain.outbox.OutboxStatus;
import com.pooli.traffic.domain.outbox.RedisOutboxRecord;
import com.pooli.traffic.domain.outbox.payload.SharedPoolContributionOutboxPayload;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

@ExtendWith(MockitoExtension.class)
class FamilySharedPoolContributionRedisFirstServiceTest {

    @Mock
    private RedisOutboxRecordService redisOutboxRecordService;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Mock
    private TrafficRedisFailureClassifier trafficRedisFailureClassifier;

    @Mock
    private FamilySharedPoolContributionTransactionService transactionService;

    private FamilySharedPoolContributionRedisFirstService service;

    @BeforeEach
    void setUp() {
        service = new FamilySharedPoolContributionRedisFirstService(
                redisOutboxRecordService,
                trafficRedisRuntimePolicy,
                trafficRedisFailureClassifier,
                transactionService
        );
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("요청 경로는 outbox 생성 후 처리 transaction을 실행한다")
    void submitRunsCreateAndProcessTransactions() {
        MDC.put("traceId", "trace-contribution-001");
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisRuntimePolicy.formatYyyyMm(any(YearMonth.class))).thenReturn("202605");
        when(transactionService.createOutbox(any(), eq("trace-contribution-001")))
                .thenReturn(OutboxCreateResult.created(101L));
        when(transactionService.processContribution(any(), any(), eq("trace-contribution-001"), eq(false)))
                .thenReturn(ProcessingResult.SUCCESS);

        boolean result = service.submit(11L, 22L, 100L, false);

        assertThat(result).isTrue();
        ArgumentCaptor<SharedPoolContributionOutboxPayload> payloadCaptor =
                ArgumentCaptor.forClass(SharedPoolContributionOutboxPayload.class);
        verify(transactionService).createOutbox(payloadCaptor.capture(), eq("trace-contribution-001"));
        verify(transactionService).processContribution(101L, payloadCaptor.getValue(), "trace-contribution-001", false);
        assertThat(payloadCaptor.getValue().getTargetMonth()).isEqualTo("202605");
    }

    @Test
    @DisplayName("기존 접수 outbox가 있으면 처리 transaction을 반복하지 않는다")
    void submitDuplicateSkipsProcessing() {
        MDC.put("traceId", "trace-duplicate");
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisRuntimePolicy.formatYyyyMm(any(YearMonth.class))).thenReturn("202605");
        when(transactionService.createOutbox(any(), eq("trace-duplicate")))
                .thenReturn(OutboxCreateResult.duplicate());

        boolean result = service.submit(11L, 22L, 100L, false);

        assertThat(result).isFalse();
        verify(transactionService, never()).processContribution(any(), any(), any(), eq(false));
    }

    @Test
    @DisplayName("즉시 재시도 3회가 모두 실패하면 retry_count를 3까지 기록하고 복구 대상으로 남긴다")
    void submitLeavesOutboxWhenImmediateRetriesExhausted() {
        MDC.put("traceId", "trace-lock-busy");
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisRuntimePolicy.formatYyyyMm(any(YearMonth.class))).thenReturn("202605");
        when(transactionService.createOutbox(any(), eq("trace-lock-busy")))
                .thenReturn(OutboxCreateResult.created(102L));
        when(transactionService.processContribution(any(), any(), eq("trace-lock-busy"), eq(false)))
                .thenReturn(ProcessingResult.RETRYABLE_FAILURE);

        boolean result = service.submit(11L, 22L, 100L, false);

        assertThat(result).isFalse();
        verify(redisOutboxRecordService).markFailWithRetryCount(102L, 1);
        verify(redisOutboxRecordService).markFailWithRetryCount(102L, 2);
        verify(redisOutboxRecordService).markFailWithRetryCount(102L, 3);
    }

    @Test
    @DisplayName("스케줄러 복구는 저장된 payload로 처리 transaction을 위임한다")
    void recoverDelegatesToProcessTransaction() {
        RedisOutboxRecord record = record();
        SharedPoolContributionOutboxPayload payload = payload();
        when(redisOutboxRecordService.readPayload(record, SharedPoolContributionOutboxPayload.class))
                .thenReturn(payload);
        when(transactionService.processContribution(201L, payload, "trace-recover", true))
                .thenReturn(ProcessingResult.SUCCESS);

        OutboxRetryResult result = service.recover(record);

        assertThat(result).isEqualTo(OutboxRetryResult.SUCCESS);
        verify(transactionService).processContribution(201L, payload, "trace-recover", true);
    }

    private SharedPoolContributionOutboxPayload payload() {
        return SharedPoolContributionOutboxPayload.builder()
                .lineId(11L)
                .familyId(22L)
                .amount(100L)
                .individualUnlimited(false)
                .targetMonth("202605")
                .usageDate("2026-05-16")
                .build();
    }

    private RedisOutboxRecord record() {
        return RedisOutboxRecord.builder()
                .id(201L)
                .eventType(OutboxEventType.SHARED_POOL_CONTRIBUTION)
                .traceId("trace-recover")
                .status(OutboxStatus.PROCESSING)
                .retryCount(1)
                .build();
    }
}
