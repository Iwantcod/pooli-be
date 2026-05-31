package com.pooli.traffic.service.restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.restore.TrafficRestoreHydrateTarget;
import com.pooli.traffic.domain.restore.TrafficRestoreHydrateTargetType;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficBalanceSnapshotHydrateService;

@ExtendWith(MockitoExtension.class)
class TrafficRestorePhase0HydrateServiceTest {

    @Mock
    private TrafficBalanceSnapshotHydrateService balanceSnapshotHydrateService;

    @Mock
    private TrafficPolicyBootstrapService policyBootstrapService;

    @InjectMocks
    private TrafficRestorePhase0HydrateService service;

    @Test
    @DisplayName("LINE target은 개인 잔량 snapshot hydrate를 수행한다")
    void hydratesLineTarget() {
        TrafficRestoreHydrateTarget target = target(TrafficRestoreHydrateTargetType.LINE, 10L);

        service.hydrate(target);

        verify(balanceSnapshotHydrateService).hydrateIndividualSnapshot(10L, YearMonth.of(2026, 5));
    }

    @Test
    @DisplayName("FAMILY target은 공유 잔량 snapshot hydrate를 수행한다")
    void hydratesFamilyTarget() {
        TrafficRestoreHydrateTarget target = target(TrafficRestoreHydrateTargetType.FAMILY, 100L);

        service.hydrate(target);

        verify(balanceSnapshotHydrateService).hydrateSharedSnapshot(100L, YearMonth.of(2026, 5));
    }

    @Test
    @DisplayName("GLOBAL_POLICY target은 전역 정책 hydrate를 수행한다")
    void hydratesGlobalPolicyTarget() {
        TrafficRestoreHydrateTarget target = target(TrafficRestoreHydrateTargetType.GLOBAL_POLICY, 0L);

        service.hydrate(target);

        verify(policyBootstrapService).hydrateOnDemand();
    }

    @Test
    @DisplayName("target이 null이면 NullPointerException이 발생한다")
    void throwsExceptionWhenTargetIsNull() {
        assertThrows(
                NullPointerException.class,
                () -> service.hydrate(null),
                "target must not be null"
        );
    }

    @Test
    @DisplayName("targetMonthStart가 null이면 NullPointerException이 발생한다")
    void throwsExceptionWhenTargetMonthStartIsNull() {
        TrafficRestoreHydrateTarget target = TrafficRestoreHydrateTarget.builder()
                .targetMonthStart(null)
                .targetType(TrafficRestoreHydrateTargetType.LINE)
                .targetOwnerId(10L)
                .build();

        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> service.hydrate(target)
        );
        assertEquals(
                "targetMonthStart must not be null for owner 10",
                exception.getMessage()
        );
    }

    private TrafficRestoreHydrateTarget target(TrafficRestoreHydrateTargetType targetType, Long ownerId) {
        return TrafficRestoreHydrateTarget.builder()
                .targetMonthStart(LocalDate.of(2026, 5, 1))
                .targetType(targetType)
                .targetOwnerId(ownerId)
                .build();
    }
}
