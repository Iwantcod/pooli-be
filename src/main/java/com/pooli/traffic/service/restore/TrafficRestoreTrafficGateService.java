package com.pooli.traffic.service.restore;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Redis 장애 복구 중 traffic 생산, poll, reclaim, worker 처리를 차단할지 판단한다.
 */
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class TrafficRestoreTrafficGateService {

    private final TrafficRestorePolicyFlagService policyFlagService;

    /**
     * traffic 처리 진입을 차단해야 하면 true를 반환한다.
     */
    public boolean shouldBlockTraffic() {
        return policyFlagService.isRestoreActiveFailClosed();
    }
}
