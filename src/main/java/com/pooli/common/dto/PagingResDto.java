package com.pooli.common.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "페이징 응답 DTO")
public class PagingResDto<T> {
	
    @Schema(description = "페이징 처리된 목록")
    private List<T> content;

    @Schema(description = "현재 페이지", example = "0")
    private Integer page;

    @Schema(description = "페이지 크기", example = "10")
    private Integer size;

    @Schema(description = "전체 요소 수", example = "25")
    private Long totalElements;
    
    @Schema(description = "전체 페이지 수", example = "3")
    private Integer totalPages;
        
}
