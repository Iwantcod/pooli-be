package com.pooli.family.controller;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.family.domain.dto.request.CreateSharedPoolContributionReqDto;
import com.pooli.family.domain.dto.request.UpdateSharedDataThresholdReqDto;
import com.pooli.family.domain.dto.response.*;
import com.pooli.family.service.FamilySharedPoolsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Shared Pool", description = "가족 공유데이터 관련 API")
@RestController
@RequestMapping("/api/shared-pools")
@RequiredArgsConstructor
public class FamilySharedPoolsController {

    private final FamilySharedPoolsService familySharedPoolsService;

    @Operation(
            summary = "공유 데이터 담기 화면 개인 정보 조회",
            description = "로그인한 사용자의 메인 회선을 기준으로 개인 데이터 잔여량과 "
                    + "해당 회선이 공유풀에 담은 총 데이터 양을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "유저 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/my")
    public ResponseEntity<SharedPoolMyStatusResDto> getMySharedPoolStatus(
            @AuthenticationPrincipal AuthUserDetails userDetails
    ) {
        Long lineId = userDetails.getLineId();
        SharedPoolMyStatusResDto response = familySharedPoolsService.getMySharedPoolStatus(lineId);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "가족 공유풀 조회",
            description = "로그인한 사용자가 속한 가족의 공유풀 총량, 잔여량 및 "
                    + "당월 공유풀 사용량과 제공량을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "가족 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping
    public ResponseEntity<FamilySharedPoolResDto> getFamilySharedPool(
            @AuthenticationPrincipal AuthUserDetails userDetails
    ) {
        Long lineId = userDetails.getLineId();
        Long familyId = familySharedPoolsService.getFamilyIdByLineId(lineId);
        FamilySharedPoolResDto response = familySharedPoolsService.getFamilySharedPool(familyId);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "공유풀에 데이터 담기",
            description = "로그인한 사용자의 개인 데이터를 가족 공유풀에 "
                    + "지정한 용량만큼 담습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "데이터 담기 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "404", description = "유저 및 가족 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping
    public ResponseEntity<Void> contributeToSharedPool(
            @AuthenticationPrincipal AuthUserDetails userDetails,
            @RequestBody CreateSharedPoolContributionReqDto request
    ) {
        Long lineId = userDetails.getLineId();
        Long familyId = familySharedPoolsService.getFamilyIdByLineId(lineId);
        familySharedPoolsService.contributeToSharedPool(lineId, familyId, request.getAmount());
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "공유풀 상세 페이지 조회 (Duplicate)",
            description = "로그인한 사용자의 기본 데이터 및 "
                    + "사용 가능한 공유풀 데이터 정보를 조회합니다.",
            deprecated = true
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "공유풀 상세 페이지 조회 성공"),
            @ApiResponse(responseCode = "404", description = "유저 및 가족 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/detail/remaining-amount")
    public ResponseEntity<SharedPoolDetailResDto> getSharedPoolDetail(
            @AuthenticationPrincipal AuthUserDetails userDetails
    ) {
        Long lineId = userDetails.getLineId();
        Long familyId = familySharedPoolsService.getFamilyIdByLineId(lineId);
        SharedPoolDetailResDto response = familySharedPoolsService.getSharedPoolDetail(familyId, lineId);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "메인 대시보드 공유풀 잔량 조회",
            description = "로그인한 사용자가 속한 가족의 당월 공유풀 기본 충전량, 추가 충전량, "
                    + "총량 및 잔여량 정보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "메인 대시보드 공유풀 잔량 조회 성공"),
            @ApiResponse(responseCode = "404", description = "가족 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/main/remaining-amount")
    public ResponseEntity<SharedPoolMainResDto> getSharedPoolMain(
            @AuthenticationPrincipal AuthUserDetails userDetails
    ) {
        Long lineId = userDetails.getLineId();
        Long familyId = familySharedPoolsService.getFamilyIdByLineId(lineId);
        SharedPoolMainResDto response = familySharedPoolsService.getSharedPoolMain(familyId);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "가족 공유 데이터 사용량 알람 임계치 조회",
            description = "로그인한 사용자가 속한 가족의 임계치를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "데이터가 존재하지 않음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/limit")
    public ResponseEntity<SharedDataThresholdResDto> getSharedDataLimit(
            @AuthenticationPrincipal AuthUserDetails userDetails
    ) {
        Long lineId = userDetails.getLineId();
        Long familyId = familySharedPoolsService.getFamilyIdByLineId(lineId);
        SharedDataThresholdResDto result = familySharedPoolsService.getSharedDataThreshold(familyId);
        return ResponseEntity.ok(result);
    }

    @Operation(
            summary = "가족 공유 데이터 사용량 알람 임계치 수정",
            description = "가족 대표자 또는 관리자만 요청할 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "403", description = "수정 권한 없음"),
            @ApiResponse(responseCode = "404", description = "가족 식별자에 해당하는 가족이 존재하지 않음"),
            @ApiResponse(responseCode = "500", description = "서버 오류"),
    })
    @PreAuthorize("@authz.requireOwner(authentication)")
    @PatchMapping("/limit")
    public ResponseEntity<Void> updateSharedDataLimit(
            @AuthenticationPrincipal AuthUserDetails userDetails,
            @RequestBody UpdateSharedDataThresholdReqDto request
    ) {
        Long lineId = userDetails.getLineId();
        Long familyId = familySharedPoolsService.getFamilyIdByLineId(lineId);
        familySharedPoolsService.updateSharedDataThreshold(familyId, request);
        return ResponseEntity.ok().build();
    }
    
    
    
    @Operation(
            summary = "가족 공유풀 사용량 조회 ",
            description = "로그인 회선 기준 가족 공유풀 사용량 조회"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "임계치 수정 성공"),
        @ApiResponse(responseCode = "401", description = "인증 실패"),
        @ApiResponse(responseCode = "404", description = "공유풀 정보를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/usage/monthly-total")
    public ResponseEntity<SharedPoolMonthlyUsageResDto> getFamilyMonthlySharedUsageTotal(
            @AuthenticationPrincipal AuthUserDetails principal
    ) {
        return ResponseEntity.ok(familySharedPoolsService.getFamilyMonthlySharedUsageTotal(principal));
    }
}
