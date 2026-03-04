package com.pooli.permission.controller;

import com.pooli.permission.domain.dto.request.PermissionReqDto;
import com.pooli.permission.domain.dto.response.PermissionListResDto;
import com.pooli.permission.domain.dto.response.PermissionResDto;
import com.pooli.permission.domain.dto.response.SimpleMessageResDto;
import com.pooli.permission.service.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    @Operation(
            summary = "권한 목록 조회",
            description = "관리자가 전체 권한 목록을 조회한다. 삭제 여부는 deletedAt 값으로 구분한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = """
                    권한 없음

                    - COMMON:4302: 접근 권한이 없습니다.
                    """),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<PermissionListResDto> getPermissions() {
        return ResponseEntity.ok(permissionService.getPermissions());
    }

    @Operation(
            summary = "권한 생성",
            description = "관리자가 권한을 생성한다. 권한 이름(permissionTitle)은 필수이며 중복되지 않아야 한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공"),
            @ApiResponse(responseCode = "400", description = """
                    요청 값 오류

                    - COMMON:4001: 권한 이름은 비어 있을 수 없습니다. / 권한 이름은 20자 이하여야 합니다.
                    """),
            @ApiResponse(responseCode = "403", description = """
                    권한 없음

                    - COMMON:4302: 접근 권한이 없습니다.
                    """),
            @ApiResponse(responseCode = "409", description = """
                    중복 충돌

                    - PERMISSION-4900: 이미 존재하는 권한 이름입니다.
                    """),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<PermissionResDto> createPermission(
            @Valid @RequestBody PermissionReqDto permissionReqDto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(permissionService.createPermission(permissionReqDto));
    }

    @Operation(
            summary = "권한 이름 수정",
            description = "관리자가 permissionId를 기준으로 권한 이름을 수정한다. 수정 대상이 없으면 404를 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "400", description = """
                    요청 값 오류

                    - COMMON:4001: 권한 이름은 비어 있을 수 없습니다. / 권한 이름은 20자 이하여야 합니다.
                    """),
            @ApiResponse(responseCode = "403", description = """
                    권한 없음

                    - COMMON:4302: 접근 권한이 없습니다.
                    """),
            @ApiResponse(responseCode = "404", description = """
                    권한 정보 없음

                    - PERMISSION-4400: 해당 권한 정보가 존재하지 않습니다.
                    """),
            @ApiResponse(responseCode = "409", description = """
                    중복 충돌

                    - PERMISSION-4900: 이미 존재하는 권한 이름입니다.
                    """),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping
    public ResponseEntity<PermissionResDto> updatePermissionTitle(
            @Parameter(description = "권한 ID", example = "1")
            @RequestParam Integer permissionId,
            @Valid @RequestBody PermissionReqDto permissionReqDto) {
        return ResponseEntity.ok(permissionService.updatePermissionTitle(permissionId, permissionReqDto));
    }

    @Operation(
            summary = "권한 삭제",
            description = "관리자가 permissionId를 기준으로 권한을 삭제 처리한다. 삭제 시점은 현재 시간으로 갱신한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "삭제 성공"),
            @ApiResponse(responseCode = "403", description = """
                    권한 없음

                    - COMMON:4302: 접근 권한이 없습니다.
                    """),
            @ApiResponse(responseCode = "404", description = """
                    권한 정보 없음

                    - PERMISSION-4400: 해당 권한 정보가 존재하지 않습니다.
                    """),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping
    public ResponseEntity<SimpleMessageResDto> deletePermission(
            @Parameter(description = "권한 ID", example = "1")
            @RequestParam Integer permissionId) {
        permissionService.deletePermission(permissionId);
        return ResponseEntity.ok(SimpleMessageResDto.builder()
                .code("PERMISSION_DELETED")
                .message("권한이 삭제되었습니다. permissionId=" + permissionId)
                .timestamp(LocalDateTime.now())
                .build());
    }
}
