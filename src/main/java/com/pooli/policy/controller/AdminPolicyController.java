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

@Tag(name = "정책", description = "정책 API")
@RestController
@RequestMapping("/api")
public class AdminPolicyController {

    @Operation(
            summary = "전체 정책 목록 조회",
            description = "관리자 전용. 활성화/비활성화를 포함한 전체 정책 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/policies/all")
    public ResponseEntity<List<AdminPolicyResDto>> getAllPolicies() {
        List<AdminPolicyResDto> response = List.of(
                AdminPolicyResDto.of(1001L, "야간 사용 차단", "BLOCK", true, "2026-02-20T10:30:00"),
                AdminPolicyResDto.of(1002L, "일일 데이터 제한", "LIMIT", true, "2026-02-20T10:31:00"),
                AdminPolicyResDto.of(1004L, "등교 시간 차단", "BLOCK", false, "2026-02-18T09:00:00")
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "정책 활성화",
            description = "관리자 전용. 백오피스에서 정책을 활성화합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PostMapping("/policies")
    public ResponseEntity<PolicyActivationResDto> activatePolicy(@RequestBody PolicyActivationReqDto request) {
        PolicyActivationResDto response =
                PolicyActivationResDto.of(request.policyId(), true, LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "정책 비활성화",
            description = "관리자 전용. 백오피스에서 정책을 비활성화합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @DeleteMapping("/policies")
    public ResponseEntity<PolicyDeactivationResDto> deactivatePolicy(
            @Parameter(description = "정책 식별자", example = "1003")
            @RequestParam Long policyId
    ) {
        PolicyDeactivationResDto response =
                PolicyDeactivationResDto.of(policyId, false, LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "회선 앱 사용량 조회",
            description = "관리자 전용. 특정 회선의 앱 사용량 통계를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/policies/lines/apps/usage")
    public ResponseEntity<List<LineAppUsageResDto>> getLineAppUsage(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId
    ) {
        List<LineAppUsageResDto> response = List.of(
                LineAppUsageResDto.of(lineId, 301L, "YouTube", 2450L, 32),
                LineAppUsageResDto.of(lineId, 302L, "Instagram", 1210L, 16),
                LineAppUsageResDto.of(lineId, 401L, "GameX", 980L, 13)
        );
        return ResponseEntity.ok(response);
    }
}


