package com.pooli.traffic.service.invoke;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.bson.Document;

import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;
import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.repository.TrafficDeductDoneLogRepository;

@ExtendWith(MockitoExtension.class)
public class TrafficDeductDoneLogServiceTest {

    @Mock
    private TrafficDeductDoneLogRepository trafficDeductDoneLogRepository;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private IndexOperations indexOperations;

    @InjectMocks
    private TrafficDeductDoneLogService trafficDeductDoneLogService;

    @Nested
    @DisplayName("ensureIndexes 테스트")
    class EnsureIndexesTest {

        @Test
        @DisplayName("traceId UNIQUE와 loggedAt TTL 인덱스를 보장한다")
        void ensuresUniqueAndTtlIndexes() {
            // given
            when(mongoTemplate.indexOps(TrafficDeductDoneLogService.COLLECTION_NAME)).thenReturn(indexOperations);

            // when
            trafficDeductDoneLogService.ensureIndexes();

            // then
            verify(mongoTemplate).indexOps(TrafficDeductDoneLogService.COLLECTION_NAME);
            ArgumentCaptor<Index> indexCaptor = ArgumentCaptor.forClass(Index.class);
            verify(indexOperations, times(2)).createIndex(indexCaptor.capture());

            boolean hasTraceIndex = indexCaptor.getAllValues().stream()
                    .map(Index::getIndexKeys)
                    .map(Document::toJson)
                    .anyMatch(json -> json.contains("trace_id"));
            boolean hasLoggedAtIndex = indexCaptor.getAllValues().stream()
                    .map(Index::getIndexKeys)
                    .map(Document::toJson)
                    .anyMatch(json -> json.contains("logged_at"));

            assertTrue(hasTraceIndex);
            assertTrue(hasLoggedAtIndex);
        }
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
            verify(trafficDeductDoneLogRepository, never()).existsByTraceId(any());
        }

        @Test
        @DisplayName("유효 traceId는 저장소 조회 결과를 반환한다")
        void validTraceIdReturnsRepositoryResult() {
            // given
            when(trafficDeductDoneLogRepository.existsByTraceId("trace-001")).thenReturn(true);

            // when
            boolean exists = trafficDeductDoneLogService.existsByTraceId("trace-001");

            // then
            assertTrue(exists);
            verify(trafficDeductDoneLogRepository).existsByTraceId("trace-001");
        }
    }

    @Nested
    @DisplayName("saveIfAbsent 테스트")
    class SaveIfAbsentTest {

        @Test
        @DisplayName("신규 insert가 성공하면 true를 반환한다")
        void returnsTrueWhenInserted() {
            long latency = 123L;

            // when
            boolean saved = trafficDeductDoneLogService.saveIfAbsent(payload(), result(), "1-0", latency);

            // then
            assertTrue(saved);
            ArgumentCaptor<TrafficDeductDoneLog> captor = ArgumentCaptor.forClass(TrafficDeductDoneLog.class);
            verify(trafficDeductDoneLogRepository).insert(captor.capture());
            TrafficDeductDoneLog savedLog = captor.getValue();
            assertEquals("trace-001", savedLog.getTraceId());
            assertEquals("1-0", savedLog.getRecordId());
            assertEquals(90L, savedLog.getDeductedTotalBytes());
            assertEquals(latency, savedLog.getLatency());
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
            // when
            boolean saved = trafficDeductDoneLogService.saveIfAbsent(
                    payload(),
                    result(TrafficFinalStatus.PARTIAL_SUCCESS, policyFailureStatus, 0L, 100L),
                    "1-1",
                    55L
            );

            // then
            assertTrue(saved);
            ArgumentCaptor<TrafficDeductDoneLog> captor = ArgumentCaptor.forClass(TrafficDeductDoneLog.class);
            verify(trafficDeductDoneLogRepository).insert(captor.capture());
            TrafficDeductDoneLog savedLog = captor.getValue();
            assertEquals(TrafficFinalStatus.PARTIAL_SUCCESS.name(), savedLog.getFinalStatus());
            assertEquals(policyFailureStatus.name(), savedLog.getLastLuaStatus());
            assertEquals(0L, savedLog.getDeductedTotalBytes());
            assertEquals(100L, savedLog.getApiRemainingData());
        }

        @Test
        @DisplayName("traceId UNIQUE 중복이면 false를 반환한다")
        void returnsFalseWhenDuplicateKey() {
            // given
            when(trafficDeductDoneLogRepository.insert(any(TrafficDeductDoneLog.class)))
                    .thenThrow(new DuplicateKeyException("duplicate trace_id"));

            // when
            boolean saved = trafficDeductDoneLogService.saveIfAbsent(payload(), result(), "1-0", 10L);

            // then
            assertFalse(saved);
        }

        @Test
        @DisplayName("중복 외 저장 예외는 상위로 전파한다")
        void rethrowsUnexpectedRuntimeException() {
            // given
            when(trafficDeductDoneLogRepository.insert(any(TrafficDeductDoneLog.class)))
                    .thenThrow(new IllegalStateException("mongo unavailable"));

            // when & then
            assertThrows(
                    IllegalStateException.class,
                    () -> trafficDeductDoneLogService.saveIfAbsent(payload(), result(), "1-0", 10L)
            );
        }
    }

    private TrafficPayloadReqDto payload() {
        return TrafficPayloadReqDto.builder()
                .traceId("trace-001")
                .lineId(11L)
                .familyId(22L)
                .appId(33)
                .apiTotalData(100L)
                .enqueuedAt(System.currentTimeMillis())
                .build();
    }

    private TrafficDeductResultResDto result() {
        return result(TrafficFinalStatus.PARTIAL_SUCCESS, TrafficLuaStatus.NO_BALANCE, 90L, 10L);
    }

    private TrafficDeductResultResDto result(
            TrafficFinalStatus finalStatus,
            TrafficLuaStatus lastLuaStatus,
            long deductedTotalBytes,
            long apiRemainingData
    ) {
        return TrafficDeductResultResDto.builder()
                .traceId("trace-001")
                .apiTotalData(100L)
                .deductedTotalBytes(deductedTotalBytes)
                .apiRemainingData(apiRemainingData)
                .finalStatus(finalStatus)
                .lastLuaStatus(lastLuaStatus)
                .createdAt(LocalDateTime.now().minusSeconds(1))
                .finishedAt(LocalDateTime.now())
                .build();
    }
}
