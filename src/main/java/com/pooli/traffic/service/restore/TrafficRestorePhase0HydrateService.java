package com.pooli.traffic.service.restore;

import java.time.YearMonth;
import java.util.Objects;

import lombok.NonNull;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.restore.TrafficRestoreHydrateTarget;
import com.pooli.traffic.service.policy.TrafficPolicyBootstrapService;
import com.pooli.traffic.service.runtime.TrafficBalanceSnapshotHydrateService;

import lombok.RequiredArgsConstructor;

/**
 * Redis 복구 phase 0 target을 실제 Redis hydrate 작업으로 실행한다.
 */
@Service
@RequiredArgsConstructor
public class TrafficRestorePhase0HydrateService {

    private final TrafficBalanceSnapshotHydrateService balanceSnapshotHydrateService;
    private final TrafficPolicyBootstrapService policyBootstrapService;

    /**
     * target type에 맞는 기존 hydrate 서비스를 호출한다.
     */
    public void hydrate(@NonNull TrafficRestoreHydrateTarget target) {
        YearMonth targetMonth = YearMonth.from(target.getTargetMonthStart());
        switch (target.getTargetType()) {
            case LINE -> balanceSnapshotHydrateService.hydrateIndividualSnapshot(target.getTargetOwnerId(), targetMonth);
            case FAMILY -> balanceSnapshotHydrateService.hydrateSharedSnapshot(target.getTargetOwnerId(), targetMonth);
            case GLOBAL_POLICY -> policyBootstrapService.hydrateOnDemand();
        }
    }
}
