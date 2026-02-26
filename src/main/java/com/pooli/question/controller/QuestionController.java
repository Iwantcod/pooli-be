package com.pooli.question.controller;

import java.util.Collections;
import java.util.List;

import com.pooli.question.domain.dto.response.*;
import com.pooli.question.service.QuestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.common.dto.PagingResDto;
import com.pooli.question.domain.dto.request.QuestionCreateReqDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "question", description = "문의 사항 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/questions")
public class QuestionController {

	private final QuestionService questionService;

	@Operation(
	    summary = "문의사항 생성 요청",
	    description = "새로운 문의사항을 생성한다"
	)
	@ApiResponses({
	    @ApiResponse(responseCode = "201", description = "문의사항 생성 성공"),
	    @ApiResponse(responseCode = "400", description = "잘못된 요청"),
	    @ApiResponse(responseCode = "500", description = "서버 오류"),
	        
	})
	@PostMapping
	public ResponseEntity<QuestionCreateResDto> createQuestion( @Valid @RequestBody QuestionCreateReqDto request) {
		QuestionCreateResDto res = questionService.createQuestion(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(res);
	}
	
	@Operation(
	    summary = "문의사항 삭제 요청",
	    description = "특정 문의사항을 삭제한다"
	)
	@ApiResponses({
	    @ApiResponse(responseCode = "204", description = "문의사항 삭제 성공"),
	    @ApiResponse(responseCode = "400", description = "잘못된 요청"),
	    @ApiResponse(responseCode = "404", description = "문의사항을 찾을 수 없음"),    
	})
	@DeleteMapping
	public ResponseEntity<Void> deleteQuestion(@RequestParam(name="questionId") Long questionId) {
		questionService.deleteQuestion(questionId);
		return ResponseEntity.noContent().build();
	}
	
	@Operation(
	    summary = "문의사항 목록 조회 요청",
	    description = "문의사항 목록을 조회한다"
	)
	@ApiResponses({
	    @ApiResponse(responseCode = "200", description = "문의사항 조회 성공"),
	    @ApiResponse(responseCode = "404", description = "문의사항 정보가 존재하지 않음"),
	    @ApiResponse(responseCode = "500", description = "서버 오류"),
	        
	})
	@GetMapping
	public ResponseEntity<PagingResDto<QuestionListResDto>> selectQuestion(
			@RequestParam(name="categories") String categories,
			@RequestParam(name="isAnswered", required = false) Boolean isAnswered,
			@RequestParam(name="pageNumber") Integer page,
			@RequestParam(name="pageSize") Integer size
			) {

		PagingResDto<QuestionListResDto> result =
				questionService.selectQuestion(categories, isAnswered, page, size);

		return ResponseEntity.ok(result);
	}
	
	@Operation(
	    summary = "문의사항 상세 내용 조회 요청",
	    description = "문의사항 상세 내용을 조회한다"
	)
	@ApiResponses({
	    @ApiResponse(responseCode = "200", description = "문의사항 상세 내용 조회 성공"),
	    @ApiResponse(responseCode = "404", description = "문의사항 상세 정보가 존재하지 않음"),
	    @ApiResponse(responseCode = "500", description = "서버 오류"),
	        
	})
	@GetMapping("/details")
	public ResponseEntity<QuestionResDto> selectDetailQuestion(
			@RequestParam(name="questionId") Long questionId
			) {
		return ResponseEntity.ok(
			    QuestionResDto.builder().build()
			);
	}

	@Operation(
			summary = "문의 카테고리 목록 조회",
			description = "관리자 및 유저가 문의 카테고리 전체 목록을 조회한다. 삭제 여부는 deletedAt 값으로 구분한다.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "조회 성공"),
			@ApiResponse(responseCode = "404", description = "QUESTION:4041 카테고리가 존재하지 않음"),
			@ApiResponse(responseCode = "500", description = "서버 오류")
	})
	@GetMapping("/categories")
	public ResponseEntity<QuestionCategoryListResDto> getQuestionCategories() {

		QuestionCategoryListResDto response =  questionService.getQuestionCategories();

		return ResponseEntity.ok(response);
	}
}
