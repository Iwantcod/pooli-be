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
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
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
            summary = "관리자 기능: 정책 추가 ( 활성화)",
            description = "관리자 전용. 백오피스에서 정책을 활성화합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
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
            summary = "관리자 기능: 정책 삭제 ( 비활성화)",
            description = "관리자 전용. 백오피스에서 정책을 비활성화합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
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
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
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
