package com.pooli.traffic.domain.restore;

import java.time.LocalDate;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * phase 1/2 Redis replay Lua에 전달할 사용량 복구 명령이다.
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficRestoreReplayCommand {

    /** 중복 replay를 막는 Redis idempotency key */
    private String idempotencyKey;

    /** 사용량을 복구할 업무 기준일 */
    private LocalDate usageDate;

    /** 사용량을 복구할 line 식별자 */
    private Long lineId;

    /** 공유 사용량이 존재할 때 사용할 family 식별자 */
    private Long familyId;

    /** 사용량을 복구할 application 식별자 */
    private Integer applicationId;

    /** 개인 잔량과 사용량 key에 반영할 byte 값 */
    private Long individualUsageBytes;

    /** 공유 잔량과 사용량 key에 반영할 byte 값 */
    private Long sharedUsageBytes;

    /** 잔량 차감 없이 사용량 key에만 반영할 QoS byte 값 */
    private Long qosUsageBytes;

    /** replay 대상 Redis key 만료 epoch seconds */
    private Long expireEpochSeconds;
}
