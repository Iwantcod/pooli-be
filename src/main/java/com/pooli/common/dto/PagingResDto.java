package com.pooli.common.dto;

import java.util.List;

import com.pooli.notification.domain.dto.response.NotiSendResDto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "페이징 응답 DTO")
public class PagingResDto {
	
    @Schema(description = "페이징 처리된 목록")
    private List<NotiSendResDto> content;

    @Schema(description = "현재 페이지", example = "0")
    private Integer page;

    @Schema(description = "페이지 크기", example = "10")
    private Integer size;

    @Schema(description = "전체 요소 수", example = "25")
    private Long totalElements;
    
    @Schema(description = "전체 페이지 수", example = "3")
    private Long totalPages;
        
}
