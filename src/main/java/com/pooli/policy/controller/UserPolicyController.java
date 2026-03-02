package com.pooli.policy.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.policy.domain.dto.request.AppDataLimitUpdateReqDto;
import com.pooli.policy.domain.dto.request.AppPolicyCreateReqDto;
import com.pooli.policy.domain.dto.request.AppPolicyUpdateReqDto;
import com.pooli.policy.domain.dto.request.AppSpeedLimitUpdateReqDto;
import com.pooli.policy.domain.dto.request.BlockPolicyUpdateReqDto;
import com.pooli.policy.domain.dto.request.ImmediateBlockReqDto;
import com.pooli.policy.domain.dto.request.LimitPolicyUpdateReqDto;
import com.pooli.policy.domain.dto.request.RepeatBlockPolicyReqDto;
import com.pooli.policy.domain.dto.response.ActivePolicyResDto;
import com.pooli.policy.domain.dto.response.AppPolicyResDto;
import com.pooli.policy.domain.dto.response.AppliedPolicyResDto;
import com.pooli.policy.domain.dto.response.BlockPolicyResDto;
import com.pooli.policy.domain.dto.response.ImmediateBlockResDto;
import com.pooli.policy.domain.dto.response.LimitPolicyResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.service.UserPolicyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Policy", description = "정책 API")
@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class UserPolicyController {

	private final UserPolicyService userPolicyService;
	
    @Operation(
            summary = "백오피스에서 '활성화'한 전체 정책 목록 조회",
            description = "사용자 권한 필요. 가족 대표가 가족 그룹에 적용할 수 있는 활성화 정책 목록을 조회합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "활성화 정책 목록 조회 요청 성공"),
        @ApiResponse(
            responseCode = "403",
            description = """
                가족 대표자 권한 없음
                
                - COMMON:4300 가족 대표자 권한 없음
                """
        ),
        @ApiResponse(
            responseCode = "500",
            description = """
                서버 내부 오류
                
                - COMMON:5000 서버 내부 오류 발생
                - COMMON:5001 데이터베이스 오류
                """
        )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @GetMapping
    public ResponseEntity<List<ActivePolicyResDto>> getActivePolicies(  @AuthenticationPrincipal AuthUserDetails auth) {
    	    	
        return ResponseEntity.ok(userPolicyService.getActivePolicies());
    }

    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 목록 조회",
            description = "특정 구성원의 회선 ID 필요. 해당 회선의 반복적 차단 정책 목록을 조회합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "반복적 차단 정책 목록 조회 요청 성공"),
        @ApiResponse(
            responseCode = "400",
            description = """
                잘못된 요청
                
                - COMMON:4002 RequestParam 유효성 검증 실패
                - COMMON:4003 RequestParam 타입 불일치
                - COMMON:4004 필수 RequestParam 누락
                """
        ),
        @ApiResponse(
            responseCode = "403",
            description = """
                가족 대표자 권한 없음
                
                - COMMON:4300 가족 대표자 권한이 없음
                """
        ),
        @ApiResponse(
            responseCode = "404",
            description = """
                리소스를 찾을 수 없음
                
                - POLICY:4400 해당 회선이 없음
                """
        ),
        @ApiResponse(
            responseCode = "500",
            description = """
                서버 내부 오류
                
                - COMMON:5000 서버 내부 오류 발생
                - COMMON:5001 데이터베이스 오류
                """
        )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @GetMapping("/lines/repeat-block")
    public ResponseEntity<List<RepeatBlockPolicyResDto>> getReBlockPolicies(
    		@AuthenticationPrincipal AuthUserDetails auth,
            @Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId
    ) {

        return ResponseEntity.ok(userPolicyService.getRepeatBlockPolicies(lineId, auth));
    }

    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 생성",
            description = "특정 구성원의 반복적 차단 정책을 생성합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "요청 성공"),
        @ApiResponse(
            responseCode = "400",
            description = """
                잘못된 요청
                
                - COMMON:4000 요청 형식 불일치
                - COMMON:4001 요청 DTO 필드 유효성 검증 실패
                - COMMON:4006 Content-Type 불일치
                - POLICY:4000 차단 시작 시간은 종료 시간보다 이전이어야 함
                - POLICY:4001 차단/종료 시간 누락
                - POLICY:4002 차단 기간 24시간 미만으로 설정 필수
                """
        ),
        @ApiResponse(
            responseCode = "403",
            description = """
                가족 대표자 권한 없음
                
                - COMMON:4300 가족 대표자 권한이 없음
                """
        ),
        @ApiResponse(
            responseCode = "404",
            description = """
                리소스를 찾을 수 없음
                
                - POLICY:4400 해당 회선이 없음
                """
        ),
        @ApiResponse(
            responseCode = "409",
            description = """
                정책 충돌
                
                - POLICY:4904 기존의 차단 정책과 충돌
                """
        ),
        @ApiResponse(
            responseCode = "500",
            description = """
                서버 내부 오류
                
                - COMMON:5000 서버 내부 오류 발생
                - COMMON:5001 데이터베이스 오류
                """
        )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @PostMapping("/lines/repeat-block")
    public ResponseEntity<RepeatBlockPolicyResDto> createReBlockPolicies(

    		@AuthenticationPrincipal AuthUserDetails auth,
            @RequestBody RepeatBlockPolicyReqDto request
    ) {

        RepeatBlockPolicyResDto response = userPolicyService.createRepeatBlockPolicy(request, auth);
        
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 수정",
            description = "특정 구성원의 반복적 차단 ID 필요. 반복적 차단 정책을 수정합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "반복적 차단 정책 수정 요청 성공"),
        @ApiResponse(
            responseCode = "400",
            description = """
                잘못된 요청
                
                - COMMON:4000 요청 형식 불일치
                - COMMON:4001 요청 DTO 필드 유효성 검증 실패
                - COMMON:4002 RequestParam 유효성 검증 실패
                - COMMON:4003 RequestParam 타입 불일치
                - COMMON:4004 필수 RequestParam 누락
                - COMMON:4006 Content-Type 불일치
                - POLICY:4000 차단 시작 시간은 종료 시간보다 이전이어야 함
                - POLICY:4001 차단/종료 시간 누락
                - POLICY:4002 차단 기간 24시간 미만으로 설정 필수
                """
        ),
        @ApiResponse(
            responseCode = "403",
            description = """
                가족 대표자 권한 없음
                
                - COMMON:4300 가족 대표자 권한이 없음
                """
        ),
        @ApiResponse(
            responseCode = "404",
            description = """
                리소스를 찾을 수 없음
                
                - POLICY:4401 해당 반복적 차단 정보가 없음
                """
        ),
        @ApiResponse(
            responseCode = "409",
            description = """
                정책 충돌
                
                - POLICY:4904 기존의 차단 정책과 충돌
                """
        ),
        @ApiResponse(
            responseCode = "500",
            description = """
                서버 내부 오류
                
                - COMMON:5000 서버 내부 오류 발생
                - COMMON:5001 데이터베이스 오류
                """
        )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @PatchMapping("/lines/repeat-block")
    public ResponseEntity<RepeatBlockPolicyResDto> updateReBlockPolicies(
    		@AuthenticationPrincipal AuthUserDetails auth,
            @Parameter(name = "repeatBlockId", description = "반복적 차단 식별자", example = "202")
            @RequestParam("repeatBlockId") Long repeatBlockId,
            @RequestBody RepeatBlockPolicyReqDto request
    ) {
        RepeatBlockPolicyResDto response = RepeatBlockPolicyResDto.builder().build();

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원의 반복적 차단 정책 삭제",
            description = "특정 구성원의 반복적 차단 ID 필요. 반복적 차단 정책을 삭제합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "요청 성공"),
        @ApiResponse(
            responseCode = "400",
            description = """
                잘못된 요청
                
                - COMMON:4002 RequestParam 유효성 검증 실패
                - COMMON:4003 RequestParam 타입 불일치
                - COMMON:4004 필수 RequestParam 누락
                """
        ),
        @ApiResponse(
            responseCode = "403",
            description = """
                가족 대표자 권한 없음
                
                - COMMON:4300 가족 대표자 권한이 없음
                """
        ),
        @ApiResponse(
            responseCode = "404",
            description = """
                리소스를 찾을 수 없음
                
                - POLICY:4401 해당 반복적 차단 정보가 없음
                """
        ),
        @ApiResponse(
            responseCode = "409",
            description = """
                정책 충돌
                
                - POLICY:4901 이미 삭제된 정책
                """
        ),
        @ApiResponse(
            responseCode = "500",
            description = """
                서버 내부 오류
                
                - COMMON:5000 서버 내부 오류 발생
                - COMMON:5001 데이터베이스 오류
                """
        )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @DeleteMapping("/lines/repeat-block")
    public ResponseEntity<RepeatBlockPolicyResDto> deleteReBlockPolicies(
    		@AuthenticationPrincipal AuthUserDetails auth,
            @Parameter(name = "repeatBlockId", description = "반복적 차단 식별자", example = "202")
            @RequestParam("repeatBlockId") Long repeatBlockId
    ) {
    	
    	RepeatBlockPolicyResDto response = userPolicyService.deleteRepeatBlockPolicy(repeatBlockId, auth);
        
        return ResponseEntity.ok(response);
    }
    
    @Operation(
            summary = "특정 구성원의 즉시 차단 정책 조회",
            description = "특정 구성원의 회선 ID 필요. 해당 회선의 즉시 차단 정책 내용을 조회합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "즉시 차단 정책 조회 요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
                    
                    - COMMON:4002 RequestParam 유효성 검증 실패
                    - COMMON:4003 RequestParam 타입 불일치
                    - COMMON:4004 필수 RequestParam 누락
                    """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(
                responseCode = "404",
                description = """
                    리소스를 찾을 수 없음
                    
                    - POLICY:4400 해당 회선이 없음
                    """
            ),
        @ApiResponse(
                responseCode = "500",
                description = """
                    서버 내부 오류
                    
                    - COMMON:5000 서버 내부 오류 발생
                    - COMMON:5001 데이터베이스 오류
                    """
            )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @GetMapping("/lines/immediate-block")
    public ResponseEntity<ImmediateBlockResDto> getImBlockPolicies(
    		@AuthenticationPrincipal AuthUserDetails auth,
            @Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId
    ) {
    	
        ImmediateBlockResDto response = userPolicyService.getImmediateBlockPolicy(lineId, auth);
        
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원의 즉시 차단 정책 수정",
            description = "특정 구성원의 회선 ID 필요. 해당 회선의 즉시 차단 정책 내용을 수정합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "즉시 차단 정책 수정 요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
                    - COMMON:4000 요청 형식 불일치
	                - COMMON:4001 요청 DTO 필드 유효성 검증 실패
	                - COMMON:4002 RequestParam 유효성 검증 실패
	                - COMMON:4003 RequestParam 타입 불일치
	                - COMMON:4004 필수 RequestParam 누락
	                - COMMON:4006 Content-Type 불일치
	                - POLICY:4000 차단 시작 시간은 종료 시간보다 이전이어야 함
	                - POLICY:4001 차단/종료 시간 누락
	                - POLICY:4002 차단 기간 24시간 미만으로 설정 필수		                
                    """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(
                responseCode = "404",
                description = """
                    리소스를 찾을 수 없음
                    
                    - POLICY:4400 해당 회선이 없음
                    """
            ),
        @ApiResponse(
                responseCode = "409",
                description = """
                    정책 충돌
                    
                    - POLICY:4904 기존의 차단 정책과 충돌
                    """
            ),
        @ApiResponse(
                responseCode = "500",
                description = """
                    서버 내부 오류
                    
                    - COMMON:5000 서버 내부 오류 발생
                    - COMMON:5001 데이터베이스 오류
                    """
            )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @PatchMapping("/lines/immediate-block")
    public ResponseEntity<ImmediateBlockResDto> updateImBlockPolicies(
    		@AuthenticationPrincipal AuthUserDetails auth,
            @Parameter(name = "lineId", description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId,
            @RequestBody ImmediateBlockReqDto request
    ) {

        ImmediateBlockResDto response = userPolicyService.updateImmediateBlockPolicy(lineId, request, auth);
        
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 제한 정책 조회",
            description = "사용자 권한 필요. 특정 회선의 제한 정책 항목을 조회합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "제한 정책 조회 요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
	                - COMMON:4002 RequestParam 유효성 검증 실패
	                - COMMON:4003 RequestParam 타입 불일치
	                - COMMON:4004 필수 RequestParam 누락           
                    """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(
                responseCode = "404",
                description = """
                    리소스를 찾을 수 없음
                    
                    - POLICY:4400 해당 회선이 없음
                    """
            ),
        @ApiResponse(
                responseCode = "500",
                description = """
                    서버 내부 오류
                    
                    - COMMON:5000 서버 내부 오류 발생
                    - COMMON:5001 데이터베이스 오류
                    """
            )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @GetMapping("/lines/limits")
    public ResponseEntity<LimitPolicyResDto> getLimitPolicies(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId
    ) {
        return ResponseEntity.ok(LimitPolicyResDto.builder().build());
    }

    @Operation(
            summary = "특정 구성원의 하루 총 데이터 사용량 제한 제한 정책 (신규 추가 or 활성화)/비활성화",
            description = "제한 정책을 활성화하거나, 활성화했던 적이 없다면 신규 추가합니다. 혹은 비활성화합니다. 가족 대표자 권한이 필요합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "하루 총 데이터 사용량 제한 정책 활성화/비활성화 요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
	                - COMMON:4002 RequestParam 유효성 검증 실패
	                - COMMON:4003 RequestParam 타입 불일치
	                - COMMON:4004 필수 RequestParam 누락           
                    """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(
                responseCode = "404",
                description = """
                    리소스를 찾을 수 없음
                    
                    - POLICY:4400 해당 회선이 없음
                    """
            ),
        @ApiResponse(
                responseCode = "409",
                description = """
                    정책 충돌
                    
                    - POLICY:4902 이미 활성화된 정책
                    """
            ),
        @ApiResponse(
                responseCode = "500",
                description = """
                    서버 내부 오류
                    
                    - COMMON:5000 서버 내부 오류 발생
                    - COMMON:5001 데이터베이스 오류
                    """
            )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @PatchMapping("/lines/days/limits/enable-toggles")
    public ResponseEntity<LimitPolicyResDto> toggleDayLimitPolicy(
            @Parameter(description = "회선 식별자", example = "1")
            @RequestParam("lineId") Long lineId
    ) {
        LimitPolicyResDto answer = LimitPolicyResDto.builder().build();
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "특정 구성원의 하루 총 데이터 사용량 제한 정책 값 수정",
            description = "슬라이드로 결정된 값으로 수정합니다. 가족 대표자 권한이 필요합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "하루 총 데이터 사용량 제한 값 수정 요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
                    
                    - COMMON:4000 요청 형식 불일치
                    - COMMON:4001 요청 DTO 필드 유효성 검증 실패
                    - COMMON:4006 Content-Type 불일치
                    - POLICY:4003 제한할 수 있는 데이터의 범위 초과
                    """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(
                responseCode = "404",
                description = """
                    리소스를 찾을 수 없음
                    
                    - POLICY:4400 해당 회선이 없음
                    """
            ),
        @ApiResponse(
                responseCode = "409",
                description = """
                    정책 충돌
                    
                    - POLICY:4903 활성화되지 않은 정책
                    """
            ),
        @ApiResponse(
                responseCode = "500",
                description = """
                    서버 내부 오류
                    
                    - COMMON:5000 서버 내부 오류 발생
                    - COMMON:5001 데이터베이스 오류
                    """
            )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @PatchMapping("/lines/days/limits")
    public ResponseEntity<LimitPolicyResDto> updateDayLimitPolicy(
            @RequestBody LimitPolicyUpdateReqDto request
    ) {
        LimitPolicyResDto answer = LimitPolicyResDto.builder().build();
        return ResponseEntity.ok(answer);
    }
    
    @Operation(
            summary = "특정 구성원의 공유풀 데이터 사용량 제한 제한 정책 (신규 추가 or 활성화)/비활성화",
            description = "제한 정책을 활성화하거나, 활성화했던 적이 없다면 신규 추가합니다. 혹은 비활성화합니다. 가족 대표자 권한이 필요합니다."
    )
    @ApiResponses({
	    @ApiResponse(responseCode = "200", description = "데이터 사용량 제한 활성화/비활성화 요청 성공"),
	    @ApiResponse(
	            responseCode = "400",
	            description = """
	                잘못된 요청
	                
	                - COMMON:4002 RequestParam 유효성 검증 실패
	                - COMMON:4003 RequestParam 타입 불일치
	                - COMMON:4004 필수 RequestParam 누락
	                """
	        ),
	    @ApiResponse(
	            responseCode = "403",
	            description = """
	                가족 대표자 권한 없음
	                
	                - COMMON:4300 가족 대표자 권한이 없음
	                """
	        ),
	    @ApiResponse(
	            responseCode = "404",
	            description = """
	                리소스를 찾을 수 없음
	                
	                - POLICY:4400 해당 회선이 없음
	                """
	        ),
	    @ApiResponse(
	            responseCode = "500",
	            description = """
	                서버 내부 오류
	                
	                - COMMON:5000 서버 내부 오류 발생
	                - COMMON:5001 데이터베이스 오류
	                """
	    )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @PatchMapping("/lines/shares/limits/enable-toggles")
    public ResponseEntity<LimitPolicyResDto> toggleShareLimitPolicy(
            @Parameter(description = "회선 식별자", example = "1")
            @RequestParam("lineId") Long lineId
    ) {
        LimitPolicyResDto answer = LimitPolicyResDto.builder().build();
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "특정 구성원의 공유풀 데이터 사용량 제한 정책 값 수정",
            description = "슬라이드로 결정된 값으로 수정합니다. 가족 대표자 권한이 필요합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "공유풀 데이터 사용량 제한 값 수정 요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
                    
                    - COMMON:4000 요청 형식 불일치
                    - COMMON:4001 요청 DTO 필드 유효성 검증 실패
                    - COMMON:4006 Content-Type 불일치
                    - POLICY:4003 제한할 수 있는 데이터의 범위 초과
                    """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(
                responseCode = "404",
                description = """
                    리소스를 찾을 수 없음
                    
                    - POLICY:4400 해당 회선이 없음
                    """
            ),
        @ApiResponse(
                responseCode = "409",
                description = """
                    정책 충돌
                    
                    - POLICY:4903 활성화되지 않은 정책
                    """
            ),
        @ApiResponse(
                responseCode = "500",
                description = """
                    서버 내부 오류
                    
                    - COMMON:5000 서버 내부 오류 발생
                    - COMMON:5001 데이터베이스 오류
                    """
            )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @PatchMapping("/lines/shares/limits")
    public ResponseEntity<LimitPolicyResDto> updateShareLimitPolicy(
            @RequestBody LimitPolicyUpdateReqDto request
    ) {
        LimitPolicyResDto answer = LimitPolicyResDto.builder().build();
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "특정 구성원 앱 별 정책 조회",
            description = "사용자 권한 필요. 특정 회선의 앱 단위 정책 목록과 PK를 조회합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "앱 단위 정책 목록, pk 조회 요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
                        
	                - COMMON:4002 RequestParam 유효성 검증 실패
	                - COMMON:4003 RequestParam 타입 불일치
	                - COMMON:4004 필수 RequestParam 누락
	                """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(
                responseCode = "404",
                description = """
                    리소스를 찾을 수 없음
                    
                    - POLICY:4400 해당 회선이 없음
                    - POLICY:4402 해당 앱 정책 정보가 없음
                    - POLICY:4403 해당 앱 정보가 없음
                    """
            ),
        @ApiResponse(
                responseCode = "500",
                description = """
                    서버 내부 오류
                    
                    - COMMON:5000 서버 내부 오류 발생
                    - COMMON:5001 데이터베이스 오류
                    """
            )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @GetMapping("/lines/apps")
    public ResponseEntity<List<AppPolicyResDto>> getAppPolicies(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId
    ) {
        List<AppPolicyResDto> response = List.of(
                AppPolicyResDto.builder()
                        .appPolicyId(7301L)
                        .appId(301)
                        .appName("YouTube")
                        .enabled(true)
                        .dailyLimitData(500L)
                        .build(),
                AppPolicyResDto.builder()
                        .appPolicyId(7302L)
                        .appId(302)
                        .appName("Instagram")
                        .enabled(true)
                        .dailyLimitData(300L)
                        .build(),
                AppPolicyResDto.builder()
                        .appPolicyId(7303L)
                        .appId(401)
                        .appName("GameX")
                        .enabled(false)
                        .dailyLimitData(0L)
                        .build()
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 앱별 정책 신규 생성",
            description = "가족 대표자만 설정 가능합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "요청 성공"),
        @ApiResponse(
                responseCode = "400",
                description = """
                    잘못된 요청
                        
	                - COMMON:4000 요청 형식 불일치
	                - COMMON:4001 DTO 유효성 검증 실패
	                - COMMON:4006 Content-Type 불일치
	                """
            ),
        @ApiResponse(
                responseCode = "403",
                description = """
                    가족 대표자 권한 없음
                    
                    - COMMON:4300 가족 대표자 권한이 없음
                    """
            ),
        @ApiResponse(
                responseCode = "404",
                description = """
                    리소스를 찾을 수 없음
                    
                    - POLICY:4400 해당 회선이 없음
                    - POLICY:4403 해당 앱 정보가 없음
                    """
            ),
        @ApiResponse(
                responseCode = "409",
                description = """
                    정책 충돌
                    
                    - POLICY:4904 기존의 차단 정책과 충돌
                    """
            ),
        @ApiResponse(
                responseCode = "500",
                description = """
                    서버 내부 오류
                    
                    - COMMON:5000 서버 내부 오류 발생
                    - COMMON:5001 데이터베이스 오류
                    """
            )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @PostMapping("/lines/apps")
    public ResponseEntity<AppPolicyResDto> createAppPolicy(
            @RequestBody AppPolicyCreateReqDto request
    ) {
        AppPolicyResDto response = AppPolicyResDto.builder().build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 앱별 정책의 제한 데이터량(단위: Byte) 수정",
            description = "가족 대표자 권한 필요, value 단위: Byte"
    )
    @ApiResponses({
	    @ApiResponse(responseCode = "200", description = "특정 구성원 앱별 정책의 제한 데이터량 수정 요청 성공"),
	    @ApiResponse(
	            responseCode = "400",
	            description = """
	            	잘못된 요청
	            		
	                - COMMON:4000 요청 형식 불일치
	                - COMMON:4001 DTO 유효성 검증 실패
	                - COMMON:4006 Content-Type 불일치
	                - POLICY:4003 제한할 수 있는 데이터 범위 초과
	                """
	        ),
	    @ApiResponse(
	            responseCode = "403",
	            description = """
	                가족 대표자 권한 없음
	                
	                - COMMON:4300 가족 대표자 권한이 없음
	                """
	        ),
	    @ApiResponse(
	            responseCode = "404",
	            description = """
	                리소스를 찾을 수 없음
	                
	                - POLICY:4400 해당 회선이 없음
	                - POLICY:4402 해당 앱 정책 정보가 없음
	                - POLICY:4403 해당 앱 정보가 없음
	                """
	        ),
	    @ApiResponse(
	            responseCode = "500",
	            description = """
	                서버 내부 오류
	                
	                - COMMON:5000 서버 내부 오류 발생
	                - COMMON:5001 데이터베이스 오류
	                """
	        )
	})
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @PatchMapping("/lines/apps/limits")
    public ResponseEntity<AppPolicyResDto> updateAppPolicyLimit(
            @RequestBody AppDataLimitUpdateReqDto request
    ) {
        AppPolicyResDto response = AppPolicyResDto.builder().build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 앱별 정책의 제한 속도(단위: Kbps) 수정",
            description = "가족 대표자 권한 필요, value 단위: Kbps(ex: 1Mbps == 1000Kbps)"
    )
    @ApiResponses({
	    @ApiResponse(responseCode = "200", description = "특정 구성원 앱별 정책의 제한 속도 수정 요청 성공"),
	    @ApiResponse(
	            responseCode = "400",
	            description = """
	            	잘못된 요청
	            	
	                - COMMON:4000 요청 형식 불일치
	                - COMMON:4001 DTO 유효성 검증 실패
	                - COMMON:4006 Content-Type 불일치
	                - POLICY:4003 제한할 수 있는 데이터 범위 초과
	                """
	        ),
	    @ApiResponse(
	            responseCode = "403",
	            description = """
	                가족 대표자 권한 없음
	                
	                - COMMON:4300 가족 대표자 권한이 없음
	                """
	        ),
	    @ApiResponse(
	            responseCode = "404",
	            description = """
	                리소스를 찾을 수 없음
	                
	                - POLICY:4400 해당 회선이 없음
	                - POLICY:4402 해당 앱 정책 정보가 없음
	                - POLICY:4403 해당 앱 정보가 없음
	                """
	        ),
	    @ApiResponse(
	            responseCode = "500",
	            description = """
	                서버 내부 오류
	                
	                - COMMON:5000 서버 내부 오류 발생
	                - COMMON:5001 데이터베이스 오류
	                """
	        )
	})
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @PatchMapping("/lines/apps/speeds")
    public ResponseEntity<AppPolicyResDto> updateAppPolicySpeed(
            @RequestBody AppSpeedLimitUpdateReqDto request
    ) {
        AppPolicyResDto response = AppPolicyResDto.builder().build();
        return ResponseEntity.ok(response);
    }


    @Operation(
            summary = "구성원의 특정 앱 데이터 사용 정책 활성화/비활성화 토글 요청",
            description = "가족 대표자 권한 필요"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
            잘못된 요청
            
            - COMMON:4002 RequestParam 유효성 검증 실패
            - COMMON:4003 RequestParam 타입 불일치
            - COMMON:4004 필수 RequestParam 누락
            """
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = """
            권한이 없음
            
            - COMMON:4300 가족 대표자 권한이 없습니다.
            """
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = """
            리소스를 찾을 수 없음
            
            - POLICY:4402 해당 앱 정책 정보가 존재하지 않습니다.
            """
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = """
            서버 내부 오류
            
            - COMMON:5000 서버 내부 오류 발생
            - COMMON:5001 데이터베이스 오류
            """
            )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @PatchMapping("/lines/apps/enable-toggles")
    public ResponseEntity<Void> toggleAppPolicyEnable(
            @Parameter(description = "앱 정책 식별자", example = "154")
            @RequestParam("appPolicyId") Long appPolicyId
    ) {
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "구성원의 특정 앱 데이터 사용 정책 삭제",
            description = "가족 대표자 권한 필요"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
            잘못된 요청
            
            - COMMON:4002 RequestParam 유효성 검증 실패
            - COMMON:4003 RequestParam 타입 불일치
            - COMMON:4004 필수 RequestParam 누락
            """
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = """
            권한이 없음
            
            - COMMON:4300 가족 대표자 권한이 없습니다.
            """
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = """
            리소스를 찾을 수 없음
            
            - POLICY:4402 해당 앱 정책 정보가 존재하지 않습니다.
            """
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = """
            서버 내부 오류
            
            - COMMON:5000 서버 내부 오류 발생
            - COMMON:5001 데이터베이스 오류
            """
            )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @DeleteMapping("/lines/apps")
    public ResponseEntity<Void> deleteAppPolicy(
            @Parameter(description = "앱 정책 식별자", example = "154")
            @RequestParam("appPolicyId") Long appPolicyId
    ) {
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "특정 구성원에게 적용 중인 모든 정책 목록 조회(현재 적용 중인 정책)",
            description = "사용자 권한 필요. 특정 회선에 현재 적용 중인 정책 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
            잘못된 요청
            
            - COMMON:4002 RequestParam 유효성 검증 실패
            - COMMON:4003 RequestParam 타입 불일치
            - COMMON:4004 필수 RequestParam 누락
            """
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = """
            권한이 없음
            
            - COMMON:4300 가족 대표자 권한이 없습니다.
            """
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = """
            리소스를 찾을 수 없음
            
            - POLICY:4400 해당 회선이 존재하지 않습니다.
            """
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = """
            서버 내부 오류
            
            - COMMON:5000 서버 내부 오류 발생
            - COMMON:5001 데이터베이스 오류
            """
            )
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @GetMapping("/lines/applied")
    public ResponseEntity<AppliedPolicyResDto> getAppliedPoliciesByLine(
    		@AuthenticationPrincipal AuthUserDetails auth,
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId
    ) {
    	return ResponseEntity.ok(userPolicyService.getAppliedPolicies(lineId, auth));
    }

    @Operation(
            summary = "특정 구성원 제한 정책 정보 수정",
            description = "사용자 권한 필요. 제한 정책 PK 기준으로 1건만 수정하고 PK와 값을 응답합니다.",
            deprecated = true
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @PatchMapping("/lines/limits")
    public ResponseEntity<LimitPolicyResDto> updateLimitPolicy(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId,
            @Parameter(description = "제한 정책 PK", example = "7201")
            @RequestParam("limitPolicyId") Long limitPolicyId,
            @RequestBody LimitPolicyUpdateReqDto request
    ) {
        LimitPolicyResDto response = LimitPolicyResDto.builder()
                .build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 반복적 차단 정책 정보 수정",
            description = "사용자 권한 필요. 차단 정책 PK 기준으로 1건만 수정하고 PK와 값을 응답합니다.",
            deprecated = true
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @PatchMapping("/lines/blocks")
    public ResponseEntity<BlockPolicyResDto> updateBlockPolicy(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId,
            @Parameter(description = "차단 정책 PK", example = "7101")
            @RequestParam("blockPolicyId") Long blockPolicyId,
            @RequestBody BlockPolicyUpdateReqDto request
    ) {
        BlockPolicyResDto response = BlockPolicyResDto.builder()
                .build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 앱별 정책 정보 수정",
            description = "사용자 권한 필요. 앱 정책 PK 기준으로 1건만 수정하고 PK와 값을 응답합니다.",
            deprecated = true
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @PatchMapping("/lines/apps")
    public ResponseEntity<AppPolicyResDto> updateAppPolicy(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId,
            @Parameter(description = "앱 정책 PK", example = "7301")
            @RequestParam("appPolicyId") Long appPolicyId,
            @RequestBody AppPolicyUpdateReqDto request
    ) {
        AppPolicyResDto response = AppPolicyResDto.builder()
                .appPolicyId(appPolicyId)
                .appId(resolveAppId(appPolicyId))
                .appName(resolveAppName(appPolicyId))
                .enabled(request.getEnabled() != null ? request.getEnabled() : Boolean.FALSE)
                .dailyLimitData(request.getDailyLimitMb() != null ? request.getDailyLimitMb() : 0L)
                .build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "특정 구성원 반복적 차단 정책 조회",
            description = "사용자 권한 필요. 특정 회선의 차단 정책 항목과 PK를 조회합니다.",
            deprecated = true
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("hasRole('FAMILY_OWNER')")
    @GetMapping("/lines/blocks")
    public ResponseEntity<List<BlockPolicyResDto>> getBlockPolicies(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam("lineId") Long lineId
    ) {
        List<BlockPolicyResDto> response = List.of(
                BlockPolicyResDto.builder()
                        .build(),
                BlockPolicyResDto.builder()
                        .build()
        );
        return ResponseEntity.ok(response);
    }

    private Integer resolveAppId(Long appPolicyId) {
        if (Long.valueOf(7301L).equals(appPolicyId)) {
            return 301;
        }
        if (Long.valueOf(7302L).equals(appPolicyId)) {
            return 302;
        }
        if (Long.valueOf(7303L).equals(appPolicyId)) {
            return 401;
        }
        return 0;
    }

    private String resolveAppName(Long appPolicyId) {
        if (Long.valueOf(7301L).equals(appPolicyId)) {
            return "YouTube";
        }
        if (Long.valueOf(7302L).equals(appPolicyId)) {
            return "Instagram";
        }
        if (Long.valueOf(7303L).equals(appPolicyId)) {
            return "GameX";
        }
        return "Unknown";
    }

}
