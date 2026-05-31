package com.pooli.traffic.domain.outbox;

import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * TRAFFIC_REDIS_OUTBOX 테이블 레코드 매핑 객체입니다.
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RedisOutboxRecord {

    /** Outbox 레코드 고유 ID */
    private Long id;
    /** Outbox 이벤트 유형 */
    private OutboxEventType eventType;
    /** 직렬화된 이벤트 데이터 페이로드 */
    private String payload;
    /** 이벤트 추적용 Trace ID */
    private String traceId;
    /** 아웃박스 동기화 상태 */
    private OutboxStatus status;
    /** 동기화 재시도 횟수 */
    private Integer retryCount;
    /** 아웃박스 레코드 생성 시각 */
    private LocalDateTime createdAt;
    /** 아웃박스 상태 최종 변경 시각 */
    private LocalDateTime statusUpdatedAt;
}
