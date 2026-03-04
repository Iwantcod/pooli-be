package com.pooli.common.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class Violation {
    private final String target; // "body" | "param" | "path"
    private final String name;   // 필드명(startAt) 또는 파라미터명(lineId)
    private final String reason; // 실패 사유(메시지)
}
