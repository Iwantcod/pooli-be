package com.pooli.common.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Schema(description = "파일 업로드를 위한 Presigned URL 목록 요청 DTO")
public class PresignedUrlReqDto {
    private List<UploadFileReqDto> files;
    private String domain; // QUESTION, ANSWER 등
}
