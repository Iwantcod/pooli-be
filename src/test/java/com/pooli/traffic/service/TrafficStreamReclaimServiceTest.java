package com.pooli.traffic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.RecordId;

import com.pooli.common.config.AppStreamsProperties;
import com.pooli.traffic.domain.TrafficStreamFields;

@ExtendWith(MockitoExtension.class)
class TrafficStreamReclaimServiceTest {

    @Mock
    private TrafficStreamInfraService trafficStreamInfraService;

    @Mock
    private AppStreamsProperties appStreamsProperties;

    @InjectMocks
    private TrafficStreamReclaimService trafficStreamReclaimService;

    @Nested
    @DisplayName("reclaimAndRouteExceededRetries 테스트")
    class ReclaimAndRouteExceededRetriesTest {

        @Test
        @DisplayName("pending이 없으면 빈 목록 반환")
        void returnsEmptyWhenNoPendingMessages() {
            // given
            when(appStreamsProperties.getReadCount()).thenReturn(20);
            when(trafficStreamInfraService.readPendingMessages(20L)).thenReturn(List.of());

            // when
            List<MapRecord<String, String, String>> reclaimed =
                    trafficStreamReclaimService.reclaimAndRouteExceededRetries();

            // then
            assertTrue(reclaimed.isEmpty());
            verify(trafficStreamInfraService, never()).claimPending(anyList(), anyLong());
        }

        @Test
        @DisplayName("retry 초과 메시지는 DLQ+ACK 처리")
        void routesExceededRetryMessageToDlq() {
            // given
            mockProperties(20, 70_000L, 3);
            PendingMessage exceeded = pendingMessage("1-0", 4L, 80_000L);
            MapRecord<String, String, String> sourceRecord = MapRecord
                    .<String, String, String>create("traffic:deduct:request", Map.of(TrafficStreamFields.PAYLOAD, "{\"traceId\":\"t-1\"}"))
                    .withId(RecordId.of("1-0"));

            when(trafficStreamInfraService.readPendingMessages(20L)).thenReturn(List.of(exceeded));
            when(trafficStreamInfraService.readRecordById(any(RecordId.class))).thenReturn(sourceRecord);
            when(trafficStreamInfraService.extractPayload(sourceRecord)).thenReturn("{\"traceId\":\"t-1\"}");

            // when
            List<MapRecord<String, String, String>> reclaimed =
                    trafficStreamReclaimService.reclaimAndRouteExceededRetries();

            // then
            assertTrue(reclaimed.isEmpty());
            verify(trafficStreamInfraService).writeDlq(
                    eq("{\"traceId\":\"t-1\"}"),
                    eq("max retry 초과: deliveryCount=4, maxRetry=3"),
                    eq("1-0")
            );
            verify(trafficStreamInfraService).acknowledge(
                    argThat(recordId -> recordId != null && "1-0".equals(recordId.getValue()))
            );
            verify(trafficStreamInfraService, never()).claimPending(anyList(), eq(70_000L));
        }

        @Test
        @DisplayName("retry 한도 이내 메시지는 reclaim 대상으로 claim")
        void claimsEligiblePendingMessages() {
            // given
            mockProperties(20, 70_000L, 3);
            PendingMessage candidate = pendingMessage("2-0", 2L, 75_000L);
            MapRecord<String, String, String> claimedRecord = MapRecord
                    .<String, String, String>create("traffic:deduct:request", Map.of(TrafficStreamFields.PAYLOAD, "{\"traceId\":\"t-2\"}"))
                    .withId(RecordId.of("2-0"));

            when(trafficStreamInfraService.readPendingMessages(20L)).thenReturn(List.of(candidate));
            when(trafficStreamInfraService.claimPending(List.of(RecordId.of("2-0")), 70_000L))
                    .thenReturn(List.of(claimedRecord));

            // when
            List<MapRecord<String, String, String>> reclaimed =
                    trafficStreamReclaimService.reclaimAndRouteExceededRetries();

            // then
            assertEquals(1, reclaimed.size());
            assertEquals("2-0", reclaimed.get(0).getId().getValue());
            verify(trafficStreamInfraService).claimPending(List.of(RecordId.of("2-0")), 70_000L);
            verify(trafficStreamInfraService, never()).writeDlq(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("min-idle 미만 pending은 reclaim에서 제외")
        void skipsPendingMessageWhenIdleNotEnough() {
            // given
            mockProperties(20, 70_000L, 3);
            PendingMessage tooFresh = pendingMessage("3-0", 1L, 5_000L);

            when(trafficStreamInfraService.readPendingMessages(20L)).thenReturn(List.of(tooFresh));

            // when
            List<MapRecord<String, String, String>> reclaimed =
                    trafficStreamReclaimService.reclaimAndRouteExceededRetries();

            // then
            assertTrue(reclaimed.isEmpty());
            verify(trafficStreamInfraService, never()).claimPending(anyList(), anyLong());
            verify(trafficStreamInfraService, never()).writeDlq(any(), anyString(), anyString());
        }

        private void mockProperties(int readCount, long reclaimMinIdleMs, int maxRetry) {
            when(appStreamsProperties.getReadCount()).thenReturn(readCount);
            when(appStreamsProperties.getReclaimMinIdleMs()).thenReturn(reclaimMinIdleMs);
            when(appStreamsProperties.getMaxRetry()).thenReturn(maxRetry);
        }

        private PendingMessage pendingMessage(String recordId, long deliveryCount, long elapsedMs) {
            return new PendingMessage(
                    RecordId.of(recordId),
                    Consumer.from("traffic-deduct-cg", "consumer-a"),
                    Duration.ofMillis(elapsedMs),
                    deliveryCount
            );
        }
    }
}
