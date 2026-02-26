package com.pooli.permission.controller;

import com.pooli.permission.domain.dto.response.RepresentativeRoleTransferResDto;
import com.pooli.permission.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Role", description = "역할 관련 API")
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @Operation(
            summary = "가족관리자 역할 양도",
            description = "관리자가 현재 대표 회선 사용자와 변경 대상 회선 사용자를 기준으로 가족관리자 역할을 양도한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "양도 성공"),
            @ApiResponse(responseCode = "400", description = "PERMISSION-4004: 자기 자신에게 역할을 양도할 수 없습니다."),
            @ApiResponse(
                    responseCode = "404",
                    description = """
                            사용자 또는 회선 정보 없음

                            - PERMISSION-4402: 역할 양도 대상 사용자가 존재하지 않습니다.
                            - PERMISSION-4403: 역할 양도 출발 사용자 정보를 찾을 수 없습니다.
                            """
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = """
                            충돌

                            - PERMISSION-4901: 같은 가족 내 사용자에게만 역할 양도가 가능합니다.
                            - PERMISSION-4902: 대상 사용자는 이미 가족 대표자입니다.
                            """
            ),
            @ApiResponse(responseCode = "500", description = "PERMISSION-5001: 역할 양도 처리 중 오류가 발생했습니다.")
    })
    @PatchMapping("/representative")
    public ResponseEntity<RepresentativeRoleTransferResDto> transferRepresentativeRole(
            @Parameter(description = "현재 대표 사용자 ID", example = "101")
            @RequestParam Long currentUserId,
            @Parameter(description = "변경 대상 사용자 ID", example = "202")
            @RequestParam Long changeUserId) {
        return ResponseEntity.ok(roleService.transferRepresentativeRole(currentUserId, changeUserId));
    }
}
