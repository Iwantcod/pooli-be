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

	/*TodoList :
			- Session에 따른 Common validation 추가 시 각자 Session에 따른 유효성 검사 추가 필요
	* */

	private final QuestionService questionService;

	@Operation(
	    summary = "문의사항 생성 요청",
	    description = "새로운 문의사항을 생성한다"
	)
	@ApiResponses({
	    @ApiResponse(responseCode = "201", description = "문의사항 생성 성공"),
	    @ApiResponse(responseCode = "400",
				description = """
						잘못된 요청
							
						- COMMON:4000 요청 형식 불일치
						- COMMON:4001 요청 DTO 필드 유효성 검증 실패
						- COMMON:4006 Content-Type 불일치
						- UPLOAD:4002 파일 개수 초과
					"""),
	    @ApiResponse(responseCode = "500",
				description = """
						서버 오류
						
						- COMMON:5000 서버 내부 오류 발생
						- COMMON:5001 데이터베이스 오류
					""")
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
	    @ApiResponse(responseCode = "400",
				description = """
						잘못된 요청
						
						 - COMMON:4002 RequestParam 유효성 검증 실패
						 - COMMON:4003 RequestParam 타입 불일치
						 - COMMON:4004 필수 RequestParam 누락
					"""),
	    @ApiResponse(responseCode = "404", description = "QUESTION:4042:해당 문의사항이 존재하지 않습니다."),
		@ApiResponse(responseCode = "500",
				description = """
					서버 오류
					
					- COMMON:5000 서버 내부 오류 발생
					- COMMON:5001 데이터베이스 오류
				""")
	})
	@DeleteMapping
	public ResponseEntity<Void> deleteQuestion(@RequestParam(name="questionId") Long questionId) {
		questionService.deleteQuestion(questionId);
		return ResponseEntity.noContent().build();
	}
	
	@Operation(
	    summary = "유저 문의사항 목록 조회 요청",
	    description = "유저의 문의사항 목록을 조회한다"
	)
	@ApiResponses({
	    @ApiResponse(responseCode = "200", description = "문의사항 조회 성공"),
		@ApiResponse(responseCode = "400",
				description = """
					잘못된 요청
					
					 - COMMON:4002 RequestParam 유효성 검증 실패
					 - COMMON:4003 RequestParam 타입 불일치
					 - COMMON:4004 필수 RequestParam 누락
					 - COMMON:4008 페이지 크기(size)가 올바르지 않습니다.
					 - COMMON:4007 페이지 번호가 올바르지 않습니다.
				"""),
	    @ApiResponse(responseCode = "404", description = "문의사항 정보가 존재하지 않음"),
	    @ApiResponse(responseCode = "500", description = "서버 오류"),
	        
	})
	@GetMapping("/users")
	public ResponseEntity<PagingResDto<QuestionListResDto>> selectQuestion(
			@RequestParam(name="categoryIds", required = false) List<Long> categoryIds,
			@RequestParam(name="lineId") Long lineId,
			@RequestParam(name="isAnswered", required = false) Boolean isAnswered,
			@RequestParam(name="pageNumber") Integer page,
			@RequestParam(name="pageSize") Integer size
	) {

		PagingResDto<QuestionListResDto> result =
				questionService.selectQuestion(categoryIds, lineId, isAnswered, page, size);

		return ResponseEntity.ok(result);
	}

	@Operation(
			summary = "백오피스 문의사항 목록 조회 요청",
			description = "백오피스 문의사항 목록을 조회한다"
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "문의사항 조회 성공"),
			@ApiResponse(responseCode = "400",
					description = """
					잘못된 요청
					
					 - COMMON:4002 RequestParam 유효성 검증 실패
					 - COMMON:4003 RequestParam 타입 불일치
					 - COMMON:4004 필수 RequestParam 누락
					 - COMMON:4008 페이지 크기(size)가 올바르지 않습니다.
					 - COMMON:4007 페이지 번호가 올바르지 않습니다.
				"""),
			@ApiResponse(responseCode = "404", description = "문의사항 정보가 존재하지 않음"),
			@ApiResponse(responseCode = "500", description = "서버 오류"),

	})
	@GetMapping("/admins")
	public ResponseEntity<PagingResDto<QuestionListResDto>> selectQuestionAdmin(
			@RequestParam(required = false) List<Long> categoryIds,
			@RequestParam(required = false) Boolean isAnswered,
			@RequestParam(required = false) Long lineId,
			@RequestParam Integer pageNumber,
			@RequestParam Integer pageSize
	) {
		return ResponseEntity.ok(
				questionService.selectQuestionAdmin(categoryIds, isAnswered, lineId, pageNumber, pageSize)
		);
	}
	
	@Operation(
	    summary = "문의사항 상세 내용 조회 요청",
	    description = "문의사항 상세 내용을 조회한다"
	)
	@ApiResponses({
	    @ApiResponse(responseCode = "200", description = "문의사항 상세 내용 조회 성공"),
		@ApiResponse(responseCode = "400",
				description = """
					잘못된 요청
					
					 - COMMON:4002 RequestParam 유효성 검증 실패
					 - COMMON:4003 RequestParam 타입 불일치
					 - COMMON:4004 필수 RequestParam 누락
				"""),
	    @ApiResponse(responseCode = "404", description = "문의사항 상세 정보가 존재하지 않음"),
	    @ApiResponse(responseCode = "500", description = "서버 오류"),
	        
	})
	@GetMapping("/details")
	public ResponseEntity<QuestionResDto> selectDetailQuestion(
			@RequestParam(name="questionId") Long questionId
			) {
		return ResponseEntity.ok(
			    questionService.selectDetailQuestion(questionId)
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
