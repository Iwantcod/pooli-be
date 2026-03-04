package com.pooli.common.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "단일 파일 업로드를 위한 Presigned URL 정보 요청 Dto")
public class UploadFileReqDto {

    @NotBlank(message = "파일 이름은 필수입니다.")
    @Schema(
            description = "업로드할 파일 이름 (확장자 포함)",
            example = "image1.png"
    )
    private String fileName;

    @NotBlank(message = "contentType은 필수입니다.")
    @Schema(
            description = "파일의 MIME 타입",
            example = "image/png"
    )
    private String contentType;
}