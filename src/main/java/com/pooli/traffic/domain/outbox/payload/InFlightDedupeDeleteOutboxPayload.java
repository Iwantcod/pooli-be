package com.pooli.traffic.domain.outbox.payload;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * in-flight dedupe key 삭제 재시도에 필요한 Outbox payload입니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class InFlightDedupeDeleteOutboxPayload {
    /** 멱등 키 제거 식별 고유 UUID */
    private String uuid;
    /** 원본 트래픽 로그 레코드 ID */
    private String sourceRecordId;
    /** 삭제 요청 등록 Epoch 밀리초 시각 */
    private Long requestedAtEpochMillis;
}
