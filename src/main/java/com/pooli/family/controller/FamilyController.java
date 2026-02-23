package com.pooli.family.controller;

import com.pooli.family.domain.dto.request.UpdateSharedDataThresholdReqDto;
import com.pooli.family.domain.dto.response.SharedDataThresholdResDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.pooli.family.domain.dto.response.FamilyMembersResDto;
import com.pooli.family.domain.dto.response.FamilyMembersSimpleResDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Family", description = "가족 관련 API")
@RestController
@RequestMapping("/api/families")
public class FamilyController {

    @Operation(
            summary = "메인 대시보드 가족별 가족 구성원 정보 조회",
            description = "메인 대시보드에서 특정 가족의 상세 정보를 조회합니다. "
                    + "상세 페이지 열람 권한 활성화 여부(isEnable), 가족 식별자(familyId)와 함께 "
                    + "가족 구성원 목록을 반환합니다. "
                    + "각 구성원 정보에는 회원 식별자(userId), 회선 식별자(lineId), "
                    + "요금제 식별자(planId), 회원 이름(userName), 전화번호(phone), "
                    + "요금제명(planName), 기본 제공 데이터 잔량(remainingData), "
                    + "기본 제공 데이터량(basicDataAmount), 가족 역할(role), "
                    + "당일 누적 공유풀 데이터 사용량(usageAmount)이 포함됩니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "404", description = "가족 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/members")
    public ResponseEntity<FamilyMembersResDto> getFamilyMembers(
            @Parameter(description = "가족 ID", example = "1")
            @RequestParam Integer familyId,

            @Parameter(description = "회선 ID", example = "10")
            @RequestParam Integer lineId
    ) {

        FamilyMembersResDto response = FamilyMembersResDto.builder().build();

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "가족 결합 구성원(단말) 목록 조회",
            description = "familyId에 해당하는 가족 결합에 포함된 구성원(단말) 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "404", description = "가족 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/members-simple")
    public ResponseEntity<FamilyMembersSimpleResDto> getFamilyMembers(
            @Parameter(description = "가족 식별자", example = "1")
            @RequestParam Integer familyId
    ) {

        FamilyMembersSimpleResDto response = FamilyMembersSimpleResDto.builder().build();

        return ResponseEntity.ok(response);
    }
}