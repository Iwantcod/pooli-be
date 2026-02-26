package com.pooli.common.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ErrorResDto {
    private final String code;
    private final String message;
    private final String timestamp;
    private final String traceId;
}
