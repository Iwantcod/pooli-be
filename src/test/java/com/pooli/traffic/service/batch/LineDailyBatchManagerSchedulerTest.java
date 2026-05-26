package com.pooli.traffic.service.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import com.pooli.traffic.service.runtime.TrafficLuaScriptInfraService;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class LineDailyBatchManagerSchedulerTest {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 25);
    private static final String LOCK_KEY = "pooli:line_daily_batch:manager_lock";

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficLuaScriptInfraService trafficLuaScriptInfraService;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @Mock
    private LineDailyBatchManagerService lineDailyBatchManagerService;

    @Mock
    private LineDailyBatchWorkerScheduler lineDailyBatchWorkerScheduler;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private LineDailyBatchManagerScheduler lineDailyBatchManagerScheduler;

    @Test
    @DisplayName("Redis manager lock 미획득 시 manager 작업 대신 같은 usage_date의 worker 시작 감지로 진입한다")
    void startsWorkerWhenLockNotAcquired() {
        when(trafficRedisKeyFactory.lineDailyBatchManagerLockKey()).thenReturn(LOCK_KEY);
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq(LOCK_KEY),
                anyString(),
                eq(Duration.ofMillis(LineDailyBatchManagerScheduler.MANAGER_LOCK_TTL_MS))
        )).thenReturn(false);

        lineDailyBatchManagerScheduler.runForUsageDate(USAGE_DATE);

        verify(lineDailyBatchManagerService, never()).run(eq(USAGE_DATE), anyString());
        verify(lineDailyBatchWorkerScheduler).startForUsageDate(USAGE_DATE);
        verify(trafficLuaScriptInfraService, never()).executeLockRelease(anyString(), anyString());
    }

    @Test
    @DisplayName("Redis manager lock 획득 시 manager 작업을 1회 수행하고 lock을 해제한다")
    void runsManagerWorkOnceWhenLockAcquired() {
        when(trafficRedisKeyFactory.lineDailyBatchManagerLockKey()).thenReturn(LOCK_KEY);
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq(LOCK_KEY),
                anyString(),
                eq(Duration.ofMillis(LineDailyBatchManagerScheduler.MANAGER_LOCK_TTL_MS))
        )).thenReturn(true);
        when(trafficLuaScriptInfraService.executeLockRelease(eq(LOCK_KEY), anyString())).thenReturn(true);

        lineDailyBatchManagerScheduler.runForUsageDate(USAGE_DATE);

        ArgumentCaptor<String> managerInstanceIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(lineDailyBatchManagerService, times(1)).run(eq(USAGE_DATE), managerInstanceIdCaptor.capture());
        assertTrue(managerInstanceIdCaptor.getValue().startsWith("line-daily-batch:" + USAGE_DATE + ":"));
        verify(lineDailyBatchWorkerScheduler, never()).startForUsageDate(USAGE_DATE);
        verify(trafficLuaScriptInfraService, times(1))
                .executeLockRelease(eq(LOCK_KEY), eq(managerInstanceIdCaptor.getValue()));
    }

    @Test
    @DisplayName("03:00 scheduler 진입점은 KST 기준 전일 usage_date를 사용한다")
    void scheduleEntryPointUsesPreviousKstDate() {
        LocalDate beforeRunDate = LocalDate.now(ZoneId.of("Asia/Seoul"));
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.lineDailyBatchManagerLockKey()).thenReturn(LOCK_KEY);
        when(cacheStringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq(LOCK_KEY),
                anyString(),
                eq(Duration.ofMillis(LineDailyBatchManagerScheduler.MANAGER_LOCK_TTL_MS))
        )).thenReturn(true);
        when(trafficLuaScriptInfraService.executeLockRelease(eq(LOCK_KEY), anyString())).thenReturn(true);

        lineDailyBatchManagerScheduler.runDailyBatchManagerSchedule();

        ArgumentCaptor<LocalDate> usageDateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(trafficRedisRuntimePolicy).zoneId();
        verify(lineDailyBatchManagerService).run(usageDateCaptor.capture(), anyString());
        LocalDate afterRunDate = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate capturedUsageDate = usageDateCaptor.getValue();
        assertTrue(
                capturedUsageDate.isEqual(beforeRunDate.minusDays(1))
                        || capturedUsageDate.isEqual(afterRunDate.minusDays(1))
        );
    }

    @Test
    @DisplayName("scheduler는 매일 03:00 KST에 실행된다")
    void scheduleCronRunsAtThreeAmKst() throws NoSuchMethodException {
        Scheduled scheduled = LineDailyBatchManagerScheduler.class
                .getMethod("runDailyBatchManagerSchedule")
                .getAnnotation(Scheduled.class);

        assertEquals("0 0 3 * * *", scheduled.cron());
        assertEquals("Asia/Seoul", scheduled.zone());
    }
}
