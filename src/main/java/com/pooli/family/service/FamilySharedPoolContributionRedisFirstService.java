package com.pooli.family.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pooli.family.service.FamilySharedPoolContributionTransactionService.ProcessingResult;
import com.pooli.traffic.domain.outbox.OutboxCreateResult;
import com.pooli.traffic.domain.outbox.OutboxRetryResult;
import com.pooli.traffic.domain.outbox.RedisOutboxRecord;
import com.pooli.traffic.domain.outbox.payload.SharedPoolContributionOutboxPayload;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;
import com.pooli.traffic.service.runtime.TrafficRedisFailureClassifier;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 공유풀 기여 요청과 outbox 복구를 같은 Redis-first 계약으로 처리합니다.
 *
 * <p>이 서비스는 요청 접수, 즉시 재시도, scheduler 복구 진입점만 조율하고,
 * 실제 트랜잭션 본문은 {@link FamilySharedPoolContributionTransactionService}에 위임합니다.
 */
@Slf4j
@Service
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class FamilySharedPoolContributionRedisFirstService {

    private static final int IMMEDIATE_MAX_ATTEMPTS = 3;

    private final RedisOutboxRecordService redisOutboxRecordService;
    private final TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;
    private final TrafficRedisFailureClassifier trafficRedisFailureClassifier;
    private final FamilySharedPoolContributionTransactionService transactionService;

    /**
     * API 요청 경로에서 공유풀 기여를 접수하고 가능한 경우 즉시 처리합니다.
     *
     * <p>1. 현재 요청 traceId와 이번 달 payload를 만든다.
     * <br>2. outbox 레코드를 먼저 생성해 Redis 적용 이후 복구 기준점을 확보한다.
     * <br>3. 같은 traceId의 기존 기여 outbox가 있으면 중복 요청으로 보고 즉시 완료되지 않은 것으로 반환한다.
     * <br>4. 새 outbox가 생성되면 같은 payload로 즉시 처리 재시도를 수행한다.
     *
     * @return true면 Redis/MySQL 반영까지 즉시 완료되어 후속 Mongo 로그/알림을 진행해도 된다.
     */
    public boolean submit(Long lineId, Long familyId, Long amount, boolean individualUnlimited) {
        // [1] API 요청 단위 traceId와 현재월/기여일 payload를 확정합니다.
        String traceId = resolveTraceId();
        LocalDate usageDate = LocalDate.now(trafficRedisRuntimePolicy.zoneId());
        YearMonth targetMonth = YearMonth.from(usageDate);
        SharedPoolContributionOutboxPayload payload = SharedPoolContributionOutboxPayload.builder()
                .lineId(lineId)
                .familyId(familyId)
                .amount(amount)
                .individualUnlimited(individualUnlimited)
                .targetMonth(trafficRedisRuntimePolicy.formatYyyyMm(targetMonth))
                .usageDate(usageDate.toString())
                .build();

        // [2] Redis 반영보다 먼저 outbox를 만들고, 이미 접수된 trace/event 조합은 중복으로 흡수합니다.
        OutboxCreateResult createResult = transactionService.createOutbox(payload, traceId);
        if (!createResult.created()) {
            return false;
        }

        // [3] 새 outbox가 생긴 요청만 Redis-first 처리 본문을 즉시 시도합니다.
        return runImmediateAttempts(createResult.outboxId(), payload, traceId);
    }

    /**
     * scheduler가 claim한 공유풀 기여 outbox를 복구 처리합니다.
     *
     * <p>1. outbox payload를 역직렬화한다.
     * <br>2. Redis metadata가 있으면 미반영분을 보정한 뒤 MySQL source를 반영한다.
     * <br>3. Redis metadata가 없으면 최초 Redis 적용이 없던 요청으로 보고 CANCELED 처리한다.
     * <br>4. scheduler 공통 계약에 맞춰 완료/취소는 SUCCESS, 재시도 대상은 FAIL로 반환한다.
     */
    public OutboxRetryResult recover(RedisOutboxRecord record) {
        // [1] scheduler 복구는 저장된 outbox payload만 신뢰합니다.
        SharedPoolContributionOutboxPayload payload =
                redisOutboxRecordService.readPayload(record, SharedPoolContributionOutboxPayload.class);
        // [2] 복구 경로도 요청 경로와 같은 transaction_2 본문을 사용하되 Lua recover script를 실행합니다.
        ProcessingResult result = transactionService.processContribution(record.getId(), payload, record.getTraceId(), true);
        return result == ProcessingResult.SUCCESS || result == ProcessingResult.CANCELED
                ? OutboxRetryResult.SUCCESS
                : OutboxRetryResult.FAIL;
    }

    /**
     * 요청 경로의 transaction_2 처리를 최대 3회 즉시 재시도합니다.
     *
     * <p>1. 성공 또는 취소는 즉시 종료한다.
     * <br>2. lock 경합 등 재시도 가능한 실패는 retry_count를 기록하고 다음 시도로 넘어간다.
     * <br>3. non-retryable 실패는 더 시도하지 않고 outbox 복구 대상으로 남긴다.
     * <br>4. 3회 모두 실패하면 사용자 요청은 실패로 확정하지 않고 scheduler 복구에 맡긴다.
     */
    private boolean runImmediateAttempts(
            Long outboxId,
            SharedPoolContributionOutboxPayload payload,
            String traceId
    ) {
        for (int attempt = 1; attempt <= IMMEDIATE_MAX_ATTEMPTS; attempt++) {
            try {
                // [1] 각 시도는 MySQL 상태 전이와 cleanup rollback 경계를 분리하기 위해 새 트랜잭션에서 수행합니다.
                ProcessingResult result = transactionService.processContribution(outboxId, payload, traceId, false);
                if (result == ProcessingResult.SUCCESS) {
                    return true;
                }
                if (result == ProcessingResult.CANCELED) {
                    return false;
                }
                redisOutboxRecordService.markFailWithRetryCount(outboxId, attempt);
            } catch (RuntimeException e) {
                // [2] 실패 시도 수를 outbox에 남겨 scheduler가 동일 레코드를 이어서 판단할 수 있게 합니다.
                redisOutboxRecordService.markFailWithRetryCount(outboxId, attempt);
                if (!trafficRedisFailureClassifier.isRetryableInfrastructureFailure(e)) {
                    log.warn(
                            "shared_pool_contribution_immediate_retry_stopped outboxId={} traceId={} attempt={}",
                            outboxId,
                            traceId,
                            attempt,
                            e
                    );
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 현재 요청 traceId를 확정합니다.
     *
     * <p>HTTP 요청은 LoggingFilter가 MDC에 넣은 traceId를 사용하고,
     * 테스트/비동기 호출처럼 MDC가 없으면 새 UUID를 생성합니다.
     */
    private String resolveTraceId() {
        // [1] 같은 요청 안의 로그/outbox/metadata가 같은 traceId를 공유하도록 MDC 값을 우선합니다.
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            return traceId.trim();
        }
        // [2] MDC가 없는 호출도 blank traceId로 outbox 생성에 실패하지 않도록 새 값을 만듭니다.
        return UUID.randomUUID().toString();
    }

}
