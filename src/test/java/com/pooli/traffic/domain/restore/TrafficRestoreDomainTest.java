package com.pooli.traffic.domain.restore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrafficRestoreDomainTest {

    @Test
    @DisplayName("restore target 상태는 worker claim과 terminal 상태를 구분한다")
    void restoreTargetStatusContract() {
        assertTrue(TrafficRestoreTargetStatus.PENDING.isClaimable());
        assertTrue(TrafficRestoreTargetStatus.RETRYABLE.isClaimable());
        assertFalse(TrafficRestoreTargetStatus.PROCESSING.isTerminal());
        assertTrue(TrafficRestoreTargetStatus.DONE.isTerminal());
        assertTrue(TrafficRestoreTargetStatus.FAILED.isTerminal());
    }
}
