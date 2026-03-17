package com.pooli.traffic.service.outbox.strategy;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.pooli.family.mapper.FamilySharedPoolMapper;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.service.AlarmHistoryService;
import com.pooli.traffic.domain.outbox.OutboxEventType;
import com.pooli.traffic.domain.outbox.OutboxRetryResult;
import com.pooli.traffic.domain.outbox.RedisOutboxRecord;
import com.pooli.traffic.domain.outbox.payload.SharedPoolThresholdOutboxPayload;
import com.pooli.traffic.service.outbox.RedisOutboxRecordService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 공유풀 임계치 도달 알람 Outbox 재시도 전략입니다.
 */
@Slf4j
@Component
@Profile({"local", "api", "traffic"})
@RequiredArgsConstructor
public class SharedPoolThresholdOutboxStrategy implements OutboxEventRetryStrategy {

    private final RedisOutboxRecordService redisOutboxRecordService;
    private final FamilySharedPoolMapper familySharedPoolMapper;
    private final AlarmHistoryService alarmHistoryService;

    @Override
    public OutboxEventType supports() {
        return OutboxEventType.SHARED_POOL_THRESHOLD_REACHED;
    }

    @Override
    public OutboxRetryResult execute(RedisOutboxRecord record) {
        SharedPoolThresholdOutboxPayload payload =
                redisOutboxRecordService.readPayload(record, SharedPoolThresholdOutboxPayload.class);
        if (payload.getFamilyId() == null || payload.getFamilyId() <= 0) {
            log.error("traffic_outbox_shared_threshold_payload_invalid outboxId={} reason=invalid_family_id", record.getId());
            return OutboxRetryResult.FAIL;
        }

        try {
            // 임계치 값에 맞는 알람 타입을 먼저 계산해 가족 구성원 전체에 동일하게 전파합니다.
            AlarmType thresholdAlarmType = resolveThresholdAlarmType(payload.getThresholdPct());
            List<Long> lineIds = familySharedPoolMapper.selectLineIdsByFamilyId(payload.getFamilyId());
            if (lineIds == null || lineIds.isEmpty()) {
                return OutboxRetryResult.SUCCESS;
            }

            for (Long lineId : lineIds) {
                if (lineId == null || lineId <= 0) {
                    continue;
                }
                alarmHistoryService.createAlarm(
                        lineId,
                        AlarmCode.FAMILY,
                        thresholdAlarmType
                );
            }
            return OutboxRetryResult.SUCCESS;
        } catch (RuntimeException e) {
            log.error(
                    "traffic_outbox_shared_threshold_execute_failed outboxId={} familyId={}",
                    record.getId(),
                    payload.getFamilyId(),
                    e
            );
            return OutboxRetryResult.FAIL;
        }
    }

    /**
     * 공유풀 잔량 임계치 퍼센트에 대응하는 알람 타입을 반환합니다.
     * 50/30/10은 고정 타입을 사용하고, 그 외 값(0 포함)은 custom 타입으로 처리합니다.
     */
    private AlarmType resolveThresholdAlarmType(Integer thresholdPct) {
        if (thresholdPct == null) {
            return AlarmType.SHARED_POOL_THRESHOLD_REACHED_CUS;
        }
        return switch (thresholdPct) {
            case 50 -> AlarmType.SHARED_POOL_THRESHOLD_REACHED_50;
            case 30 -> AlarmType.SHARED_POOL_THRESHOLD_REACHED_30;
            case 10 -> AlarmType.SHARED_POOL_THRESHOLD_REACHED_10;
            default -> AlarmType.SHARED_POOL_THRESHOLD_REACHED_CUS;
        };
    }
}
