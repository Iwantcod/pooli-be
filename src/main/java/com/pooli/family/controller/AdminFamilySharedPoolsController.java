package com.pooli.family.controller;

import com.pooli.family.domain.dto.request.UpdateSharedDataThresholdReqDto;
import com.pooli.family.service.FamilySharedPoolsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin-SharedPool", description = "관리자용 공유풀 API")
@RestController
@RequestMapping("/api/admin/shared-pools")
@RequiredArgsConstructor
public class AdminFamilySharedPoolsController {

    private final FamilySharedPoolsService familySharedPoolsService;

    @Operation(
            summary = "관리자 기능: 가족 공유 데이터 임계치 수정",
            description = "관리자 전용. 백오피스에서 특정 가족의 공유 데이터 사용량 알람 임계치를 수정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "임계치 수정 성공"),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @ApiResponse(responseCode = "404", description = "가족 식별자에 해당하는 가족이 존재하지 않음"),
            @ApiResponse(responseCode = "500", description = "서버 오류"),
    })
    @PreAuthorize("@authz.requireAdmin(authentication)")
    @PatchMapping("/limit")
    public ResponseEntity<Void> updateSharedDataLimit(
            @Parameter(description = "가족 식별자", example = "1")
            @RequestParam("familyId") Long familyId,
            @RequestBody UpdateSharedDataThresholdReqDto request
    ) {
        familySharedPoolsService.updateSharedDataThreshold(familyId, request);
        return ResponseEntity.ok().build();
    }
}
