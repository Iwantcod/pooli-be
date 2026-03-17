package com.pooli.family.controller;

import com.pooli.family.domain.dto.request.UpdateSharedDataThresholdReqDto;
import com.pooli.family.domain.dto.response.FamilySharedPoolResDto;
import com.pooli.family.service.FamilySharedPoolsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Shared Pool", description = "공유 데이터풀 조회 및 관리 API")
@RestController
@RequestMapping("/api/admin/shared-pools")
@RequiredArgsConstructor
public class AdminFamilySharedPoolsController {

    private final FamilySharedPoolsService familySharedPoolsService;

    @Operation(
            summary = "Admin: get family shared pool",
            description = "Returns the shared pool summary for the target family."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Shared pool lookup succeeded"),
            @ApiResponse(responseCode = "403", description = "Admin role required"),
            @ApiResponse(responseCode = "404", description = "Shared pool not found"),
            @ApiResponse(responseCode = "500", description = "Server error"),
    })
    @PreAuthorize("@authz.requireAdmin(authentication)")
    @GetMapping
    public ResponseEntity<FamilySharedPoolResDto> getFamilySharedPool(
            @Parameter(description = "Family identifier", example = "278")
            @RequestParam("familyId") Long familyId
    ) {
        FamilySharedPoolResDto response = familySharedPoolsService.getFamilySharedPool(familyId);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Admin: update shared pool threshold",
            description = "Updates the shared pool threshold for the target family."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Threshold update succeeded"),
            @ApiResponse(responseCode = "403", description = "Admin role required"),
            @ApiResponse(responseCode = "404", description = "Shared pool not found"),
            @ApiResponse(responseCode = "500", description = "Server error"),
    })
    @PreAuthorize("@authz.requireAdmin(authentication)")
    @PatchMapping("/limit")
    public ResponseEntity<Void> updateSharedDataLimit(
            @Parameter(description = "Family identifier", example = "278")
            @RequestParam("familyId") Long familyId,
            @RequestBody UpdateSharedDataThresholdReqDto request
    ) {
        familySharedPoolsService.updateSharedDataThreshold(familyId, request);
        return ResponseEntity.ok().build();
    }
}
