package com.pooli.permission.controller;

import com.pooli.permission.domain.dto.response.CommonErrorResDto;
import com.pooli.permission.domain.dto.response.RepresentativeRoleTransferResDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Role", description = "역할 관련 API")
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    @Operation(
            summary = "가족관리자 역할 양도",
            description = "관리자가 현재 대표 회선 사용자와 변경 대상 회선 사용자를 기준으로 가족관리자 역할을 양도한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "양도 성공"),
            @ApiResponse(
                    responseCode = "404",
                    description = "대상 사용자 또는 회선 정보가 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class))),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class)))
    })
    @PatchMapping("/representative")
    public ResponseEntity<RepresentativeRoleTransferResDto> transferRepresentativeRole(
            @Parameter(description = "현재 대표 사용자 ID", example = "101")
            @RequestParam Long currentUserId,
            @Parameter(description = "변경 대상 사용자 ID", example = "202")
            @RequestParam Long changeUserId) {
        RepresentativeRoleTransferResDto representativeRoleTransferResDto = RepresentativeRoleTransferResDto.builder()
                .currentUserId(currentUserId)
                .changeUserId(changeUserId)
                .build();
        return ResponseEntity.ok(representativeRoleTransferResDto);
    }
}
