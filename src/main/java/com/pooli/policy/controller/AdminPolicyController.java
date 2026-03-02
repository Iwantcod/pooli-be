package com.pooli.policy.controller;

import com.pooli.policy.domain.dto.request.PolicyActivationReqDto;
import com.pooli.policy.domain.dto.response.AdminPolicyResDto;
import com.pooli.policy.domain.dto.response.LineAppUsageResDto;
import com.pooli.policy.domain.dto.response.PolicyActivationResDto;
import com.pooli.policy.domain.dto.response.PolicyDeactivationResDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin-Policy", description = "관리자용 정책 API")
@RestController
@RequestMapping("/api/admin/policies")
public class AdminPolicyController {

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
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/all")
    public ResponseEntity<List<AdminPolicyResDto>> getAllPolicies() {
        List<AdminPolicyResDto> response = List.of(
                AdminPolicyResDto.builder()
                        .policyId(1001L)
                        .policyName("야간 사용 차단")
                        .policyType("BLOCK")
                        .active(true)
                        .updatedAt("2026-02-20T10:30:00")
                        .build(),
                AdminPolicyResDto.builder()
                        .policyId(1002L)
                        .policyName("일일 데이터 제한")
                        .policyType("LIMIT")
                        .active(true)
                        .updatedAt("2026-02-20T10:31:00")
                        .build(),
                AdminPolicyResDto.builder()
                        .policyId(1004L)
                        .policyName("등교 시간 차단")
                        .policyType("BLOCK")
                        .active(false)
                        .updatedAt("2026-02-18T09:00:00")
                        .build()
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "관리자 기능: 정책 추가 (활성화)",
            description = "관리자 전용. 백오피스에서 정책을 활성화합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "정책 활성화 요청 성공"),
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
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<PolicyActivationResDto> activatePolicy(@RequestBody PolicyActivationReqDto request) {
        PolicyActivationResDto response = PolicyActivationResDto.builder()
                .policyId(request.getPolicyId())
                .active(true)
                .activatedAt(LocalDateTime.now().toString())
                .build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "관리자 기능: 정책 삭제 (비활성화)",
            description = "관리자 전용. 백오피스에서 정책을 비활성화합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "정책 비활성화 요청 성공"),
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
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping
    public ResponseEntity<PolicyDeactivationResDto> deactivatePolicy(
            @Parameter(description = "정책 식별자", example = "1003")
            @RequestParam Long policyId
    ) {
        PolicyDeactivationResDto response = PolicyDeactivationResDto.builder()
                .policyId(policyId)
                .active(false)
                .deactivatedAt(LocalDateTime.now().toString())
                .build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "관리자 기능: 특정 구성원 앱별 사용량",
            description = "관리자 전용. 특정 구성원의 앱별 사용량 통계를 조회합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "앱별 사용량 통계 조회 요청 성공"),
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
            responseCode = "404",
            description = """
                리소스를 찾을 수 없음
                
                - POLICY:4400 해당 회선이 없음
                - POLICY:4402 해당 앱 정책 정보가 없음
                - POLICY:4403 해당 앱 정보가 없음
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
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/lines/apps/usage")
    public ResponseEntity<List<LineAppUsageResDto>> getLineAppUsage(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId
    ) {
        List<LineAppUsageResDto> response = List.of(
                LineAppUsageResDto.builder()
                        .lineId(lineId)
                        .appId(301L)
                        .appName("YouTube")
                        .usedMb(2450L)
                        .usagePercent(32)
                        .build(),
                LineAppUsageResDto.builder()
                        .lineId(lineId)
                        .appId(302L)
                        .appName("Instagram")
                        .usedMb(1210L)
                        .usagePercent(16)
                        .build(),
                LineAppUsageResDto.builder()
                        .lineId(lineId)
                        .appId(401L)
                        .appName("GameX")
                        .usedMb(980L)
                        .usagePercent(13)
                        .build()
        );
        return ResponseEntity.ok(response);
    }
}
