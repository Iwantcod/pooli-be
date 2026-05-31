package com.pooli.traffic.service.invoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.common.config.AppStreamsProperties;
import com.pooli.common.exception.ApplicationException;
import com.pooli.monitoring.metrics.TrafficRequestMetrics;
import com.pooli.traffic.domain.dto.request.TrafficGenerateReqDto;
import com.pooli.traffic.service.restore.TrafficRestoreTrafficGateService;

@ExtendWith(MockitoExtension.class)
class TrafficRequestEnqueueServiceTest {

    @Mock
    private StringRedisTemplate streamsStringRedisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    @Mock
    private TrafficRequestMetrics trafficRequestMetrics;

    @Mock
    private TrafficRestoreTrafficGateService trafficRestoreTrafficGateService;

    private AppStreamsProperties appStreamsProperties;
    private TrafficRequestEnqueueService trafficRequestEnqueueService;

    @BeforeEach
    void setUp() {
        MDC.clear();
        appStreamsProperties = new AppStreamsProperties();
        appStreamsProperties.setKeyTrafficRequest("traffic:deduct:request");
        appStreamsProperties.setTrafficRequestMaxLength(500_000L);

        trafficRequestEnqueueService = new TrafficRequestEnqueueService(
                streamsStringRedisTemplate,
                new ObjectMapper(),
                appStreamsProperties,
                trafficRequestMetrics,
                trafficRestoreTrafficGateService
        );
    }

    @Test
    @DisplayName("복구 flag가 활성화되면 request stream XADD 전에 요청을 차단한다")
    void blocksBeforeXaddWhenRestoreFlagIsActive() {
        TrafficGenerateReqDto request = TrafficGenerateReqDto.builder()
                .lineId(10L)
                .familyId(20L)
                .appId(30)
                .apiTotalData(5_000L)
                .build();
        when(trafficRestoreTrafficGateService.shouldBlockTraffic()).thenReturn(true);

        assertThrows(ApplicationException.class, () -> trafficRequestEnqueueService.enqueue(request));

        verify(streamsStringRedisTemplate, never()).opsForStream();
        verifyNoInteractions(streamOperations);
    }

    @Test
    @DisplayName("enqueue 시 request stream XADD에 maxlen approximate trimming 옵션을 적용한다")
    @SuppressWarnings("unchecked")
    void enqueueAppliesApproximateMaxLengthOption() {
        MDC.put("traceId", "trace-xadd-maxlen");
        TrafficGenerateReqDto request = TrafficGenerateReqDto.builder()
                .lineId(10L)
                .familyId(20L)
                .appId(30)
                .apiTotalData(5_000L)
                .build();
        ArgumentCaptor<MapRecord<String, Object, Object>> recordCaptor = ArgumentCaptor.forClass(MapRecord.class);
        ArgumentCaptor<XAddOptions> optionsCaptor = ArgumentCaptor.forClass(XAddOptions.class);

        when(streamsStringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.add(any(MapRecord.class), any(XAddOptions.class))).thenReturn(RecordId.of("1-0"));

        trafficRequestEnqueueService.enqueue(request);

        verify(streamOperations).add(recordCaptor.capture(), optionsCaptor.capture());
        XAddOptions options = optionsCaptor.getValue();
        MapRecord<String, Object, Object> record = recordCaptor.getValue();

        assertEquals(500_000L, options.getMaxlen());
        assertTrue(options.isApproximateTrimming());
        assertEquals("traffic:deduct:request", record.getStream());
        assertTrue(String.valueOf(((Map<?, ?>) record.getValue()).get("payload")).contains("trace-xadd-maxlen"));
    }
}
