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

@Tag(name = "Policy", description = "Policy APIs")
@RestController
@RequestMapping("/api")
public class UserPolicyController {

    @Operation(
            summary = "List active policies",
            description = "User role required. Returns active policies that a family representative can apply to the family group."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request succeeded"),
            @ApiResponse(responseCode = "404", description = "Resource not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/policies")
    public ResponseEntity<List<ActivePolicyResDto>> getActivePolicies() {
        List<ActivePolicyResDto> response = List.of(
                ActivePolicyResDto.of(1001L, "Night Usage Block", "BLOCK", "Blocks data usage from 22:00 to 06:00."),
                ActivePolicyResDto.of(1002L, "Daily Data Limit", "LIMIT", "Limits daily data usage per line."),
                ActivePolicyResDto.of(1003L, "Game App Restriction", "APP", "Restricts selected game apps.")
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Update line policies",
            description = "User role required. Bulk updates detailed policy settings for a specific line."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request succeeded"),
            @ApiResponse(responseCode = "404", description = "Resource not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PatchMapping("/policies/lines")
    public ResponseEntity<LinePolicyUpdateResDto> updateLinePolicies(
            @Parameter(description = "Line identifier", example = "101")
            @RequestParam Long lineId,
            @RequestBody LinePolicyUpdateReqDto request
    ) {
        int updatedCount = 2;
        if (request.allowedAppPolicyIds() != null) {
            updatedCount += request.allowedAppPolicyIds().size();
        }
        LinePolicyUpdateResDto response =
                LinePolicyUpdateResDto.of(lineId, updatedCount, LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Get line block policies",
            description = "User role required. Returns block policy details for a specific line."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request succeeded"),
            @ApiResponse(responseCode = "404", description = "Resource not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/policies/lines/blocks")
    public ResponseEntity<BlockPolicyResDto> getBlockPolicies(
            @Parameter(description = "Line identifier", example = "101")
            @RequestParam Long lineId
    ) {
        return ResponseEntity.ok(BlockPolicyResDto.of(lineId, true, false, true));
    }

    @Operation(
            summary = "Get line limit policies",
            description = "User role required. Returns limit policy details for a specific line."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request succeeded"),
            @ApiResponse(responseCode = "404", description = "Resource not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/policies/lines/limits")
    public ResponseEntity<LimitPolicyResDto> getLimitPolicies(
            @Parameter(description = "Line identifier", example = "101")
            @RequestParam Long lineId
    ) {
        return ResponseEntity.ok(LimitPolicyResDto.of(lineId, 1024, 20480, 80));
    }

    @Operation(
            summary = "Get app policies by line",
            description = "User role required. Returns app-level policy list for a specific line."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request succeeded"),
            @ApiResponse(responseCode = "404", description = "Resource not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/policies/lines/apps")
    public ResponseEntity<List<AppPolicyResDto>> getAppPolicies(
            @Parameter(description = "Line identifier", example = "101")
            @RequestParam Long lineId
    ) {
        List<AppPolicyResDto> response = List.of(
                AppPolicyResDto.of(301L, "YouTube", "LIMIT", true, 500),
                AppPolicyResDto.of(302L, "Instagram", "LIMIT", true, 300),
                AppPolicyResDto.of(401L, "GameX", "BLOCK", false, 0)
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Get applied policies by line",
            description = "User role required. Returns currently applied policy list for a specific line."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request succeeded"),
            @ApiResponse(responseCode = "404", description = "Resource not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/policies/lines/applied")
    public ResponseEntity<List<AppliedPolicyResDto>> getAppliedPoliciesByLine(
            @Parameter(description = "Line identifier", example = "101")
            @RequestParam Long lineId
    ) {
        List<AppliedPolicyResDto> response = List.of(
                AppliedPolicyResDto.of(1001L, "Night Usage Block", "BLOCK", "LINE", lineId, "2026-02-20T10:10:00"),
                AppliedPolicyResDto.of(1002L, "Daily Data Limit", "LIMIT", "LINE", lineId, "2026-02-20T10:12:00")
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Get applied policies by family",
            description = "User role required. Returns policies currently applied to a family."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request succeeded"),
            @ApiResponse(responseCode = "404", description = "Resource not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/policies/families")
    public ResponseEntity<List<AppliedPolicyResDto>> getFamilyPolicies(
            @Parameter(description = "Family identifier", example = "10")
            @RequestParam Long familyId
    ) {
        List<AppliedPolicyResDto> response = List.of(
                AppliedPolicyResDto.of(1001L, "Night Usage Block", "BLOCK", "FAMILY", familyId, "2026-02-20T10:20:00"),
                AppliedPolicyResDto.of(1003L, "Game App Restriction", "APP", "FAMILY", familyId, "2026-02-20T10:22:00")
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Apply family policy",
            description = "User role required. Applies an activated policy to a specific family."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request succeeded"),
            @ApiResponse(responseCode = "404", description = "Resource not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping("/policies/families")
    public ResponseEntity<FamilyPolicyChangeResDto> applyFamilyPolicy(
            @Parameter(description = "Family identifier", example = "10")
            @RequestParam Long familyId,
            @RequestBody FamilyPolicyApplyReqDto request
    ) {
        FamilyPolicyChangeResDto response =
                FamilyPolicyChangeResDto.of(familyId, request.policyId(), "APPLIED", LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Remove family policy",
            description = "User role required. Removes an applied policy from a specific family."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request succeeded"),
            @ApiResponse(responseCode = "404", description = "Resource not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @DeleteMapping("/policies/families")
    public ResponseEntity<FamilyPolicyChangeResDto> removeFamilyPolicy(
            @Parameter(description = "Family identifier", example = "10")
            @RequestParam Long familyId,
            @Parameter(description = "Policy identifier", example = "1003")
            @RequestParam Long policyId
    ) {
        FamilyPolicyChangeResDto response =
                FamilyPolicyChangeResDto.of(familyId, policyId, "REMOVED", LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }
}


