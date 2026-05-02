package com.pooli.traffic.service.invoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
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
    @DisplayName("reclaimAndRouteExceededRetries")
    class ReclaimAndRouteExceededRetriesTest {

        @Test
        @DisplayName("returns empty when there is no pending message")
        void returnsEmptyWhenNoPendingMessages() {
            when(appStreamsProperties.requireReclaimPendingScanCount()).thenReturn(100);
            when(appStreamsProperties.requireReadCount()).thenReturn(20);
            when(trafficStreamInfraService.readPendingMessages(100L)).thenReturn(List.of());

            List<MapRecord<String, String, String>> reclaimed =
                    trafficStreamReclaimService.reclaimAndRouteExceededRetries();

            assertTrue(reclaimed.isEmpty());
            verify(trafficStreamInfraService, never()).claimPending(anyList(), anyLong());
        }

        @Test
        @DisplayName("does not route by delivery count and still claims reclaim candidates")
        void doesNotRouteByDeliveryCountAndStillClaimsCandidates() {
            mockProperties(100, 15_000L);
            PendingMessage highDeliveryCount = pendingMessage("1-0", 6L, 20_000L);
            MapRecord<String, String, String> claimedRecord = MapRecord
                    .<String, String, String>create("traffic:deduct:request", Map.of(TrafficStreamFields.PAYLOAD, "{\"traceId\":\"t-1\"}"))
                    .withId(RecordId.of("1-0"));

            when(trafficStreamInfraService.readPendingMessages(100L)).thenReturn(List.of(highDeliveryCount));
            when(trafficStreamInfraService.claimPending(List.of(RecordId.of("1-0")), 15_000L))
                    .thenReturn(List.of(claimedRecord));

            List<MapRecord<String, String, String>> reclaimed =
                    trafficStreamReclaimService.reclaimAndRouteExceededRetries(3);

            assertEquals(1, reclaimed.size());
            verify(trafficStreamInfraService).claimPending(List.of(RecordId.of("1-0")), 15_000L);
            verify(trafficStreamInfraService, never()).writeDlq(any(), any(), any());
            verify(trafficStreamInfraService, never()).acknowledge(
                    argThat(recordId -> recordId != null && "1-0".equals(recordId.getValue()))
            );
        }

        @Test
        @DisplayName("claims only up to remaining reclaim dispatch limit")
        void claimsOnlyUpToDispatchLimit() {
            mockProperties(100, 15_000L);
            PendingMessage first = pendingMessage("2-0", 2L, 18_000L);
            PendingMessage second = pendingMessage("2-1", 3L, 19_000L);
            PendingMessage third = pendingMessage("2-2", 1L, 20_000L);
            MapRecord<String, String, String> claimedRecord = MapRecord
                    .<String, String, String>create("traffic:deduct:request", Map.of(TrafficStreamFields.PAYLOAD, "{\"traceId\":\"t-2\"}"))
                    .withId(RecordId.of("2-0"));

            when(trafficStreamInfraService.readPendingMessages(100L)).thenReturn(List.of(first, second, third));
            when(trafficStreamInfraService.claimPending(List.of(RecordId.of("2-0"), RecordId.of("2-1")), 15_000L))
                    .thenReturn(List.of(claimedRecord));

            List<MapRecord<String, String, String>> reclaimed =
                    trafficStreamReclaimService.reclaimAndRouteExceededRetries(2);

            assertEquals(1, reclaimed.size());
            assertEquals("2-0", reclaimed.get(0).getId().getValue());
            verify(trafficStreamInfraService).claimPending(List.of(RecordId.of("2-0"), RecordId.of("2-1")), 15_000L);
            verify(trafficStreamInfraService, never()).writeDlq(any(), any(), any());
        }

        @Test
        @DisplayName("uses dedicated reclaim scan count instead of read count")
        void usesDedicatedReclaimScanCount() {
            when(appStreamsProperties.requireReclaimPendingScanCount()).thenReturn(250);
            when(appStreamsProperties.requireReadCount()).thenReturn(20);
            when(trafficStreamInfraService.readPendingMessages(250L)).thenReturn(List.of());

            trafficStreamReclaimService.reclaimAndRouteExceededRetries();

            verify(trafficStreamInfraService).readPendingMessages(250L);
        }

        @Test
        @DisplayName("skips pending message when min idle is not reached")
        void skipsPendingMessageWhenIdleNotEnough() {
            mockProperties(100, 15_000L);
            PendingMessage tooFresh = pendingMessage("3-0", 1L, 5_000L);

            when(trafficStreamInfraService.readPendingMessages(100L)).thenReturn(List.of(tooFresh));

            List<MapRecord<String, String, String>> reclaimed =
                    trafficStreamReclaimService.reclaimAndRouteExceededRetries(3);

            assertTrue(reclaimed.isEmpty());
            verify(trafficStreamInfraService, never()).claimPending(anyList(), anyLong());
            verify(trafficStreamInfraService, never()).writeDlq(any(), any(), any());
        }

        @Test
        @DisplayName("returns empty when reclaim dispatch limit is zero")
        void returnsEmptyWhenClaimLimitIsZero() {
            List<MapRecord<String, String, String>> reclaimed =
                    trafficStreamReclaimService.reclaimAndRouteExceededRetries(0);

            assertTrue(reclaimed.isEmpty());
            verify(trafficStreamInfraService, never()).readPendingMessages(anyLong());
        }

        private void mockProperties(int reclaimPendingScanCount, long reclaimMinIdleMs) {
            when(appStreamsProperties.requireReclaimPendingScanCount()).thenReturn(reclaimPendingScanCount);
            when(appStreamsProperties.resolveReclaimMinIdleMs()).thenReturn(reclaimMinIdleMs);
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
