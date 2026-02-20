package com.pooli.permission.controller;

import com.pooli.permission.domain.dto.request.MemberPermissionUpsertReqDto;
import com.pooli.permission.domain.dto.response.CommonErrorResDto;
import com.pooli.permission.domain.dto.response.MemberPermissionListResDto;
import com.pooli.permission.domain.dto.response.MemberPermissionResDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "MemberPermission", description = "구성원 권한 관련 API")
@RestController
@RequestMapping("/api/member-permissions")
public class MemberPermissionController {

    @Operation(
            summary = "구성원 권한 목록 조회",
            description = "가족관리자 또는 관리자가 familyId와 lineId를 기준으로 구성원의 권한 목록을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(
                    responseCode = "404",
                    description = "가족 또는 회선 정보가 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class))),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class)))
    })
    @GetMapping
    public ResponseEntity<MemberPermissionListResDto> getMemberPermissions(
            @Parameter(description = "가족 ID", example = "10")
            @RequestParam Long familyId,
            @Parameter(description = "회선 ID", example = "1001")
            @RequestParam Long lineId) {
        MemberPermissionResDto memberPermissionResDto = MemberPermissionResDto.builder()
                .familyId(familyId)
                .lineId(lineId)
                .permissionId(1)
                .permissionTitle("데이터 차단")
                .isEnable(Boolean.TRUE)
                .createdAt(LocalDateTime.parse("2026-02-20T12:00:00"))
                .build();

        MemberPermissionListResDto memberPermissionListResDto = MemberPermissionListResDto.builder()
                .memberPermissions(List.of(memberPermissionResDto))
                .build();
        return ResponseEntity.ok(memberPermissionListResDto);
    }

    @Operation(
            summary = "구성원 권한 부여 변경",
            description = "가족관리자 또는 관리자가 familyId와 lineId를 기준으로 권한 식별자와 활성화 여부(is_enable)를 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "변경 성공"),
            @ApiResponse(
                    responseCode = "404",
                    description = "가족, 회선 또는 권한 정보가 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class))),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class)))
    })
    @PatchMapping
    public ResponseEntity<MemberPermissionResDto> updateMemberPermission(
            @Parameter(description = "가족 ID", example = "10")
            @RequestParam Long familyId,
            @Parameter(description = "회선 ID", example = "1001")
            @RequestParam Long lineId,
            @RequestBody MemberPermissionUpsertReqDto memberPermissionUpsertReqDto) {
        MemberPermissionResDto memberPermissionResDto = MemberPermissionResDto.builder()
                .familyId(familyId)
                .lineId(lineId)
                .permissionId(memberPermissionUpsertReqDto.getPermissionId())
                .permissionTitle("데이터 차단")
                .isEnable(memberPermissionUpsertReqDto.getIsEnable())
                .createdAt(LocalDateTime.parse("2026-02-20T12:00:00"))
                .build();
        return ResponseEntity.ok(memberPermissionResDto);
    }
}
