package com.pooli.traffic.service.restore;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.restore.TrafficRestoreReplayCommand;
import com.pooli.traffic.domain.restore.TrafficRestoreReplayResult;
import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;

import lombok.RequiredArgsConstructor;

/**
 * restore replay Lua 실행에 필요한 Redis key와 argument를 구성한다.
 */
@Service
@RequiredArgsConstructor
public class TrafficRestoreReplayLuaExecutor {

    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;

    /**
     * replay command를 Redis Lua로 원자 적용한다.
     */
    public TrafficRestoreReplayResult replay(TrafficRestoreReplayCommand command) {
        List<String> raw = trafficLuaScriptInfraService.executeRestoreUsageReplay(
                buildKeys(command),
                buildArgs(command)
        );
        String status = raw.isEmpty() ? "ERROR" : raw.get(0);
        String message = raw.size() > 1 ? raw.get(1) : null;
        return new TrafficRestoreReplayResult(status, message);
    }

    /**
     * MySQL DONE commit 이후 남은 replay idempotency key를 삭제한다.
     */
    public void deleteIdempotencyKey(String idempotencyKey) {
        cacheStringRedisTemplate.delete(resolveIdempotencyKey(idempotencyKey));
    }

    private List<String> buildKeys(TrafficRestoreReplayCommand command) {
        return List.of(
                resolveIdempotencyKey(command.getIdempotencyKey()),
                trafficRedisKeyFactory.remainingIndivAmountKey(
                        command.getLineId(),
                        java.time.YearMonth.from(command.getUsageDate())
                ),
                command.getFamilyId() == null
                        ? "__restore:no_shared_remaining__"
                        : trafficRedisKeyFactory.remainingSharedAmountKey(
                                command.getFamilyId(),
                                java.time.YearMonth.from(command.getUsageDate())
                        ),
                trafficRedisKeyFactory.dailyTotalUsageKey(command.getLineId(), command.getUsageDate()),
                trafficRedisKeyFactory.dailyAppUsageKey(command.getLineId(), command.getUsageDate()),
                trafficRedisKeyFactory.dailySharedUsageKey(command.getLineId(), command.getUsageDate()),
                trafficRedisKeyFactory.monthlySharedUsageKey(
                        command.getLineId(),
                        java.time.YearMonth.from(command.getUsageDate())
                )
        );
    }

    private List<String> buildArgs(TrafficRestoreReplayCommand command) {
        return List.of(
                String.valueOf(command.getApplicationId()),
                String.valueOf(nullToZero(command.getIndividualUsageBytes())),
                String.valueOf(nullToZero(command.getSharedUsageBytes())),
                String.valueOf(nullToZero(command.getQosUsageBytes())),
                String.valueOf(nullToZero(command.getExpireEpochSeconds()))
        );
    }

    private String resolveIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey.contains(":restore:idempotency:")) {
            return idempotencyKey;
        }
        if (idempotencyKey.startsWith("restore:idempotency:")) {
            return trafficRedisKeyFactory.restoreIdempotencyKeyFromSuffix(
                    idempotencyKey.substring("restore:idempotency:".length())
            );
        }
        return trafficRedisKeyFactory.restoreIdempotencyKeyFromSuffix(idempotencyKey);
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
