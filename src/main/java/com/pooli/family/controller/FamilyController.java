package com.pooli.family.controller;

import com.pooli.family.domain.dto.response.MonthlyDataUsageDetailResDto;
import com.pooli.family.domain.dto.response.MonthlyDataUsageResDto;
import com.pooli.family.domain.dto.response.SharedDataThresholdResDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "Family", description = "가족 관련 API")
@RestController
@RequestMapping("/api/families")
public class FamilyController {

    @Operation(
            summary = "가족 공유 데이터 사용량 알람 임계치 조회",
            description = "가족 식별자를 통해 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "데이터가 존재하지 않음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/shared-data-limits")
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
    @PatchMapping("/shared-data-limits")
    public ResponseEntity<Void> updateSharedDataLimit(
            @Parameter(description = "가족 식별자", example = "1")
            @RequestParam Long familyId
    ) {
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "특정 가족 구성원의 최근 3개월의 데이터 사용량 조회",
            description = "자신의 가족에 속한 구성원의 정보만 확인할 수 있습니다. 응답 결과는 길이 3의 배열입니다. '평균 사용량'은 프론트엔드에서 연산해주세요."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "조회 권한 없음"),
            @ApiResponse(responseCode = "404", description = "자신이 속한 가족 정보가 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/data/recent-usage")
    public ResponseEntity<List<MonthlyDataUsageResDto>> getRecentUsage(
            @Parameter(description = "특정 가족 구성원의 회원 식별자(조회 대상자)", example = "1")
            @RequestParam Long memberId
    ) {
        List<MonthlyDataUsageResDto> result = new ArrayList<>();
        result.add(MonthlyDataUsageResDto.builder().month(1).totalUsageData(10000).build());
        result.add(MonthlyDataUsageResDto.builder().month(2).totalUsageData(15000).build());
        result.add(MonthlyDataUsageResDto.builder().month(3).totalUsageData(9200).build());
        return ResponseEntity.ok(result);
    }

    @Operation(
            summary = "특정 가족 구성원의 특정 월 데이터 사용 세부 정보 조회",
            description = "자신의 가족에 속한 구성원의 정보만 확인할 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "조회 권한 없음"),
            @ApiResponse(responseCode = "404", description = "자신이 속한 가족 정보가 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/data/detailed-usage")
    public ResponseEntity<MonthlyDataUsageDetailResDto> getDetailedUsage(
            @Parameter(description = "특정 가족 구성원의 회원 식별자(조회 대상자)", example = "1")
            @RequestParam Long memberId,
            @Parameter(description = "조회 대상 월 숫자(1~12)", example = "2")
            @RequestParam Integer month
    ) {

        List<MonthlyDataUsageDetailResDto.DataUsageDetail> details = new ArrayList<>();

        // 껍데기 데이터 삽입
        details.add(
                MonthlyDataUsageDetailResDto.DataUsageDetail.builder()
                .applicationName("Youtube").totalUsageData(2100).build());
        details.add(
                MonthlyDataUsageDetailResDto.DataUsageDetail.builder()
                        .applicationName("Instagram").totalUsageData(2100).build());
        details.add(
                MonthlyDataUsageDetailResDto.DataUsageDetail.builder()
                        .applicationName("KakaoTalk").totalUsageData(2100).build());
        details.add(
                MonthlyDataUsageDetailResDto.DataUsageDetail.builder()
                        .applicationName("Naver").totalUsageData(2100).build());
        MonthlyDataUsageDetailResDto result = MonthlyDataUsageDetailResDto.builder()
                .isPublic(true)
                .dataUsageDetails(details)
                .build();

        return ResponseEntity.ok(result);
    }
}