package com.pooli.policy.controller;

import com.pooli.policy.domain.dto.request.FamilyPolicyApplyReqDto;
import com.pooli.policy.domain.dto.request.LinePolicyUpdateReqDto;
import com.pooli.policy.domain.dto.response.ActivePolicyResDto;
import com.pooli.policy.domain.dto.response.AppliedPolicyResDto;
import com.pooli.policy.domain.dto.response.AppPolicyResDto;
import com.pooli.policy.domain.dto.response.BlockPolicyResDto;
import com.pooli.policy.domain.dto.response.FamilyPolicyChangeResDto;
import com.pooli.policy.domain.dto.response.LinePolicyUpdateResDto;
import com.pooli.policy.domain.dto.response.LimitPolicyResDto;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "정책", description = "정책 API")
@RestController
@RequestMapping("/api")
public class UserPolicyController {

    @Operation(
            summary = "활성화 정책 목록 조회",
            description = "사용자 권한 필요. 가족 대표가 가족 그룹에 적용할 수 있는 활성화 정책 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/policies")
    public ResponseEntity<List<ActivePolicyResDto>> getActivePolicies() {
        List<ActivePolicyResDto> response = List.of(
                ActivePolicyResDto.builder()
                        .policyId(1001L)
                        .policyName("야간 사용 차단")
                        .policyType("BLOCK")
                        .description("22:00부터 06:00까지 데이터 사용을 차단합니다.")
                        .build(),
                ActivePolicyResDto.builder()
                        .policyId(1002L)
                        .policyName("일일 데이터 제한")
                        .policyType("LIMIT")
                        .description("회선별 일일 데이터 사용량을 제한합니다.")
                        .build(),
                ActivePolicyResDto.builder()
                        .policyId(1003L)
                        .policyName("게임 앱 제한")
                        .policyType("APP")
                        .description("선택한 게임 앱을 제한합니다.")
                        .build()
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "회선 정책 수정",
            description = "사용자 권한 필요. 특정 회선의 정책 상세 설정값을 일괄 수정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/policies/lines")
    public ResponseEntity<LinePolicyUpdateResDto> updateLinePolicies(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId,
            @RequestBody LinePolicyUpdateReqDto request
    ) {
        int updatedCount = 2;
        if (request.getAllowedAppPolicyIds() != null) {
            updatedCount += request.getAllowedAppPolicyIds().size();
        }
        LinePolicyUpdateResDto response =
                LinePolicyUpdateResDto.builder()
                        .lineId(lineId)
                        .updatedPolicyCount(updatedCount)
                        .updatedAt(LocalDateTime.now().toString())
                        .build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "회선 차단 정책 조회",
            description = "사용자 권한 필요. 특정 회선의 차단 정책 상세 정보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/policies/lines/blocks")
    public ResponseEntity<BlockPolicyResDto> getBlockPolicies(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId
    ) {
        return ResponseEntity.ok(
                BlockPolicyResDto.builder()
                        .lineId(lineId)
                        .blockRoaming(false)
                        .blockPaidContent(true)
                        .build()
        );
    }

    @Operation(
            summary = "회선 제한 정책 조회",
            description = "사용자 권한 필요. 특정 회선의 제한 정책 상세 정보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/policies/lines/limits")
    public ResponseEntity<LimitPolicyResDto> getLimitPolicies(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId
    ) {
        return ResponseEntity.ok(
                LimitPolicyResDto.builder()
                        .lineId(lineId)
                        .dailyLimitMb(1024)
                        .monthlyLimitMb(20480)
                        .warningThresholdPercent(80)
                        .build()
        );
    }

    @Operation(
            summary = "회선 앱 정책 조회",
            description = "사용자 권한 필요. 특정 회선의 앱 단위 정책 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/policies/lines/apps")
    public ResponseEntity<List<AppPolicyResDto>> getAppPolicies(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId
    ) {
        List<AppPolicyResDto> response = List.of(
                AppPolicyResDto.builder()
                        .appId(301L)
                        .appName("YouTube")
                        .policyType("LIMIT")
                        .enabled(true)
                        .dailyLimitMb(500)
                        .build(),
                AppPolicyResDto.builder()
                        .appId(302L)
                        .appName("Instagram")
                        .policyType("LIMIT")
                        .enabled(true)
                        .dailyLimitMb(300)
                        .build(),
                AppPolicyResDto.builder()
                        .appId(401L)
                        .appName("GameX")
                        .policyType("BLOCK")
                        .enabled(false)
                        .dailyLimitMb(0)
                        .build()
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "회선 적용 정책 목록 조회",
            description = "사용자 권한 필요. 특정 회선에 현재 적용 중인 정책 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/policies/lines/applied")
    public ResponseEntity<List<AppliedPolicyResDto>> getAppliedPoliciesByLine(
            @Parameter(description = "회선 식별자", example = "101")
            @RequestParam Long lineId
    ) {
        List<AppliedPolicyResDto> response = List.of(
                AppliedPolicyResDto.builder()
                        .policyId(1001L)
                        .policyName("야간 사용 차단")
                        .policyType("BLOCK")
                        .appliedTarget("LINE")
                        .targetId(lineId)
                        .appliedAt("2026-02-20T10:10:00")
                        .build(),
                AppliedPolicyResDto.builder()
                        .policyId(1002L)
                        .policyName("일일 데이터 제한")
                        .policyType("LIMIT")
                        .appliedTarget("LINE")
                        .targetId(lineId)
                        .appliedAt("2026-02-20T10:12:00")
                        .build()
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "가족 적용 정책 목록 조회",
            description = "사용자 권한 필요. 가족에 현재 적용 중인 정책 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/policies/families")
    public ResponseEntity<List<AppliedPolicyResDto>> getFamilyPolicies(
            @Parameter(description = "가족 식별자", example = "10")
            @RequestParam Long familyId
    ) {
        List<AppliedPolicyResDto> response = List.of(
                AppliedPolicyResDto.builder()
                        .policyId(1001L)
                        .policyName("야간 사용 차단")
                        .policyType("BLOCK")
                        .appliedTarget("FAMILY")
                        .targetId(familyId)
                        .appliedAt("2026-02-20T10:20:00")
                        .build(),
                AppliedPolicyResDto.builder()
                        .policyId(1003L)
                        .policyName("게임 앱 제한")
                        .policyType("APP")
                        .appliedTarget("FAMILY")
                        .targetId(familyId)
                        .appliedAt("2026-02-20T10:22:00")
                        .build()
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "가족 정책 적용",
            description = "사용자 권한 필요. 활성화된 정책을 특정 가족에 적용합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PostMapping("/policies/families")
    public ResponseEntity<FamilyPolicyChangeResDto> applyFamilyPolicy(
            @Parameter(description = "가족 식별자", example = "10")
            @RequestParam Long familyId,
            @RequestBody FamilyPolicyApplyReqDto request
    ) {
        FamilyPolicyChangeResDto response =
                FamilyPolicyChangeResDto.builder()
                        .familyId(familyId)
                        .policyId(request.getPolicyId())
                        .status("APPLIED")
                        .processedAt(LocalDateTime.now().toString())
                        .build();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "가족 정책 제거",
            description = "사용자 권한 필요. 특정 가족에 적용된 정책을 제거합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "404", description = "리소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @DeleteMapping("/policies/families")
    public ResponseEntity<FamilyPolicyChangeResDto> removeFamilyPolicy(
            @Parameter(description = "가족 식별자", example = "10")
            @RequestParam Long familyId,
            @Parameter(description = "정책 식별자", example = "1003")
            @RequestParam Long policyId
    ) {
        FamilyPolicyChangeResDto response =
                FamilyPolicyChangeResDto.builder()
                        .familyId(familyId)
                        .policyId(policyId)
                        .status("REMOVED")
                        .processedAt(LocalDateTime.now().toString())
                        .build();
        return ResponseEntity.ok(response);
    }
}


