package com.pooli.data.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.data.domain.dto.request.DataTransferReqDto;
import com.pooli.data.domain.dto.response.AppDataUsageResDto;
import com.pooli.data.domain.dto.response.DataBalancesResDto;
import com.pooli.data.domain.dto.response.MonthlyDataUsageResDto;
import com.pooli.data.service.DataService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

@Tag(name = "Data", description = "데이터 관련 API")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/data")
public class DataController {
	
	private final DataService dataService;

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
            summary = "3개월 간 데이터 사용량 조회",
            description = "특정 회선의 특정 월 기준 최근 3개월(해당 월 포함) 데이터 사용량 및 평균 사용량을 조회한다."
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
            @RequestParam(required = true) @NotNull Long lineId,

            @Parameter(description = "조회 기준 월", example = "202601")
            @RequestParam(required = true) @NotNull Integer month
    ) {
    	
    	MonthlyDataUsageResDto response = dataService.getMonthlyDataUsage(lineId , month);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "기준 월 앱 서비스별 데이터 사용량 조회",
            description = "특정 회선의 당월 앱 서비스별 데이터 사용량 및 총 사용량을 조회한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "404", description = "회선 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/usages/apps")
    public ResponseEntity<AppDataUsageResDto> getAppDataUsage(
    		@AuthenticationPrincipal AuthUserDetails principal,
            @Parameter(description = "회선 ID", example = "1")
            @RequestParam Long lineId,
            @Parameter(description = "기준 월", example = "202601")
    		@RequestParam Integer month
    ) {
    	
    	
    	AppDataUsageResDto response = dataService.getAppDataUsage(lineId, month);
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