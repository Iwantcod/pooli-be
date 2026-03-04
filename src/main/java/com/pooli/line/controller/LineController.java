package com.pooli.line.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.validator.LineOwnershipValidator;
import com.pooli.line.domain.dto.request.UpdateIndividualThresholdReqDto;
import com.pooli.line.domain.dto.response.IndividualThresholdResDto;
import com.pooli.line.domain.dto.response.LineSimpleResDto;
import com.pooli.line.service.LineService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

@Tag(name = "Line", description = "회선 관련 API")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/lines")
public class LineController {
	
	private final LineService lineService;
	private final LineOwnershipValidator ownershipValidator;
	
	/**
	 * getLines()
	 * - 로그인 유저 소유의 회선 목록 조회
	 * 
	 * @param principal : 로그인 세션 정보
	 * @return
	 */
    @Operation(
            summary = "유저 회선 조회",
            description = "유저가 가지고 있는 회선들을 조회한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회선 조회 성공"),
            @ApiResponse(
                    responseCode = "404",
                    description = """
                        리소스 없음
                        
                        - LINE:4401 관련 회선 정보가 존재하지 않습니다
                        """
                ),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping
    public ResponseEntity<List<LineSimpleResDto>> getLines(
             @AuthenticationPrincipal AuthUserDetails principal
    ) {
    	
        List<LineSimpleResDto> response = lineService.getLines(principal.getUserId(), principal.getLineId());
        return ResponseEntity.ok(response);
    }
    
    
    /**
     * 
     * switchLine()
     * - 특정 회선으로 사용자 세션 정보 변경
     * 
     * @param principal : 로그인 세션 정보
     * @param lineId : 변경 대상 회선 식별자
     * @param request
     * @param response
     * @return
     */
    @Operation(
            summary = "유저 회선 변경",
            description = "유저가 가지고 있는 회선 중 특정 회선으로 세션 정보를 변경한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회선 변경 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
                        잘못된 요청
                        
                        - COMMON:4002 RequestParam 유효성 검증 실패
                        - COMMON:4003 RequestParam 타입 불일치
                        - COMMON:4004 필수 RequestParam 누락
                        """
                ),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(
                    responseCode = "403",
                    description = """
                        권한 부족
                        
                        - COMMON:4302 접근 권한 없음
                        - COMMON:4303 사용자 권한이 없음
                        """
                ),
            @ApiResponse(responseCode = "404", description = "회선 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PreAuthorize("@authz.requireUser(authentication)")
    @PatchMapping
    public ResponseEntity<Void> switchLine(
	    		@AuthenticationPrincipal AuthUserDetails principal,
	            @NotNull @RequestParam(required = true, name = "lineId") Long lineId,
	            HttpServletRequest request,
	            HttpServletResponse response
    		){
    	
    	
    	lineService.switchLine(principal, lineId, request, response);
    	return ResponseEntity.ok().build();
    	
    }

    /**
     * 
     * getIndividualThreshold
     * - 유저 회선 별 개인 임계치 활성화 여부 및 임계치 조회
     * 
     * @param principal
     * @param lineId
     * @return
     */
    @Operation(
            summary = "유저 회선별 개인 임계치 조회",
            description = "유저 회선별 설정된 개인 임계치를 조회한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "임계치 조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
                        잘못된 요청
                        
                        - COMMON:4002 RequestParam 유효성 검증 실패
                        - COMMON:4003 RequestParam 타입 불일치
                        - COMMON:4004 필수 RequestParam 누락
                        """
                ),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(
                    responseCode = "403",
                    description = """
                        권한 부족
                        
                        - COMMON:4302 접근 권한 없음
                        """
                ),
            @ApiResponse(responseCode = "404", description = "회선 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/thresholds")
    public ResponseEntity<IndividualThresholdResDto> getIndividualThreshold(
    		@AuthenticationPrincipal AuthUserDetails principal,
            @Parameter(description = "회선 ID", example = "1")
    		@NotNull
            @RequestParam(required = true, name = "lineId") Long lineId
    ) {
    	
        return ResponseEntity.ok(lineService.getIndividualThreshold(lineId,principal));
    }

    /**
     * updateIndividualThreshold
     * - 유저 회선별 설정된 개인 임계치 활성화 여부 / 임계치 값을 수정
     * 
     * @param principal
     * @param request
     * @return
     */
    @Operation(
            summary = "유저 회선별 개인 임계치 수정",
            description = "유저 회선별 설정된 개인 임계치를 수정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "임계치 수정 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
                        잘못된 요청
                        
                        - COMMON:4001 요청 DTO 필드 유효성 검증 실패
                        """
                ),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(
                    responseCode = "403",
                    description = """
                        권한 부족
                        
                        - COMMON:4302 접근 권한 없음
                        """
                ),
            @ApiResponse(responseCode = "404", description = "회선 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PatchMapping("/thresholds")
    public ResponseEntity<Void> updateIndividualThreshold(            
    		@AuthenticationPrincipal AuthUserDetails principal,
    		@RequestBody UpdateIndividualThresholdReqDto request
    ) {
    	lineService.updateIndividualThreshold(principal.getLineId(), request);

        return ResponseEntity.ok().build();
    }
}
