package com.pooli.notification.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "알림 조회 응답 DTO")
public class NotiReadDto {

	@Schema(description = "알림 ID", example = "1")
    private Long appId;
	

    @Schema(description = "현재 페이지", example = "0")
    private int page;

    @Schema(description = "페이지 크기", example = "10")
    private int size;

    @Schema(description = "전체 요소 수", example = "25")
    private long totalElements;
}
