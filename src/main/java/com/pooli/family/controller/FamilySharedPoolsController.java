package com.pooli.family.controller;

import com.pooli.family.domain.dto.request.CreateSharedPoolContributionReqDto;
import com.pooli.family.domain.dto.request.UpdateSharedDataThresholdReqDto;
import com.pooli.family.domain.dto.response.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Shared Pool", description = "가족 공유데이터 관련 API")
@RestController
@RequestMapping("/api/shared-pools")
public class FamilySharedPoolsController {

    @Operation(
            summary = "공유 데이터 담기 화면 개인 정보 조회",
            description = "특정 회선(lineId)을 기준으로 개인 데이터 잔여량과 "
                    + "해당 회선이 공유풀에 담은 총 데이터 양을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "404", description = "유저 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/my")
    public ResponseEntity<SharedPoolMyStatusResDto> getMySharedPoolStatus(
            @Parameter(description = "회선 ID", example = "10")
            @RequestParam Integer lineId
    ) {

        SharedPoolMyStatusResDto response =SharedPoolMyStatusResDto.builder().build();

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "가족 공유풀 조회",
            description = "특정 가족의 공유풀 총량, 잔여량 및 "
                    + "당월 공유풀 사용량과 제공량을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "404", description = "가족 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping
    public ResponseEntity<FamilySharedPoolResDto> getFamilySharedPool(
            @Parameter(description = "가족 ID", example = "1")
            @RequestParam Integer familyId
    ) {

        FamilySharedPoolResDto response =FamilySharedPoolResDto.builder().build();

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "공유풀에 데이터 담기",
            description = "특정 가족의 공유풀에 회선(lineId)이 보유한 개인 데이터를 "
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
            @RequestBody CreateSharedPoolContributionReqDto request
    ) {
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "공유풀 상세 페이지 조회",
            description = "familyId와 lineId에 해당하는 기본 데이터 및 "
                    + "사용 가능한 공유풀 데이터 정보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "공유풀 상세 페이지 조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "404", description = "유저 및 가족 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/detail/remaining-amount")
    public ResponseEntity<SharedPoolDetailResDto> getSharedPoolDetail(
            @Parameter(description = "가족 식별자", example = "1")
            @RequestParam Integer familyId,

            @Parameter(description = "회선 식별자", example = "10")
            @RequestParam Integer lineId
    ) {

        SharedPoolDetailResDto response =SharedPoolDetailResDto.builder().build();


        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "메인 대시보드 공유풀 잔량 조회",
            description = "familyId에 해당하는 당월 공유풀 기본 충전량, 추가 충전량, "
                    + "총량 및 잔여량 정보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "메인 대시보드 공유풀 잔량 조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "404", description = "가족 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/main/remaining-amount")
    public ResponseEntity<SharedPoolMainResDto> getSharedPoolMain(
            @Parameter(description = "가족 식별자", example = "1")
            @RequestParam Integer familyId
    ) {

        SharedPoolMainResDto response = SharedPoolMainResDto.builder().build();

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "가족 공유 데이터 사용량 알람 임계치 조회",
            description = "가족 식별자를 통해 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "데이터가 존재하지 않음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/limits")
    public ResponseEntity<SharedDataThresholdResDto> getSharedDataLimit(
            @Parameter(description = "가족 식별자", example = "1")
            @RequestParam Long familyId
    ) {
        SharedDataThresholdResDto result = SharedDataThresholdResDto.builder().build();
        return ResponseEntity.ok(result);
    }

    @Operation(
            summary = "가족 공유 데이터 사용량 알람 임계치 수정",
            description = "가족 대표자만 요청할 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "401", description = "수정 권한 없음"),
            @ApiResponse(responseCode = "404", description = "가족 식별자에 해당하는 가족이 존재하지 않음"),
            @ApiResponse(responseCode = "500", description = "서버 오류"),
    })
    @PatchMapping("/limits")
    public ResponseEntity<Void> updateSharedDataLimit(
            @Parameter(description = "가족 식별자", example = "1")
            @RequestParam Long familyId,
            @RequestBody UpdateSharedDataThresholdReqDto request
    ) {
        return ResponseEntity.ok().build();
    }
}
