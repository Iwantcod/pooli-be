package com.pooli.common.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "단일 파일 업로드를 위한 Presigned URL 정보 Dto")
public class UploadFileResDto {

    @Schema(
            description = "파일 업로드를 위한 Presigned URL",
            example = "https://bucket.s3.ap-northeast-2.amazonaws.com/answer/temp-file.png?X-Amz-Algorithm=AWS4-HMAC-SHA256..."
    )
    private String uploadUrl;

    @Schema(
            description = "업로드 후 S3 키",
            example = "question/39b1f22e617240648a739161149d042c.png"
    )
    private String s3Key;
}