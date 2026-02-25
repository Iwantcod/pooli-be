package com.pooli.common.controller;

import com.pooli.common.dto.request.PresignedUrlReqDto;
import com.pooli.common.dto.response.PresignedUrlResDto;
import com.pooli.common.service.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "uploads", description = "문의 사항 및 답변 관련 이미지 업로드 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/uploads")
public class UploadController {
    private final UploadService uploadService;


    @Operation(
            summary = "파일 업로드용 Presigned URL 발급",
            description = "클라이언트가 AWS S3에 직접 파일을 업로드할 수 있도록 Presigned URL을 생성합니다. " +
                    "요청된 파일 개수만큼 업로드 URL과 업로드 완료 후 접근 가능한 파일 URL을 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Presigned URL 생성 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
                        업로드 요청 오류
                        
                        - UPLOAD:4001 요청 값 오류
                        - UPLOAD:4002 파일 개수 초과
                        - UPLOAD:4003 파일 형식 오류
                        - UPLOAD:4004 도메인 오류
                        """
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "UPLOAD:5001 - Presigned URL 생성 실패"
            )
    })
    @PostMapping("/presigned-urls")
    public PresignedUrlResDto createPresignedUrls(
            @RequestBody PresignedUrlReqDto request
    ) {
        return uploadService.generatePresignedUrls(request);
    }
}
