package com.pooli.permission.controller;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.permission.domain.dto.request.MemberPermissionBulkUpsertReqDto;
import com.pooli.permission.domain.dto.response.MemberPermissionListResDto;
import com.pooli.permission.service.MemberPermissionService;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "MemberPermission", description = "구성원 권한 관련 API")
@Validated
@RestController
@RequestMapping("/api/member-permissions")
@RequiredArgsConstructor
public class MemberPermissionController {

    private final MemberPermissionService memberPermissionService;

    @Operation(summary = "내 권한 상태 조회", description = "로그인한 유저가 자신의 권한 목록과 활성화 상태를 조회한다. 세션의 사용자 정보를 기준으로 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = """
                    회선 정보 없음

                    - PERMISSION-4401: 대상 회선 정보가 존재하지 않습니다.
                    """),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/me")
    public ResponseEntity<MemberPermissionListResDto> getMyPermissions(
            @AuthenticationPrincipal AuthUserDetails userDetails) {
        return ResponseEntity.ok(memberPermissionService.getMyPermissions(userDetails.getLineId()));
    }

    @Operation(summary = "가족 전체 구성원 권한 목록 조회", description = """
            가족 내 전체 구성원 권한 목록을 조회한다.

            - 가족관리자: lineId 무시, 세션 lineId 자동 사용
            - 관리자: 조회할 대상의 lineId 필수""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = """
                    요청 파라미터 오류

                    - COMMON:4003: lineId 타입이 올바르지 않습니다.
                    - COMMON:4004: lineId가 누락되었습니다. (관리자 전용)
                    """),
            @ApiResponse(responseCode = "403", description = """
                    권한 없음

                    - COMMON:4302: 접근 권한이 없습니다.
                    """),
            @ApiResponse(responseCode = "404", description = """
                    회선 정보 없음

                    - PERMISSION-4401: 대상 회선 정보가 존재하지 않습니다.
                    """),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @GetMapping("/family")
    public ResponseEntity<MemberPermissionListResDto> getFamilyMemberPermissions(
            @Parameter(description = "회선 ID (관리자 전용)", example = "1001") @RequestParam(required = false) Long lineId,
            @AuthenticationPrincipal AuthUserDetails userDetails) {
        return ResponseEntity.ok(memberPermissionService.getFamilyMemberPermissions(lineId, userDetails));
    }

    @Operation(summary = "구성원 권한 목록 조회", description = "가족관리자 또는 관리자가 lineId를 기준으로 구성원의 권한 목록을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = """
                    요청 파라미터 오류

                    - COMMON:4003: lineId 타입이 올바르지 않습니다.
                    - COMMON:4004: lineId가 누락되었습니다.
                    """),
            @ApiResponse(responseCode = "403", description = """
                    권한 없음

                    - COMMON:4302: 접근 권한이 없습니다.
                    """),
            @ApiResponse(responseCode = "404", description = """
                    회선 정보 없음

                    - PERMISSION-4401: 대상 회선 정보가 존재하지 않습니다.
                    """),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @GetMapping
    public ResponseEntity<MemberPermissionListResDto> getMemberPermissions(
            @Parameter(description = "회선 ID", example = "1001") @RequestParam Long lineId,
            @AuthenticationPrincipal AuthUserDetails userDetails) {
        return ResponseEntity.ok(memberPermissionService.getMemberPermissions(lineId, userDetails));
    }

    @Operation(summary = "구성원 권한 변경", description = """
            여러 구성원의 권한을 한 번에 변경한다. 적용 버튼 클릭 시 사용한다.

            - 가족관리자: lineId 무시, 세션 lineId 자동 사용
            - 관리자: 대상 가족의 lineId 필수""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "일괄 변경 성공"),
            @ApiResponse(responseCode = "400", description = """
                    요청 값 오류

                    - COMMON:4000: 요청 본문 JSON 형식이 올바르지 않습니다.
                    - COMMON:4001: 회선 ID는 필수입니다. / 권한 ID는 필수입니다. / 권한 활성화 여부(is_enable)는 필수입니다.
                    - COMMON:4002: 변경할 권한 목록이 비어 있습니다.
                    - COMMON:4003: lineId 타입이 올바르지 않습니다.
                    - COMMON:4004: lineId가 누락되었습니다. (관리자 전용)
                    """),
            @ApiResponse(responseCode = "403", description = """
                    권한 없음

                    - COMMON:4302: 접근 권한이 없습니다.
                    """),
            @ApiResponse(responseCode = "404", description = """
                    대상 정보 없음

                    - PERMISSION-4400: 해당 권한 정보가 존재하지 않습니다.
                    - PERMISSION-4401: 대상 회선 정보가 존재하지 않습니다.
                    - PERMISSION-4404: 해당 가족-회선 매핑 정보가 존재하지 않습니다.
                    """),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
    @PatchMapping
    public ResponseEntity<MemberPermissionListResDto> bulkUpdateMemberPermissions(
            @Parameter(description = "회선 ID (관리자 전용)", example = "1001") @RequestParam(required = false) Long lineId,
            @NotEmpty @Valid @RequestBody List<@Valid MemberPermissionBulkUpsertReqDto> reqList,
            @AuthenticationPrincipal AuthUserDetails userDetails) {
        return ResponseEntity.ok(memberPermissionService.bulkUpdateMemberPermissions(lineId, reqList, userDetails));
    }

//    @Operation(summary = "구성원 권한 부여 변경", description = "가족관리자 또는 관리자가 familyId와 lineId를 기준으로 권한 식별자와 활성화 여부(is_enable)를 변경한다.")
//    @ApiResponses({
//            @ApiResponse(responseCode = "200", description = "변경 성공"),
//            @ApiResponse(responseCode = "400", description = """
//                    요청 값 오류
//
//                    - COMMON:4001: 권한 ID는 필수입니다. / 권한 활성화 여부(is_enable)는 필수입니다.
//                    """),
//            @ApiResponse(responseCode = "403", description = """
//                    권한 없음
//
//                    - COMMON:4302: 접근 권한이 없습니다.
//                    """),
//            @ApiResponse(responseCode = "404", description = """
//                    권한 정보 없음
//
//                    - PERMISSION-4400: 해당 권한 정보가 존재하지 않습니다.
//                    """),
//            @ApiResponse(responseCode = "500", description = """
//                    서버 오류
//
//                    - PERMISSION-5000: 구성원 권한 반영 중 오류가 발생했습니다.
//                    """)
//    })
//    @PreAuthorize("@authz.requireAdminOrOwner(authentication)")
//    @PatchMapping
//    public ResponseEntity<MemberPermissionResDto> updateMemberPermission(
//            @Parameter(description = "가족 ID", example = "10") @RequestParam Long familyId,
//            @Parameter(description = "회선 ID", example = "1001") @RequestParam Long lineId,
//            @Valid @RequestBody MemberPermissionUpsertReqDto memberPermissionUpsertReqDto,
//            @AuthenticationPrincipal AuthUserDetails userDetails) {
//        return ResponseEntity.ok(memberPermissionService.updateMemberPermission(familyId, lineId,
//                memberPermissionUpsertReqDto, userDetails));
//    }
}
