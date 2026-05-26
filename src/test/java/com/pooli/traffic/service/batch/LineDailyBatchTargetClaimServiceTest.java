package com.pooli.traffic.service.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.batch.LineDailyBatchTarget;
import com.pooli.traffic.domain.batch.LineDailyBatchTargetStatus;
import com.pooli.traffic.mapper.LineDailyBatchTargetMapper;

@ExtendWith(MockitoExtension.class)
class LineDailyBatchTargetClaimServiceTest {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 25);
    private static final String WORKER_ID = "worker-1";
    private static final int LIMIT = 100;

    @Mock
    private LineDailyBatchTargetMapper lineDailyBatchTargetMapper;

    @InjectMocks
    private LineDailyBatchTargetClaimService lineDailyBatchTargetClaimService;

    @Test
    @DisplayName("claim 대상 row를 조회한 뒤 같은 트랜잭션에서 PROCESSING으로 전환한다")
    void claimsTargetsAndMarksProcessing() {
        LineDailyBatchTarget first = target(1L);
        LineDailyBatchTarget second = target(2L);
        when(lineDailyBatchTargetMapper.selectClaimableTargetsForUpdate(
                USAGE_DATE,
                LineDailyBatchTargetClaimService.PROCESSING_LEASE_TIMEOUT_SECONDS,
                LIMIT
        )).thenReturn(List.of(first, second));

        List<LineDailyBatchTarget> claimed =
                lineDailyBatchTargetClaimService.claim(USAGE_DATE, WORKER_ID, LIMIT);

        assertEquals(List.of(first, second), claimed);
        verify(lineDailyBatchTargetMapper).markTargetsProcessing(List.of(1L, 2L), WORKER_ID);
    }

    @Test
    @DisplayName("claim 대상 row가 없으면 PROCESSING 전환 SQL을 실행하지 않는다")
    void doesNotMarkProcessingWhenNoTargetsClaimed() {
        when(lineDailyBatchTargetMapper.selectClaimableTargetsForUpdate(
                USAGE_DATE,
                LineDailyBatchTargetClaimService.PROCESSING_LEASE_TIMEOUT_SECONDS,
                LIMIT
        )).thenReturn(List.of());

        List<LineDailyBatchTarget> claimed =
                lineDailyBatchTargetClaimService.claim(USAGE_DATE, WORKER_ID, LIMIT);

        assertEquals(List.of(), claimed);
        verify(lineDailyBatchTargetMapper, never()).markTargetsProcessing(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private LineDailyBatchTarget target(Long id) {
        return LineDailyBatchTarget.builder()
                .id(id)
                .usageDate(USAGE_DATE)
                .lineId(id + 100L)
                .status(LineDailyBatchTargetStatus.PENDING)
                .retryCount(0)
                .build();
    }
}
