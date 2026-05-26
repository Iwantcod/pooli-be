package com.pooli.traffic.service.batch;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 모든 traffic 서버에서 매일 03:00 KST에 실행되어 Redis lock으로 manager 1대를 선출한다.
 */
@Slf4j
@Component
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class LineDailyBatchManagerScheduler {

    static final long MANAGER_LOCK_TTL_MS = 10 * 60 * 1000L;

    @Qualifier("cacheStringRedisTemplate")
    private final StringRedisTemplate cacheStringRedisTemplate;
    private final TrafficRedisKeyFactory trafficRedisKeyFactory;
    private final TrafficLuaScriptInfraService trafficLuaScriptInfraService;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final LineDailyBatchManagerService lineDailyBatchManagerService;

    /**
     * 매일 03:00 KST scheduler 진입점이다.
     * 1. KST 기준 전일을 동기화 대상 usageDate로 계산한다.
     * 2. 실제 manager 선출 절차는 테스트 가능한 내부 메서드에 위임한다.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void runDailyBatchManagerSchedule() {
        LocalDate usageDate = LocalDate.now(trafficRedisRuntimePolicy.zoneId()).minusDays(1);
        runForUsageDate(usageDate);
    }

    /**
     * 특정 usageDate에 대해 manager 선출을 1회 시도한다.
     * 1. 고정 Redis lock key와 이번 실행의 owner id를 준비한다.
     * 2. Redis NX lock 획득에 실패하면 manager 작업 없이 종료한다.
     * 3. lock 획득 서버만 manager 진입점을 실행하고, finally에서 owner 비교 release를 시도한다.
     */
    void runForUsageDate(LocalDate usageDate) {
        String lockKey = trafficRedisKeyFactory.lineDailyBatchManagerLockKey();
        String managerInstanceId = buildManagerInstanceId(usageDate);
        boolean lockAcquired = tryAcquireManagerLock(lockKey, managerInstanceId);
        if (!lockAcquired) {
            log.info("line_daily_batch_manager_lock_skipped usageDate={} lockKey={}", usageDate, lockKey);
            return;
        }

        try {
            lineDailyBatchManagerService.run(usageDate, managerInstanceId);
        } finally {
            releaseManagerLock(lockKey, managerInstanceId);
        }
    }

    /**
     * Redis manager lock 획득을 단 한 번 시도한다.
     * 1. `SET key owner NX PX ttl` 의미의 setIfAbsent를 사용한다.
     * 2. RedisTemplate이 null/false를 반환하면 lock 미획득으로 취급한다.
     */
    private boolean tryAcquireManagerLock(String lockKey, String managerInstanceId) {
        Boolean acquired = cacheStringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                managerInstanceId,
                Duration.ofMillis(MANAGER_LOCK_TTL_MS)
        );
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * lock owner와 batch metadata에 사용할 manager 식별자를 만든다.
     * 1. usageDate를 포함해 로그에서 어느 날짜 실행인지 바로 구분한다.
     * 2. UUID를 붙여 같은 서버의 반복 실행도 서로 다른 owner로 분리한다.
     */
    private String buildManagerInstanceId(LocalDate usageDate) {
        return "line-daily-batch:" + usageDate + ":" + UUID.randomUUID();
    }

    /**
     * manager lock을 안전하게 해제한다.
     * 1. 기존 Lua release 경로로 현재 owner 값과 일치할 때만 삭제한다.
     * 2. release 실패는 이미 수행한 manager 작업을 rollback할 수 없으므로 로그만 남긴다.
     */
    private void releaseManagerLock(String lockKey, String managerInstanceId) {
        try {
            trafficLuaScriptInfraService.executeLockRelease(lockKey, managerInstanceId);
        } catch (Exception e) {
            log.warn("line_daily_batch_manager_lock_release_failed lockKey={}", lockKey, e);
        }
    }
}
