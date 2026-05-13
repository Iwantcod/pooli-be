package com.pooli.traffic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pooli.common.config.AppStreamsProperties;
import com.pooli.common.config.TrafficRetryConfig;
import com.pooli.monitoring.metrics.TrafficGeneratorMetrics;
import com.pooli.monitoring.metrics.TrafficRecordStageMetricsLocal;
import com.pooli.monitoring.metrics.TrafficRecordStageMetricsNoOp;
import com.pooli.monitoring.metrics.TrafficRecordStageMetricsPort;
import com.pooli.monitoring.metrics.TrafficRequestMetrics;
import com.pooli.traffic.config.TrafficSchedulingConfig;
import com.pooli.traffic.controller.TrafficController;
import com.pooli.traffic.service.decision.TrafficDeductOrchestratorService;
import com.pooli.traffic.service.invoke.TrafficDeductDoneLogService;
import com.pooli.traffic.service.invoke.TrafficPayloadValidationService;
import com.pooli.traffic.service.invoke.TrafficRequestEnqueueService;
import com.pooli.traffic.service.invoke.TrafficStreamConsumerRunner;
import com.pooli.traffic.service.invoke.TrafficStreamInfraService;
import com.pooli.traffic.service.invoke.TrafficStreamReclaimService;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;
import com.pooli.traffic.service.outbox.RedisOutboxRetryScheduler;
import com.pooli.traffic.service.outbox.TrafficInFlightDedupeDeleteOutboxService;
import com.pooli.traffic.service.outbox.TrafficPolicyVersionedRedisService;
import com.pooli.traffic.service.outbox.strategy.OutboxRetryStrategyRegistry;
import com.pooli.traffic.service.policy.TrafficPolicyWriteThroughService;
import com.pooli.traffic.service.runtime.TrafficInFlightDedupeService;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

class TrafficProfileBootTest {

    @Nested
    @DisplayName("profile activation")
    class ProfileActivationTest {

        @Test
        @DisplayName("api profile activates producer beans only")
        void apiProfileActivatesProducerOnly() {
            contextRunnerWith(disabledConsumerStreamsProperties())
                    .withPropertyValues("spring.profiles.active=api")
                    .run(context -> {
                        assertThat(context).hasSingleBean(TrafficController.class);
                        assertThat(context).hasSingleBean(TrafficRequestEnqueueService.class);
                        assertThat(context).hasSingleBean(TrafficPolicyWriteThroughService.class);
                        assertThat(context).hasSingleBean(TrafficRetryConfig.class);
                        assertThat(context).hasSingleBean(TrafficRecordStageMetricsPort.class);
                        assertThat(context.getBean(TrafficRecordStageMetricsPort.class))
                                .isInstanceOf(TrafficRecordStageMetricsNoOp.class);

                        assertThat(context).doesNotHaveBean(TrafficStreamConsumerRunner.class);
                        assertThat(context).doesNotHaveBean(TrafficStreamReclaimService.class);
                        assertThat(context).doesNotHaveBean(RedisOutboxRetryScheduler.class);
                        assertThat(context).doesNotHaveBean(TrafficSchedulingConfig.class);
                    });
        }

