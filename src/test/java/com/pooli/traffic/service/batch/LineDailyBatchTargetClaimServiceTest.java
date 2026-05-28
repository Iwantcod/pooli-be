package com.pooli.traffic.service.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
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

    /**
     * 유효하지 않은 workerId가 DB claim query로 전달되지 않는지 검증한다.
     */
    @Test
    @DisplayName("workerId가 null이거나 비어 있으면 claim query 실행 전에 예외를 던진다")
    void rejectsInvalidWorkerIdBeforeClaimQuery() {
        IllegalArgumentException nullException = assertThrows(
                IllegalArgumentException.class,
                () -> lineDailyBatchTargetClaimService.claim(USAGE_DATE, null, LIMIT)
        );
        IllegalArgumentException emptyException = assertThrows(
                IllegalArgumentException.class,
                () -> lineDailyBatchTargetClaimService.claim(USAGE_DATE, "", LIMIT)
        );
        IllegalArgumentException blankException = assertThrows(
                IllegalArgumentException.class,
                () -> lineDailyBatchTargetClaimService.claim(USAGE_DATE, " ", LIMIT)
        );

        assertTrue(nullException.getMessage().contains("workerId"));
        assertTrue(emptyException.getMessage().contains("workerId"));
        assertTrue(blankException.getMessage().contains("workerId"));
        verify(lineDailyBatchTargetMapper, never()).selectClaimableTargetsForUpdate(
                any(),
                anyInt(),
                anyInt()
        );
        verify(lineDailyBatchTargetMapper, never()).markTargetsProcessing(any(), any());
    }

    /**
     * 양수가 아닌 limit이 DB claim query로 전달되지 않는지 검증한다.
     */
    @Test
    @DisplayName("limit이 양수가 아니면 claim query 실행 전에 예외를 던진다")
    void rejectsNonPositiveLimitBeforeClaimQuery() {
        IllegalArgumentException zeroException = assertThrows(
                IllegalArgumentException.class,
                () -> lineDailyBatchTargetClaimService.claim(USAGE_DATE, WORKER_ID, 0)
        );
        IllegalArgumentException negativeException = assertThrows(
                IllegalArgumentException.class,
                () -> lineDailyBatchTargetClaimService.claim(USAGE_DATE, WORKER_ID, -1)
        );

        assertTrue(zeroException.getMessage().contains("limit"));
        assertTrue(negativeException.getMessage().contains("limit"));
        verify(lineDailyBatchTargetMapper, never()).selectClaimableTargetsForUpdate(
                any(),
                anyInt(),
                anyInt()
        );
        verify(lineDailyBatchTargetMapper, never()).markTargetsProcessing(any(), any());
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
