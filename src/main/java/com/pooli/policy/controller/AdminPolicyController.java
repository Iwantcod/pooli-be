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

@Tag(name = "Policy", description = "Policy APIs")
@RestController
@RequestMapping("/api")
public class AdminPolicyController {

    @Operation(
            summary = "List all policies",
            description = "Admin only. Returns all policies including both active and inactive items."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request succeeded"),
            @ApiResponse(responseCode = "404", description = "Resource not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/policies/all")
    public ResponseEntity<List<AdminPolicyResDto>> getAllPolicies() {
        List<AdminPolicyResDto> response = List.of(
                AdminPolicyResDto.of(1001L, "Night Usage Block", "BLOCK", true, "2026-02-20T10:30:00"),
                AdminPolicyResDto.of(1002L, "Daily Data Limit", "LIMIT", true, "2026-02-20T10:31:00"),
                AdminPolicyResDto.of(1004L, "School Hours Block", "BLOCK", false, "2026-02-18T09:00:00")
        );
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Activate policy",
            description = "Admin only. Activates a policy from back office."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request succeeded"),
            @ApiResponse(responseCode = "404", description = "Resource not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping("/policies")
    public ResponseEntity<PolicyActivationResDto> activatePolicy(@RequestBody PolicyActivationReqDto request) {
        PolicyActivationResDto response =
                PolicyActivationResDto.of(request.policyId(), true, LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Deactivate policy",
            description = "Admin only. Deactivates a policy from back office."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request succeeded"),
            @ApiResponse(responseCode = "404", description = "Resource not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @DeleteMapping("/policies")
    public ResponseEntity<PolicyDeactivationResDto> deactivatePolicy(
            @Parameter(description = "Policy identifier", example = "1003")
            @RequestParam Long policyId
    ) {
        PolicyDeactivationResDto response =
                PolicyDeactivationResDto.of(policyId, false, LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Get app usage by line",
            description = "Admin only. Returns app usage statistics for a specific line."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request succeeded"),
            @ApiResponse(responseCode = "404", description = "Resource not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/policies/lines/apps/usage")
    public ResponseEntity<List<LineAppUsageResDto>> getLineAppUsage(
            @Parameter(description = "Line identifier", example = "101")
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


