package com.pooli.common.dto.response;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "파일 업로드를 위한 Presigned URL 목록 응답 DTO")
public class PresignedUrlResDto {

    @Schema(description = "생성된 Presigned URL 목록")
    private List<UploadFileResDto> uploads;
}
