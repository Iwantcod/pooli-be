package com.pooli.data.controller;

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
import com.pooli.data.domain.dto.response.DataUsageResDto;
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
            description = "특정 회선 간 데이터 이체를 수행한다.",
            deprecated = true
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

    /**
     * getMonthlyDataUsage
     * - yearMonth 포함 근 3개월의 데이터 사용량 추이 조회
     * 
     * @param lineId : 조회 대상 회선 식별자
     * @param yearMonth : 조회 기준 년,월 정보
     * @return
     */
    @Operation(
            summary = "3개월 간 데이터 사용량 추이 조회",
            description = "특정 회선의 특정 월 기준 최근 3개월(해당 월 포함) 데이터 사용량 및 평균 사용량을 조회한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
                        잘못된 요청
                        
                        - COMMON:4002 RequestParam 유효성 검증 실패
                        - COMMON:4003 RequestParam 타입 불일치
                        - COMMON:4004 필수 RequestParam 누락
                        - DATA:4001 yearMonth는 YYYYMM 형식이어야 합니다
                        """
                ),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(
                    responseCode = "404",
                    description = """
                        리소스 없음
                        
                        - DATA:4001 해당 데이터가 존재하지 않습니다
                        """
                ),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/usages/monthly")
    public ResponseEntity<MonthlyDataUsageResDto> getMonthlyDataUsage(
            @Parameter(description = "회선 ID", example = "1")
            @RequestParam(required = true, name = "lineId") @NotNull Long lineId,

            @Parameter(description = "조회 기준 월", example = "202601")
            @RequestParam(required = true, name = "yearMonth") @NotNull Integer yearMonth
    ) {
    	
        return ResponseEntity.ok(dataService.getMonthlyDataUsage(lineId , yearMonth));
    }

    /**
     * getAppDataUsage()
     * - 회선의 yearMonth 월 앱 서비스별 데이터 사용량 조회
     * 
     * @param principal : 로그인 세션 정보
     * @param lineId : 조회 대상 회선 식별자
     * @param yearMonth : 조회 기준 년,월 정보
     * @return
     */
    @Operation(
            summary = "기준 월 앱 서비스별 데이터 사용량 조회",
            description = "특정 회선의 당월 앱 서비스별 데이터 사용량 및 총 사용량을 조회한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
                        잘못된 요청
                        
                        - COMMON:4002 RequestParam 유효성 검증 실패
                        - COMMON:4003 RequestParam 타입 불일치
                        - COMMON:4004 필수 RequestParam 누락
                        - DATA:4001 yearMonth는 YYYYMM 형식이어야 합니다                        
                        """
                ),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(
                    responseCode = "404",
                    description = """
                        리소스 없음
                        
                        - DATA:4001 해당 데이터가 존재하지 않습니다
                        """
                ),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/usages/apps")
    public ResponseEntity<AppDataUsageResDto> getAppDataUsage(
    		@AuthenticationPrincipal AuthUserDetails principal,
            @Parameter(description = "회선 ID", example = "1")
            @RequestParam(required = true, name = "lineId") Long lineId,
            @Parameter(description = "기준 월", example = "202601")
    		@RequestParam(required = true, name = "yearMonth") Integer yearMonth
    ) {
    	
    	
    	AppDataUsageResDto response = dataService.getAppDataUsage(lineId, yearMonth,principal);
        return ResponseEntity.ok(response);
    }

    /**
     * getDataSummary()
     * - 특정 회선의 개인 데이터 잔량, 소속 가족의 공유 데이터 잔량, 사용자 정보 등의 요약 정보 조회
     * 
     * @param lineId : 조회 대상 회선 식별자
     * @return
     */
    @Operation(
            summary = "공유 및 개인 데이터 잔량 조회",
            description = "특정 회선의 공유 데이터 잔량, 개인 데이터 잔량, 사용자 정보 및 요금제 정보를 조회한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
                        잘못된 요청
                        
                        - COMMON:4002 RequestParam 유효성 검증 실패
                        - COMMON:4003 RequestParam 타입 불일치
                        - COMMON:4004 필수 RequestParam 누락
                        """
                ),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(
                    responseCode = "404",
                    description = """
                        리소스 없음
                        
                        - DATA:4001 해당 데이터가 존재하지 않습니다
                        """
                ),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/usages/balances")
    public ResponseEntity<DataBalancesResDto> getDataSummary(
    		@AuthenticationPrincipal AuthUserDetails principal
    ) {


        return ResponseEntity.ok(dataService.getDataSummary(principal.getLineId()));
    }
    
    /**
     * getDataUsage()
     * - 특정 회선의 yearMonth의 공유 데이터 및 개인 데이터 사용량 집계 기록을 조회
     * - yearMonth가 당월일 경우 데이터 총량도 함께 조회
     * 
     * 
     * @param lineId : 조회 대상 회선 식별자
     * @param yearMonth : 조회 기준 년,월 정보
     * @return
     */
    @Operation(
            summary = "데이터 사용량 조회",
            description = "특정 회선, 특정 월의 공유 데이터 사용량, 개인 데이터 사용량을 조회한다."
            		+ "당월 조회 시 데이터 총량도 함께 조회된다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = """
                        잘못된 요청
                        
                        - COMMON:4002 RequestParam 유효성 검증 실패
                        - COMMON:4003 RequestParam 타입 불일치
                        - COMMON:4004 필수 RequestParam 누락
                        - DATA:4001 yearMonth는 YYYYMM 형식이어야 합니다
                        """
                ),
            @ApiResponse(responseCode = "404", description = "회선 정보를 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/usages/data")
    public ResponseEntity<DataUsageResDto> getDataUsage(
            @Parameter(description = "회선 ID", example = "1")
            @RequestParam(required = true, name = "lineId") Long lineId,
            
            @Parameter(description = "조회 대상 월", example = "202601")
            @RequestParam(required = true, name = "yearMonth") Integer yearMonth
            
    ) {


        return ResponseEntity.ok(dataService.getDataUsage(lineId, yearMonth));
    }
}