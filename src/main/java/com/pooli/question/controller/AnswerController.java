package com.pooli.question.controller;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.question.domain.dto.request.AnswerCreateReqDto;
import com.pooli.question.domain.dto.response.AnswerCreateResDto;
import com.pooli.question.service.AnswerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "answer", description = "문의 사항 답변 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/answers")
public class AnswerController {

    private final AnswerService answerService;

    @Operation(
            summary = "문의사항 답변 생성 요청",
            description = "사용자의 문의사항에 대한 답변을 생성한다"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "답변 생성 성공"),
            @ApiResponse(responseCode = "400",
                    description = """
                잘못된 요청
                - COMMON:4000 요청 형식 불일치
                - COMMON:4001 요청 DTO 필드 유효성 검증 실패
                - ANSWER:4001: 이미 답변이 존재합니다
                """),
            @ApiResponse(responseCode = "403",
                    description = "COMMON:4302 접근 권한이 없습니다."),
            @ApiResponse(responseCode = "500",
                    description = """
                서버 오류
                - COMMON:5000 서버 내부 오류 발생
                - COMMON:5001 데이터베이스 오류
                """)
    })
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<AnswerCreateResDto> createAnswer(
            @AuthenticationPrincipal AuthUserDetails userDetails,
            @Valid @RequestBody AnswerCreateReqDto request
    ) {
        AnswerCreateResDto res = answerService.createAnswer(request, userDetails.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @Operation(
            summary = "답변 삭제 요청",
            description = "해당 답변을 soft delete 처리한다. 작성자 본인 또는 ADMIN 권한만 가능"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "답변 삭제 성공"),
            @ApiResponse(responseCode = "400",
                    description = """
            잘못된 요청
            - COMMON:4000 요청 형식 불일치
            """),
            @ApiResponse(responseCode = "403",
                    description = "COMMON:4302 접근 권한이 없습니다."),
            @ApiResponse(responseCode = "404",
                    description = "해당 답변 없음"),
            @ApiResponse(responseCode = "500",
                    description = """
            서버 오류
            - COMMON:5000 서버 내부 오류 발생
            - COMMON:5001 데이터베이스 오류
            """)
    })
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping
    public ResponseEntity<Void> deleteAnswer(
            @RequestParam Long answerId
    ) {
        answerService.deleteAnswer(answerId);
        return ResponseEntity.noContent().build();
    }

}
