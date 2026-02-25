package com.pooli.common.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "단일 파일 업로드를 위한 Presigned URL 정보 요청 Dto")
public class UploadFileReqDto {
    private String fileName;
    private String contentType;
}