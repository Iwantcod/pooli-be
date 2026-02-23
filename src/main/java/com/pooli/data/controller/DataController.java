package com.pooli.data.controller;

import com.pooli.data.domain.dto.request.DataTransferReqDto;
import com.pooli.data.domain.dto.response.AppDataUsageResDto;
import com.pooli.data.domain.dto.response.DataBalancesResDto;
import com.pooli.data.domain.dto.response.MonthlyDataUsageResDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Data", description = "데이터 관련 API")
@RestController
@RequestMapping("/api/data")
public class DataController {

    @Operation(
            summary = "데이터 이체",
            description = "특정 회선 간 데이터 이체를 수행한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이체 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "404", description = "회선 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/transfers")
    public ResponseEntity<Void> transferData(
            @RequestBody DataTransferReqDto request
    ) {
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "월별 데이터 사용량 조회",
            description = "특정 회선의 최근 n개월 데이터 사용량 및 평균 사용량을 조회한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "404", description = "회선 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/usages/monthly")
    public ResponseEntity<MonthlyDataUsageResDto> getMonthlyDataUsage(
            @Parameter(description = "회선 ID", example = "1")
            @RequestParam Integer lineId,

            @Parameter(description = "조회 개월 수", example = "3")
            @RequestParam Integer month
    ) {

        List<MonthlyDataUsageResDto.MonthlyUsageDto> usages = List.of(
                MonthlyDataUsageResDto.MonthlyUsageDto.builder()
                        .yearMonth("2026-01")
                        .usedAmount(1000L)
                        .build(),
                MonthlyDataUsageResDto.MonthlyUsageDto.builder()
                        .yearMonth("2026-02")
                        .usedAmount(1500L)
                        .build(),
                MonthlyDataUsageResDto.MonthlyUsageDto.builder()
                        .yearMonth("2026-03")
                        .usedAmount(1100L)
                        .build()
        );

        Long average = usages.stream()
                .mapToLong(MonthlyDataUsageResDto.MonthlyUsageDto::getUsedAmount)
                .sum() / usages.size();

        MonthlyDataUsageResDto response = MonthlyDataUsageResDto.builder()
                .usages(usages)
                .averageAmount(average)
                .build();

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "앱 서비스별 데이터 사용량 조회",
            description = "특정 회선의 앱 서비스별 데이터 사용량 및 총 사용량을 조회한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "404", description = "회선 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/usages/apps")
    public ResponseEntity<AppDataUsageResDto> getAppDataUsage(
            @Parameter(description = "회선 ID", example = "1")
            @RequestParam Integer lineId
    ) {

        List<AppDataUsageResDto.AppUsageDto> apps = List.of(
                AppDataUsageResDto.AppUsageDto.builder()
                        .appName("YouTube")
                        .usedAmount(2000L)
                        .build(),
                AppDataUsageResDto.AppUsageDto.builder()
                        .appName("Instagram")
                        .usedAmount(1500L)
                        .build(),
                AppDataUsageResDto.AppUsageDto.builder()
                        .appName("Netflix")
                        .usedAmount(1700L)
                        .build()
        );

        Long total = apps.stream()
                .mapToLong(AppDataUsageResDto.AppUsageDto::getUsedAmount)
                .sum();

        AppDataUsageResDto response = AppDataUsageResDto.builder()
                .totalUsedAmount(total)
                .apps(apps)
                .build();

        return ResponseEntity.ok(response);
    }


    @Operation(
            summary = "공유 및 개인 데이터 잔량 조회",
            description = "특정 회선의 공유 데이터 잔량, 개인 데이터 잔량, 사용자 정보 및 요금제 정보를 조회한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "404", description = "회선 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/usages/balances")
    public ResponseEntity<DataBalancesResDto> getDataSummary(
            @Parameter(description = "회선 ID", example = "1")
            @RequestParam Integer lineId
    ) {

        // 데이터 임의적으로 넣어둠. 추후 수정 필요.
        DataBalancesResDto response = DataBalancesResDto.builder()
                .userName("홍길동")
                .sharedDataRemaining(5000L)
                .personalDataRemaining(2000L)
                .planName("5G 프리미엄")
                .build();

        return ResponseEntity.ok(response);
    }
}