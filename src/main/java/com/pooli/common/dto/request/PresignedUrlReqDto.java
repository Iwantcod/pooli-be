package com.pooli.common.dto.request;

import com.pooli.common.enums.FileDomain;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Schema(description = "파일 업로드를 위한 Presigned URL 목록 요청 DTO")
public class PresignedUrlReqDto {

    @NotNull(message = "업로드 파일은 필수입니다.")
    @Valid
    @Schema(description = "업로드할 파일 정보를 담는 List")
    private List<UploadFileReqDto> files;

    @NotNull(message = "도메인은 필수입니다.")
    @Schema(description = "문의 사항인지(QUESTION), 답변(ANSWER)인지 도메인", example = "ANSWER")
    private FileDomain domain;
}
