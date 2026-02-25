package com.pooli.common.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "단일 파일 업로드를 위한 Presigned URL 정보 Dto")
public class UploadFileResDto {
    private String uploadUrl;
    private String fileUrl;
}