package com.pooli.traffic.service.restore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrafficRestoreTrafficGateServiceTest {

    @Mock
    private TrafficRestorePolicyFlagService policyFlagService;

    @InjectMocks
    private TrafficRestoreTrafficGateService trafficRestoreTrafficGateService;

    @Test
    @DisplayName("복구 flag가 활성화되면 traffic 진입을 차단한다")
    void blocksTrafficWhenRestoreFlagIsActive() {
        when(policyFlagService.isRestoreActiveFailClosed()).thenReturn(true);

        assertTrue(trafficRestoreTrafficGateService.shouldBlockTraffic());
    }

    @Test
    @DisplayName("복구 flag가 비활성화되면 traffic 진입을 허용한다")
    void allowsTrafficWhenRestoreFlagIsInactive() {
        when(policyFlagService.isRestoreActiveFailClosed()).thenReturn(false);

        assertFalse(trafficRestoreTrafficGateService.shouldBlockTraffic());
    }
}
