package com.pooli.question.controller;

import com.pooli.question.domain.dto.response.QuestionCategoryListResDto;
import com.pooli.question.domain.dto.response.QuestionCategoryResDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "QuestionCategory", description = "문의 카테고리 관련 API")
@RestController
@RequestMapping("/api/question-categories")
public class QuestionCategoryController {

    @Operation(
            summary = "문의 카테고리 목록 조회",
            description = "관리자 및 유저가 문의 카테고리 전체 목록을 조회한다. 삭제 여부는 deletedAt 값으로 구분한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "카테고리 정보가 존재하지 않음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping
    public ResponseEntity<QuestionCategoryListResDto> getQuestionCategories() {
        QuestionCategoryResDto questionCategoryResDto = QuestionCategoryResDto.builder()
                .questionCategoryId(1)
                .questionCategoryName("요금제 문의")
                .build();

        QuestionCategoryListResDto questionCategoryListResDto = QuestionCategoryListResDto.builder()
                .questionCategories(List.of(questionCategoryResDto))
                .build();
        return ResponseEntity.ok(questionCategoryListResDto);
    }
}
