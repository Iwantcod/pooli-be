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
    private String uuid;
    private String sourceRecordId;
    private Long requestedAtEpochMillis;
}
