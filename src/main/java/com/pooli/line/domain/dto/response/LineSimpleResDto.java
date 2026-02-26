package com.pooli.line.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "User 회선 응답 DTO")
public class LineSimpleResDto {
    @Schema(description = "회선 식별자", example = "1")
    private Long lineId;

    @Schema(description = "전화번호", example = "010-0000-0000")
    private String phoneNumber;
}
