package com.pooli.policy.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.policy.domain.dto.request.AdminCategoryReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyActiveReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyReqDto;
import com.pooli.policy.domain.dto.response.AdminPolicyActiveResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyCateResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyResDto;
import com.pooli.policy.service.AdminPolicyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Admin-Policy", description = "관리자용 정책 API")
@RestController
@RequestMapping("/api/admin/policies")
@RequiredArgsConstructor
public class AdminPolicyController {

	private final AdminPolicyService adminPolicyService;
	
    @Operation(
            summary = "관리자 기능: 전체 정책 목록 조회",
            description = "관리자 전용. 활성화/비활성화 포함 전체 정책 목록을 조회합니다."
    )
    @ApiResponses({   	 
            @ApiResponse(responseCode = "200", description = "정책 목록 조회 요청 성공"),
            @ApiResponse(
   	             responseCode = "403",
   	             description = """
   	                 관리자 권한 오류
   	                 
   	                 - COMMON:4301 관리자 권한이 없음
   	                 """
   	         ),
            @ApiResponse(
   	             responseCode = "500",
   	             description = """
   	                 서버 내부 오류
   	                 
   	                 - COMMON:5000 서버 내부 오류
   	                 - COMMON:5001 데이터베이스 오류
   	                 """
   	         )
    })
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @GetMapping()
    public ResponseEntity<List<AdminPolicyResDto>> getAllPolicies() {

    	 return ResponseEntity.ok(adminPolicyService.getAllPolicies());
    }

