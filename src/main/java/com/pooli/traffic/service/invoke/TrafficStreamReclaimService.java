package com.pooli.traffic.service.invoke;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Service;

import com.pooli.common.config.AppStreamsProperties;

import lombok.RequiredArgsConstructor;

@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficStreamReclaimService {

    private final TrafficStreamInfraService trafficStreamInfraService;
    private final AppStreamsProperties appStreamsProperties;

    public List<MapRecord<String, String, String>> reclaimAndRouteExceededRetries() {
        return reclaimAndRouteExceededRetries(appStreamsProperties.requireReadCount());
    }

    public List<MapRecord<String, String, String>> reclaimAndRouteExceededRetries(int maxClaimCount) {
        if (maxClaimCount <= 0) {
            return List.of();
        }

        long pendingScanCount = appStreamsProperties.requireReclaimPendingScanCount();
        List<PendingMessage> pendingMessages = trafficStreamInfraService.readPendingMessages(pendingScanCount);
        if (pendingMessages.isEmpty()) {
            return List.of();
        }

        long reclaimMinIdleMs = appStreamsProperties.resolveReclaimMinIdleMs();

        List<RecordId> claimCandidates = new ArrayList<>();
        for (PendingMessage pendingMessage : pendingMessages) {
            if (!isIdleEnoughForReclaim(pendingMessage, reclaimMinIdleMs)) {
                continue;
            }

            if (claimCandidates.size() >= maxClaimCount) {
                continue;
            }

            claimCandidates.add(pendingMessage.getId());
        }

        if (claimCandidates.isEmpty()) {
            return List.of();
        }

        return trafficStreamInfraService.claimPending(claimCandidates, reclaimMinIdleMs);
    }

    private boolean isIdleEnoughForReclaim(PendingMessage pendingMessage, long reclaimMinIdleMs) {
        Duration elapsedSinceLastDelivery = pendingMessage.getElapsedTimeSinceLastDelivery();
        long elapsedMs = elapsedSinceLastDelivery == null ? 0L : elapsedSinceLastDelivery.toMillis();
        return elapsedMs >= reclaimMinIdleMs;
    }
}
