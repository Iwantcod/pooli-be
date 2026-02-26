package com.pooli.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ErrorResDto {
    private final String code;
    private final String message;
    private final String timestamp;
    private final String traceId;

    @JsonInclude(JsonInclude.Include.NON_NULL) // 해당 필드의 값이 null인 경우 직렬화하지 않습니다.
    private final List<Violation> violations; // 필드/파라미터 단위 상세
}
