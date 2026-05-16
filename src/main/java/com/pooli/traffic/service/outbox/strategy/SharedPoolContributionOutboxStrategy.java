package com.pooli.traffic.service.outbox.strategy;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.pooli.family.service.FamilySharedPoolContributionRedisFirstService;
import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.domain.outbox.OutboxRetryResult;
import com.pooli.traffic.domain.outbox.RedisOutboxRecord;

import lombok.RequiredArgsConstructor;

/**
 * 공유풀 기여 Redis-first Outbox 복구 전략입니다.
 */
@Component
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class SharedPoolContributionOutboxStrategy implements OutboxEventRetryStrategy {

    private final FamilySharedPoolContributionRedisFirstService contributionRedisFirstService;

    @Override
    public OutboxEventType supports() {
        return OutboxEventType.SHARED_POOL_CONTRIBUTION;
    }

    @Override
    public OutboxRetryResult execute(RedisOutboxRecord record) {
        return contributionRedisFirstService.recover(record);
    }
}
