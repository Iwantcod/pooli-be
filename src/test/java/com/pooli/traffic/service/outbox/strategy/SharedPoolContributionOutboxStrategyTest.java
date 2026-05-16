package com.pooli.traffic.service.outbox.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.family.service.FamilySharedPoolContributionRedisFirstService;
import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.domain.outbox.OutboxRetryResult;
import com.pooli.traffic.domain.outbox.RedisOutboxRecord;

@ExtendWith(MockitoExtension.class)
class SharedPoolContributionOutboxStrategyTest {

    @Mock
    private FamilySharedPoolContributionRedisFirstService contributionRedisFirstService;

    @Test
    @DisplayName("SHARED_POOL_CONTRIBUTION 이벤트를 복구 서비스로 위임한다")
    void delegatesSharedPoolContributionRecovery() {
        RedisOutboxRecord record = RedisOutboxRecord.builder()
                .id(1L)
                .eventType(OutboxEventType.SHARED_POOL_CONTRIBUTION)
                .build();
        SharedPoolContributionOutboxStrategy strategy =
                new SharedPoolContributionOutboxStrategy(contributionRedisFirstService);
        when(contributionRedisFirstService.recover(record)).thenReturn(OutboxRetryResult.SUCCESS);

        OutboxRetryResult result = strategy.execute(record);

        assertThat(strategy.supports()).isEqualTo(OutboxEventType.SHARED_POOL_CONTRIBUTION);
        assertThat(result).isEqualTo(OutboxRetryResult.SUCCESS);
        verify(contributionRedisFirstService).recover(record);
    }
}
