package com.pooli.common.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ValidationErrorResDto {
    private final String code;        // 예: COMMON-4001
    private final String message;     // 요약 메시지
    private final String timestamp;   // OffsetDateTime.now().toString()
    private final String traceId;     // MDC.get("traceId")

    private final List<Violation> violations; // 필드/파라미터 단위 상세
}