        @Test
        @DisplayName("traffic profile activates consumer beans")
        void trafficProfileActivatesConsumerOnly() {
            contextRunnerWith(disabledConsumerStreamsProperties())
                    .withPropertyValues("spring.profiles.active=traffic")
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(TrafficController.class);
                        assertThat(context).doesNotHaveBean(TrafficRequestEnqueueService.class);

                        assertThat(context).hasSingleBean(TrafficStreamConsumerRunner.class);
                        assertThat(context).hasSingleBean(TrafficStreamReclaimService.class);
                        assertThat(context).hasSingleBean(RedisOutboxRetryScheduler.class);
                        assertThat(context).hasSingleBean(TrafficPolicyWriteThroughService.class);
                        assertThat(context).hasSingleBean(TrafficRetryConfig.class);
                        assertThat(context).hasSingleBean(TrafficSchedulingConfig.class);
                        assertThat(context).hasSingleBean(TrafficRecordStageMetricsPort.class);
                        assertThat(context.getBean(TrafficRecordStageMetricsPort.class))
                                .isInstanceOf(TrafficRecordStageMetricsNoOp.class);
                    });
        }

        @Test
        @DisplayName("local profile activates all traffic beans")
        void localProfileActivatesAllTrafficBeans() {
            contextRunnerWith(disabledConsumerStreamsProperties())
                    .withPropertyValues("spring.profiles.active=local")
                    .run(context -> {
                        assertThat(context).hasSingleBean(TrafficController.class);
                        assertThat(context).hasSingleBean(TrafficRequestEnqueueService.class);
                        assertThat(context).hasSingleBean(TrafficStreamConsumerRunner.class);
                        assertThat(context).hasSingleBean(TrafficStreamReclaimService.class);
                        assertThat(context).hasSingleBean(RedisOutboxRetryScheduler.class);
                        assertThat(context).hasSingleBean(TrafficPolicyWriteThroughService.class);
                        assertThat(context).hasSingleBean(TrafficRetryConfig.class);
                        assertThat(context).hasSingleBean(TrafficSchedulingConfig.class);
                        assertThat(context).hasSingleBean(TrafficRecordStageMetricsPort.class);
                        assertThat(context.getBean(TrafficRecordStageMetricsPort.class))
                                .isInstanceOf(TrafficRecordStageMetricsLocal.class);
                    });
        }

        @Test
        @DisplayName("traffic/api profile keeps handleRecord stage metrics disabled")
        void nonLocalProfileUsesNoOpHandleRecordMetrics() {
            contextRunnerWith(disabledConsumerStreamsProperties())
                    .withPropertyValues("spring.profiles.active=traffic")
                    .run(context -> {
                        TrafficRecordStageMetricsPort metricsPort = context.getBean(TrafficRecordStageMetricsPort.class);
                        MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);

                        metricsPort.recordStageLatency("parse_validate", 5L);
                        metricsPort.recordTotalLatency(7L);
                        metricsPort.incrementResult("success");

                        assertThat(meterRegistry.find("traffic_stream_handle_record_stage_latency").timer()).isNull();
                        assertThat(meterRegistry.find("traffic_stream_handle_record_total_latency").timer()).isNull();
                        assertThat(meterRegistry.find("traffic_stream_handle_record_result_total").counter()).isNull();
                    });
        }

        @Test
        @DisplayName("local profile records handleRecord stage metrics")
        void localProfileRecordsHandleRecordMetrics() {
            contextRunnerWith(disabledConsumerStreamsProperties())
                    .withPropertyValues("spring.profiles.active=local")
                    .run(context -> {
                        TrafficRecordStageMetricsPort metricsPort = context.getBean(TrafficRecordStageMetricsPort.class);
                        MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);

                        metricsPort.recordStageLatency("parse_validate", 5L);
                        metricsPort.recordStageLatency("ack", 2L);
                        metricsPort.recordTotalLatency(9L);
                        metricsPort.incrementResult("success");

                        assertThat(meterRegistry.find("traffic_stream_handle_record_stage_latency")
                                .tag("stage", "parse_validate")
                                .timer())
                                .isNotNull();
                        assertThat(meterRegistry.find("traffic_stream_handle_record_stage_latency")
                                .tag("stage", "ack")
                                .timer())
                                .isNotNull();
                        assertThat(meterRegistry.find("traffic_stream_handle_record_total_latency").timer())
                                .isNotNull();
                        assertThat(meterRegistry.find("traffic_stream_handle_record_result_total")
                                .tag("result", "success")
                                .counter())
                                .isNotNull();
                    });
        }

        @Test
        @DisplayName("traffic profile boots with explicit consumer bootstrap properties")
        void trafficProfileBootsWithConsumerBootstrapProperties() {
            contextRunnerWith(enabledConsumerStreamsProperties())
                    .withPropertyValues("spring.profiles.active=traffic")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(TrafficStreamConsumerRunner.class);
                        assertThat(context).hasSingleBean(TrafficStreamReclaimService.class);
                        assertThat(context).hasSingleBean(TrafficRecordStageMetricsPort.class);
                        assertThat(context.getBean(TrafficRecordStageMetricsPort.class))
                                .isInstanceOf(TrafficRecordStageMetricsNoOp.class);
                        assertThat(context.getBean(TrafficStreamConsumerRunner.class).isRunning()).isTrue();
                    });
        }
    }

    private ApplicationContextRunner contextRunnerWith(AppStreamsProperties appStreamsProperties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        TrafficController.class,
                        TrafficRequestEnqueueService.class,
                        TrafficStreamConsumerRunner.class,
                        TrafficStreamReclaimService.class,
                        RedisOutboxRetryScheduler.class,
                        TrafficPolicyWriteThroughService.class,
                        TrafficRecordStageMetricsLocal.class,
                        TrafficRecordStageMetricsNoOp.class,
                        TrafficSchedulingConfig.class,
                        TrafficRetryConfig.class,
                        TestBeans.class
                )
                .withBean(AppStreamsProperties.class, () -> appStreamsProperties)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean("streamsStringRedisTemplate", StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean("cacheStringRedisTemplate", StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(TrafficStreamInfraService.class, TrafficProfileBootTest::mockTrafficStreamInfraService)
                .withBean(TrafficPayloadValidationService.class, () -> mock(TrafficPayloadValidationService.class))
                .withBean(TrafficDeductOrchestratorService.class, () -> mock(TrafficDeductOrchestratorService.class))
                .withBean(TrafficInFlightDedupeService.class, () -> mock(TrafficInFlightDedupeService.class))
                .withBean(TrafficDeductDoneLogService.class, () -> mock(TrafficDeductDoneLogService.class))
                .withBean(TrafficRedisKeyFactory.class, () -> mock(TrafficRedisKeyFactory.class))
                .withBean(TrafficRedisFailureClassifier.class, () -> mock(TrafficRedisFailureClassifier.class))
                .withBean(TrafficGeneratorMetrics.class, () -> mock(TrafficGeneratorMetrics.class))
                .withBean(TrafficRequestMetrics.class, () -> mock(TrafficRequestMetrics.class))
                .withBean(TrafficPolicyVersionedRedisService.class, () -> mock(TrafficPolicyVersionedRedisService.class))
                .withBean(RedisOutboxRecordService.class, () -> mock(RedisOutboxRecordService.class))
                .withBean(TrafficInFlightDedupeDeleteOutboxService.class,
                        () -> mock(TrafficInFlightDedupeDeleteOutboxService.class))
                .withBean(OutboxRetryStrategyRegistry.class, () -> mock(OutboxRetryStrategyRegistry.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
    }

    private static AppStreamsProperties disabledConsumerStreamsProperties() {
        AppStreamsProperties properties = new AppStreamsProperties();
        properties.setConsumerEnabled(false);
        properties.setKeyTrafficRequest("traffic:deduct:request");
        properties.setGroupTraffic("traffic-deduct-cg");
        properties.setConsumerName("traffic-node-disabled");
        return properties;
    }

    private static AppStreamsProperties enabledConsumerStreamsProperties() {
        AppStreamsProperties properties = new AppStreamsProperties();
        properties.setConsumerEnabled(true);
        properties.setKeyTrafficRequest("traffic:deduct:request");
        properties.setGroupTraffic("traffic-deduct-cg");
        properties.setConsumerName("traffic-node-a");
        properties.setWorkerThreadCount(1);
        properties.setWorkerQueueCapacity(4);
        properties.setWorkerRejectionPolicy("abort");
        properties.setReadCount(1);
        properties.setBlockMs(1000L);
        properties.setReclaimPendingScanCount(10);
        properties.setReclaimIntervalMs(1000L);
        properties.setReclaimMinIdleMs(15_000L);
        properties.setShutdownAwaitMs(1_000L);
        properties.setMaxRetry(5);
        return properties;
    }

    private static TrafficStreamInfraService mockTrafficStreamInfraService() {
        TrafficStreamInfraService infraService = mock(TrafficStreamInfraService.class);
        when(infraService.readBlocking()).thenAnswer(invocation -> {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        });
        when(infraService.readPendingMessages(anyLong())).thenReturn(List.of());
        when(infraService.claimPending(anyList(), anyLong())).thenReturn(List.of());
        return infraService;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        TrafficRedisRuntimePolicy trafficRedisRuntimePolicy() {
            return mock(TrafficRedisRuntimePolicy.class);
        }
    }
}
