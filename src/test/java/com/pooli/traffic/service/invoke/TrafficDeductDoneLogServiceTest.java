package com.pooli.traffic.service.invoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;

import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;
import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.mapper.TrafficDeductDoneLogMapper;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

@ExtendWith(MockitoExtension.class)
public class TrafficDeductDoneLogServiceTest {

    private static final long ENQUEUED_AT_EPOCH_MILLIS = 1_700_000_000_000L;
    private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");

    @Mock
    private TrafficDeductDoneLogMapper trafficDeductDoneLogMapper;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @InjectMocks
    private TrafficDeductDoneLogService trafficDeductDoneLogService;

    @BeforeEach
    void setUp() {
        lenient().when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ASIA_SEOUL);
    }

    @Nested
    @DisplayName("existsByTraceId 테스트")
    class ExistsByTraceIdTest {

        @Test
        @DisplayName("빈 traceId는 false를 반환한다")
        void blankTraceIdReturnsFalse() {
            // when
            boolean exists = trafficDeductDoneLogService.existsByTraceId(" ");

            // then
            assertFalse(exists);
            verify(trafficDeductDoneLogMapper, never()).existsByTraceId(any());
        }

        @Test
        @DisplayName("유효 traceId는 저장소 조회 결과를 반환한다")
        void validTraceIdReturnsRepositoryResult() {
            // given
            when(trafficDeductDoneLogMapper.existsByTraceId("trace-001")).thenReturn(true);

            // when
            boolean exists = trafficDeductDoneLogService.existsByTraceId("trace-001");

            // then
            assertTrue(exists);
            verify(trafficDeductDoneLogMapper).existsByTraceId("trace-001");
        }
    }

    @Nested
    @DisplayName("saveIfAbsent 테스트")
    class SaveIfAbsentTest {

        @Test
        @DisplayName("신규 insert가 성공하면 true를 반환한다")
        void returnsTrueWhenInserted() {
            // given
            when(trafficDeductDoneLogMapper.insert(any(TrafficDeductDoneLog.class))).thenReturn(1);
            long latency = 123L;

            // when
            boolean saved = trafficDeductDoneLogService.saveIfAbsent(payload(), result(), "1-0", latency);

            // then
            assertTrue(saved);
            ArgumentCaptor<TrafficDeductDoneLog> captor = ArgumentCaptor.forClass(TrafficDeductDoneLog.class);
            verify(trafficDeductDoneLogMapper).insert(captor.capture());
            TrafficDeductDoneLog savedLog = captor.getValue();
            assertEquals("trace-001", savedLog.getTraceId());
            assertEquals("1-0", savedLog.getRecordId());
            assertEquals(11L, savedLog.getLineId());
            assertEquals(LocalDateTime.ofInstant(Instant.ofEpochMilli(ENQUEUED_AT_EPOCH_MILLIS), ASIA_SEOUL), savedLog.getEnqueuedAt());
            assertEquals(30L, savedLog.getDeductedIndividualBytes());
            assertEquals(60L, savedLog.getDeductedSharedBytes());
            assertEquals(90L, savedLog.getDeductedTotalBytes());
            assertNull(savedLog.getFailureReason());
            assertEquals(latency, savedLog.getLatency());
            assertNull(savedLog.getRestoreStatus());
            assertNull(savedLog.getRestoreStatusUpdatedAt());
            assertNull(savedLog.getRestoreRetryCount());
            assertNull(savedLog.getRestoreLastErrorMessage());
        }

        @ParameterizedTest
        @EnumSource(
                value = TrafficLuaStatus.class,
                names = {
                        "BLOCKED_IMMEDIATE",
                        "BLOCKED_REPEAT",
                        "HIT_DAILY_LIMIT",
                        "HIT_MONTHLY_SHARED_LIMIT",
                        "HIT_APP_DAILY_LIMIT",
                        "HIT_APP_SPEED"
                }
        )
        @DisplayName("정책 실패 상태는 DONE 이력의 lastLuaStatus로 그대로 저장된다")
        void storesPolicyFailureStatusInDoneHistory(TrafficLuaStatus policyFailureStatus) {
            // given
            when(trafficDeductDoneLogMapper.insert(any(TrafficDeductDoneLog.class))).thenReturn(1);

            // when
            boolean saved = trafficDeductDoneLogService.saveIfAbsent(
                    payload(),
                    result(TrafficFinalStatus.NOT_DEDUCTED, policyFailureStatus, 0L, 0L, 100L),
                    "1-1",
                    55L
            );

            // then
            assertTrue(saved);
            ArgumentCaptor<TrafficDeductDoneLog> captor = ArgumentCaptor.forClass(TrafficDeductDoneLog.class);
            verify(trafficDeductDoneLogMapper).insert(captor.capture());
            TrafficDeductDoneLog savedLog = captor.getValue();
            assertEquals(TrafficFinalStatus.NOT_DEDUCTED.name(), savedLog.getFinalStatus());
            assertEquals(policyFailureStatus.name(), savedLog.getLastLuaStatus());
            assertEquals(0L, savedLog.getDeductedIndividualBytes());
            assertEquals(0L, savedLog.getDeductedSharedBytes());
            assertEquals(0L, savedLog.getDeductedTotalBytes());
            assertEquals(100L, savedLog.getApiRemainingData());
        }

        @Test
        @DisplayName("실패 사유가 있으면 DONE 이력의 failureReason으로 저장한다")
        void storesFailureReasonInDoneHistory() {
            // given
            when(trafficDeductDoneLogMapper.insert(any(TrafficDeductDoneLog.class))).thenReturn(1);

            // when
            boolean saved = trafficDeductDoneLogService.saveIfAbsent(
                    payload(),
                    result(TrafficFinalStatus.FAILED, TrafficLuaStatus.ERROR, 0L, 0L, 100L)
                            .toBuilder()
                            .failureReason("STALE_TARGET_MONTH")
                            .build(),
                    "1-2",
                    55L
            );

            // then
            assertTrue(saved);
            ArgumentCaptor<TrafficDeductDoneLog> captor = ArgumentCaptor.forClass(TrafficDeductDoneLog.class);
            verify(trafficDeductDoneLogMapper).insert(captor.capture());
            TrafficDeductDoneLog savedLog = captor.getValue();
            assertEquals("STALE_TARGET_MONTH", savedLog.getFailureReason());
        }

        @Test
        @DisplayName("traceId UNIQUE 중복이면 false를 반환한다")
        void returnsFalseWhenDuplicateKey() {
            // given
            when(trafficDeductDoneLogMapper.insert(any(TrafficDeductDoneLog.class)))
                    .thenThrow(new DuplicateKeyException("duplicate trace_id"));

            // when
            boolean saved = trafficDeductDoneLogService.saveIfAbsent(payload(), result(), "1-0", 10L);

            // then
            assertFalse(saved);
            verify(trafficDeductDoneLogMapper, times(1)).insert(any(TrafficDeductDoneLog.class));
        }

        @Test
        @DisplayName("retryable DB 예외는 최대 3회 재시도 후 성공하면 true를 반환한다")
        void retriesDoneLogInsertUpToThreeTimesThenSucceeds() {
            // given
            when(trafficDeductDoneLogMapper.insert(any(TrafficDeductDoneLog.class)))
                    .thenThrow(new QueryTimeoutException("timeout-1"))
                    .thenThrow(new QueryTimeoutException("timeout-2"))
                    .thenThrow(new QueryTimeoutException("timeout-3"))
                    .thenReturn(1);

            // when
            boolean saved = trafficDeductDoneLogService.saveIfAbsent(payload(), result(), "1-2", 10L);

            // then
            assertTrue(saved);
            verify(trafficDeductDoneLogMapper, times(4)).insert(any(TrafficDeductDoneLog.class));
        }

        @Test
        @DisplayName("retryable DB 예외가 계속되면 재시도 소진 후 예외를 전파한다")
        void rethrowsWhenRetryableDbExceptionPersists() {
            // given
            when(trafficDeductDoneLogMapper.insert(any(TrafficDeductDoneLog.class)))
                    .thenThrow(new QueryTimeoutException("timeout-1"))
                    .thenThrow(new QueryTimeoutException("timeout-2"))
                    .thenThrow(new QueryTimeoutException("timeout-3"))
                    .thenThrow(new QueryTimeoutException("timeout-4"));

            // when & then
            assertThrows(
                    QueryTimeoutException.class,
                    () -> trafficDeductDoneLogService.saveIfAbsent(payload(), result(), "1-3", 10L)
            );
            verify(trafficDeductDoneLogMapper, times(4)).insert(any(TrafficDeductDoneLog.class));
        }

        @Test
        @DisplayName("중복 외 저장 예외는 상위로 전파한다")
        void rethrowsUnexpectedRuntimeException() {
            // given
            when(trafficDeductDoneLogMapper.insert(any(TrafficDeductDoneLog.class)))
                    .thenThrow(new IllegalStateException("mysql unavailable"));

            // when & then
            assertThrows(
                    IllegalStateException.class,
                    () -> trafficDeductDoneLogService.saveIfAbsent(payload(), result(), "1-0", 10L)
            );
        }

        @Test
        @DisplayName("INSERT 영향 행수가 1이 아니면 예외를 전파한다")
        void throwsWhenInsertAffectedRowsIsNotOne() {
            // given
            when(trafficDeductDoneLogMapper.insert(any(TrafficDeductDoneLog.class))).thenReturn(0);

            // when & then
            IllegalStateException thrown = assertThrows(
                    IllegalStateException.class,
                    () -> trafficDeductDoneLogService.saveIfAbsent(payload(), result(), "1-9", 10L)
            );
            assertTrue(thrown.getMessage().contains("traffic_done_log_insert_unexpected_row_count"));
        }

        @Test
        @DisplayName("빈 recordId는 로그만 남기고 그대로 저장한다")
        void savesBlankRecordIdAsIs() {
            // given
            when(trafficDeductDoneLogMapper.insert(any(TrafficDeductDoneLog.class))).thenReturn(1);

            // when
            boolean saved = trafficDeductDoneLogService.saveIfAbsent(payload(), result(), " ", 10L);

            // then
            assertTrue(saved);
            ArgumentCaptor<TrafficDeductDoneLog> captor = ArgumentCaptor.forClass(TrafficDeductDoneLog.class);
            verify(trafficDeductDoneLogMapper).insert(captor.capture());
            assertEquals(" ", captor.getValue().getRecordId());
        }

        @Test
        @DisplayName("null recordId는 로그만 남기고 그대로 저장한다")
        void savesNullRecordIdAsIs() {
            // given
            when(trafficDeductDoneLogMapper.insert(any(TrafficDeductDoneLog.class))).thenReturn(1);

            // when
            boolean saved = trafficDeductDoneLogService.saveIfAbsent(payload(), result(), null, 10L);

            // then
            assertTrue(saved);
            ArgumentCaptor<TrafficDeductDoneLog> captor = ArgumentCaptor.forClass(TrafficDeductDoneLog.class);
            verify(trafficDeductDoneLogMapper).insert(captor.capture());
            assertNull(captor.getValue().getRecordId());
        }
    }

    private TrafficPayloadReqDto payload() {
        return TrafficPayloadReqDto.builder()
                .traceId("trace-001")
                .lineId(11L)
                .familyId(22L)
                .appId(33)
                .apiTotalData(100L)
                .enqueuedAt(ENQUEUED_AT_EPOCH_MILLIS)
                .build();
    }

    private TrafficDeductResultResDto result() {
        return result(TrafficFinalStatus.PARTIAL_SUCCESS, TrafficLuaStatus.NO_BALANCE, 30L, 60L, 10L);
    }

    private TrafficDeductResultResDto result(
            TrafficFinalStatus finalStatus,
            TrafficLuaStatus lastLuaStatus,
            long deductedIndividualBytes,
            long deductedSharedBytes,
            long apiRemainingData
    ) {
        return TrafficDeductResultResDto.builder()
                .traceId("trace-001")
                .apiTotalData(100L)
                .deductedIndividualBytes(deductedIndividualBytes)
                .deductedSharedBytes(deductedSharedBytes)
                .apiRemainingData(apiRemainingData)
                .finalStatus(finalStatus)
                .lastLuaStatus(lastLuaStatus)
                .createdAt(LocalDateTime.now().minusSeconds(1))
                .finishedAt(LocalDateTime.now())
                .build();
    }
}