    @Operation(
            summary = "관리자 기능: 정책 추가",
            description = "관리자 전용. 백오피스에서 정책을 추가합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "정책 추가 요청 성공"),
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
                관리자 권한 없음
                
                - COMMON:4301 관리자 권한이 없음
                """
        ),
        @ApiResponse(
            responseCode = "409",
            description = """
                정책 충돌
                
                - POLICY:4901 이미 삭제된 정책
                - POLICY:4902 이미 활성화된 정책
                - POLICY:4903 기존의 차단 정책과 충돌
                """
        ),
        @ApiResponse(
            responseCode = "500",
            description = """
                서버 내부 오류
                
                - COMMON:5000 서버 내부 오류
                - COMMON:5001 데이터베이스 오류
                """
        )
    })
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @PostMapping
    public ResponseEntity<AdminPolicyResDto> createPolicy(@RequestBody AdminPolicyReqDto request) {
    	   
    	AdminPolicyResDto response = adminPolicyService.createPolicy(request);
           
        return ResponseEntity.ok(response);
    }
    
    @Operation(
            summary = "관리자 기능: 정책 수정",
            description = "관리자 전용. 백오피스에서 정책을 수정합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "정책 수정 요청 성공"),
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
                """
        ),
        @ApiResponse(
            responseCode = "403",
            description = """
                관리자 권한 없음
                
                - COMMON:4301 관리자 권한이 없음
                """
        ),
        @ApiResponse(
            responseCode = "500",
            description = """
                서버 내부 오류
                
                - COMMON:5000 서버 내부 오류
                - COMMON:5001 데이터베이스 오류
                """
        )
    })
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @PatchMapping
    public ResponseEntity<AdminPolicyResDto> updatePolicy(
    		@RequestParam("policyId") Integer policyId,
    		@RequestBody AdminPolicyReqDto request) {
        
    	AdminPolicyResDto response = adminPolicyService.updatePolicy(policyId, request);
        
        return ResponseEntity.ok(response);
    }
    

    @Operation(
            summary = "관리자 기능: 정책 삭제",
            description = "관리자 전용. 백오피스에서 정책을 삭제합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "정책 삭제 요청 성공"),
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
                관리자 권한 없음
                
                - COMMON:4301 관리자 권한이 없음
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
                
                - COMMON:5000 서버 내부 오류
                - COMMON:5001 데이터베이스 오류
                """
        )
    })
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @DeleteMapping
    public ResponseEntity<AdminPolicyResDto> deletePolicy(
            @Parameter(description = "정책 식별자", example = "1003")
            @RequestParam("policyId") Integer policyId
    ) {
    	AdminPolicyResDto response = adminPolicyService.deletePolicy(policyId);
        
        return ResponseEntity.ok(response);
    }
    
    @Operation(
            summary = "관리자 기능: 정책 활성화/비활성화",
            description = "관리자 전용. 백오피스에서 정책의 활성화 상태를 변경합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "정책 활성화 요청 성공"),
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
                -
                """
        ),
        @ApiResponse(
            responseCode = "403",
            description = """
                관리자 권한 없음
                
                - COMMON:4301 관리자 권한이 없음
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
                
                - COMMON:5000 서버 내부 오류
                - COMMON:5001 데이터베이스 오류
                """
        )
    })
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @PatchMapping("/activation")
    public ResponseEntity<AdminPolicyActiveResDto> updateActivationPolicy(
            @Parameter(description = "정책 식별자", example = "1003")
            @RequestBody AdminPolicyActiveReqDto request,
            @RequestParam("policyId") Integer policyId
    ) {
    	AdminPolicyActiveResDto response = adminPolicyService.updateActivationPolicy(policyId, request);    			
        
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "관리자 기능: 정책 카테고리 조회",
            description = "관리자 전용. 백오피스에서 정책의 카테고리를 조회합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "정책 카테고리 조회 요청 성공"),
        @ApiResponse(
            responseCode = "403",
            description = """
                관리자 권한 없음
                
                - COMMON:4301 관리자 권한이 없음
                """
        ),
        @ApiResponse(
            responseCode = "500",
            description = """
                서버 내부 오류
                
                - COMMON:5000 서버 내부 오류
                - COMMON:5001 데이터베이스 오류
                """
        )
    })
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @GetMapping("/categories")
    public ResponseEntity<List<AdminPolicyCateResDto>> getCategories() {
    	return ResponseEntity.ok(adminPolicyService.getCategories());
    }

    
    @Operation(
            summary = "관리자 기능: 정책 카테고리 추가",
            description = "관리자 전용. 백오피스에서 정책의 카테고리를 추가합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "정책 카테고리 추가 요청 성공"),
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
                """
        ),
        @ApiResponse(
            responseCode = "403",
            description = """
                관리자 권한 없음
                
                - COMMON:4301 관리자 권한이 없음
                """
        ),
        @ApiResponse(
            responseCode = "500",
            description = """
                서버 내부 오류
                
                - COMMON:5000 서버 내부 오류
                - COMMON:5001 데이터베이스 오류
                """
        )
    })
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @PostMapping("/categories")
    public ResponseEntity<AdminPolicyCateResDto> createCategory(
            @RequestBody AdminCategoryReqDto request
    ) {
    	AdminPolicyCateResDto response = adminPolicyService.createCategory(request);   			
        
        return ResponseEntity.ok(response);
    }

    
    @Operation(
            summary = "관리자 기능: 정책 카테고리 수정",
            description = "관리자 전용. 백오피스에서 정책의 카테고리를 수정합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "정책 카테고리 수정 요청 성공"),
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
                """
        ),
        @ApiResponse(
            responseCode = "403",
            description = """
                관리자 권한 없음
                
                - COMMON:4301 관리자 권한이 없음
                """
        ),
        @ApiResponse(
            responseCode = "500",
            description = """
                서버 내부 오류
                
                - COMMON:5000 서버 내부 오류
                - COMMON:5001 데이터베이스 오류
                """
        )
    })
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @PatchMapping("/categories")
    public ResponseEntity<AdminPolicyCateResDto> updateCategory(
    		@Parameter(description = "정책 카테고리 식별자", example = "1003")
            @RequestParam("policyCategoryId") Integer policyCategoryId,
            @RequestBody AdminCategoryReqDto request
    ) {
    	AdminPolicyCateResDto response = adminPolicyService.updateCategory(policyCategoryId, request); 			
        
        return ResponseEntity.ok(response);
    }

    
    @Operation(
            summary = "관리자 기능: 정책 카테고리 삭제",
            description = "관리자 전용. 백오피스에서 정책의 카테고리를 삭제합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "정책 카테고리 삭제 성공"),
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
                관리자 권한 없음
                
                - COMMON:4301 관리자 권한이 없음
                """
        ),
        @ApiResponse(
            responseCode = "500",
            description = """
                서버 내부 오류
                
                - COMMON:5000 서버 내부 오류
                - COMMON:5001 데이터베이스 오류
                """
        )
    })
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @DeleteMapping("/categories")
    public ResponseEntity<AdminPolicyCateResDto> deleteCategory(
            @Parameter(description = "정책 카테고리 식별자", example = "1003")
            @RequestParam("policyCategoryId") Integer policyCategoryId
    ) {
    	AdminPolicyCateResDto response = adminPolicyService.deleteCategory(policyCategoryId);		
        
        return ResponseEntity.ok(response);
    }

   
}
