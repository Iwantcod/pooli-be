package com.pooli.permission.controller;

import com.pooli.permission.domain.dto.request.PermissionReqDto;
import com.pooli.permission.domain.dto.response.CommonErrorResDto;
import com.pooli.permission.domain.dto.response.MemberPermissionListResDto;
import com.pooli.permission.domain.dto.response.MemberPermissionResDto;
import com.pooli.permission.domain.dto.response.PermissionListResDto;
import com.pooli.permission.domain.dto.response.PermissionResDto;
import com.pooli.permission.domain.dto.response.SimpleMessageResDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Permission", description = "권한 관련 API")
@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    @Operation(
            summary = "내 권한 상태 조회",
            description = "로그인한 유저가 자신의 권한 목록과 활성화 상태를 조회한다. 세션의 사용자 정보를 기준으로 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(
                    responseCode = "404",
                    description = "사용자 또는 권한 정보가 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class))),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class)))
    })
    @GetMapping("/me")
    public ResponseEntity<MemberPermissionListResDto> getMyPermissions() {
        MemberPermissionResDto memberPermissionResDto = MemberPermissionResDto.builder()
                .familyId(10L)
                .lineId(1001L)
                .permissionId(1)
                .permissionTitle("데이터 차단")
                .isEnable(Boolean.FALSE)
                .createdAt(LocalDateTime.parse("2026-02-20T12:00:00"))
                .build();

        MemberPermissionListResDto memberPermissionListResDto = MemberPermissionListResDto.builder()
                .memberPermissions(List.of(memberPermissionResDto))
                .build();
        return ResponseEntity.ok(memberPermissionListResDto);
    }

    @Operation(
            summary = "권한 목록 조회",
            description = "관리자가 전체 권한 목록을 조회한다. 삭제 여부는 deletedAt 값으로 구분한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(
                    responseCode = "404",
                    description = "권한 정보가 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class))),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class)))
    })
    @GetMapping
    public ResponseEntity<PermissionListResDto> getPermissions() {
        PermissionResDto permissionResDto = PermissionResDto.builder()
                .permissionId(1)
                .permissionTitle("권한 이름")
                .createdAt(LocalDateTime.parse("2026-02-20T12:00:00"))
                .deletedAt(null)
                .build();

        PermissionListResDto permissionListResDto = PermissionListResDto.builder()
                .permissions(List.of(permissionResDto))
                .build();
        return ResponseEntity.ok(permissionListResDto);
    }

    @Operation(
            summary = "권한 생성",
            description = "관리자가 권한을 생성한다. 권한 이름(permissionTitle)은 필수이며 중복되지 않아야 한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공"),
            @ApiResponse(
                    responseCode = "404",
                    description = "권한 생성 대상이 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class))),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class)))
    })
    @PostMapping
    public ResponseEntity<PermissionResDto> createPermission(
            @RequestBody PermissionReqDto permissionReqDto) {
        PermissionResDto permissionResDto = PermissionResDto.builder()
                .permissionId(1)
                .permissionTitle(permissionReqDto.getPermissionTitle())
                .createdAt(LocalDateTime.parse("2026-02-20T12:00:00"))
                .deletedAt(null)
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(permissionResDto);
    }

    @Operation(
            summary = "권한 이름 수정",
            description = "관리자가 permissionId를 기준으로 권한 이름을 수정한다. 수정 대상이 없으면 404를 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(
                    responseCode = "404",
                    description = "수정 대상 권한이 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class))),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class)))
    })
    @PatchMapping
    public ResponseEntity<PermissionResDto> updatePermissionTitle(
            @Parameter(description = "권한 ID", example = "1")
            @RequestParam Integer permissionId,
            @RequestBody PermissionReqDto permissionReqDto) {
        PermissionResDto permissionResDto = PermissionResDto.builder()
                .permissionId(permissionId)
                .permissionTitle(permissionReqDto.getPermissionTitle())
                .createdAt(LocalDateTime.parse("2026-02-20T12:00:00"))
                .deletedAt(null)
                .build();
        return ResponseEntity.ok(permissionResDto);
    }

    @Operation(
            summary = "권한 삭제",
            description = "관리자가 permissionId를 기준으로 권한을 삭제 처리한다. 삭제 시점은 현재 시간으로 갱신한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "삭제 성공"),
            @ApiResponse(
                    responseCode = "404",
                    description = "삭제 대상 권한이 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class))),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류",
                    content = @Content(schema = @Schema(implementation = CommonErrorResDto.class)))
    })
    @DeleteMapping
    public ResponseEntity<SimpleMessageResDto> deletePermission(
            @Parameter(description = "권한 ID", example = "1")
            @RequestParam Integer permissionId) {
        SimpleMessageResDto simpleMessageResDto = SimpleMessageResDto.builder()
                .code("PERMISSION_DELETED")
                .message("권한이 삭제되었습니다. permissionId=" + permissionId)
                .timestamp(LocalDateTime.parse("2026-02-20T12:00:00"))
                .build();
        return ResponseEntity.ok(simpleMessageResDto);
    }
}
