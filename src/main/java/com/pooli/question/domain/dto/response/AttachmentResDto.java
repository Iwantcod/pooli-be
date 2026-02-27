package com.pooli.question.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "첨부 파일 DTO")
public class AttachmentResDto {
    @Schema(description = "접근 가능한 파일 URL", example = "https://bucket.s3.amazonaws.com/...")
    private String url;

    @Schema(description = "파일 크기", example = "1024")
    private Integer fileSize;
}
