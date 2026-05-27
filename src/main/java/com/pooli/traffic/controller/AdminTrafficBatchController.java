package com.pooli.traffic.controller;

import java.time.LocalDate;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.traffic.domain.dto.response.LineDailyUsageSyncResumeResDto;
import com.pooli.traffic.domain.dto.response.LineDailyUsageSyncRerunResDto;
import com.pooli.traffic.service.batch.LineDailyUsageSyncResumeService;
import com.pooli.traffic.service.batch.LineDailyUsageSyncRerunService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Admin-Traffic-Batch", description = "관리자용 트래픽 배치 API")
@RestController
@Profile({"local", "traffic"})
@RequestMapping("/api/admin/traffic/batches")
@RequiredArgsConstructor
public class AdminTrafficBatchController {

    private final LineDailyUsageSyncResumeService lineDailyUsageSyncResumeService;
    private final LineDailyUsageSyncRerunService lineDailyUsageSyncRerunService;

    @Operation(
            summary = "관리자 기능: 일별 usage sync worker 재개",
            description = "관리자 전용. 지정 usageDate의 RUNNING usage sync batch에 대해 worker 시작 감지를 재개합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "재개 요청 처리 완료"),
            @ApiResponse(responseCode = "400", description = "잘못된 usageDate 형식"),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("@authz.requireAdmin(authentication)")
    @PostMapping("/daily-usage-sync/{usageDate}/resume")
    public ResponseEntity<LineDailyUsageSyncResumeResDto> resumeDailyUsageSync(
            @Parameter(description = "재개할 usage date", example = "2026-05-25")
            @PathVariable("usageDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate usageDate
    ) {
        LineDailyUsageSyncResumeResDto response = lineDailyUsageSyncResumeService.resume(usageDate);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "관리자 기능: 일별 usage sync rerun",
            description = "관리자 전용. 지정 usageDate의 FAILED 또는 ABANDONED usage sync batch에 대해 실패 target만 재처리합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "rerun 요청 처리 완료"),
            @ApiResponse(responseCode = "400", description = "잘못된 usageDate 형식"),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PreAuthorize("@authz.requireAdmin(authentication)")
    @PostMapping("/daily-usage-sync/{usageDate}/rerun")
    public ResponseEntity<LineDailyUsageSyncRerunResDto> rerunDailyUsageSync(
            @Parameter(description = "rerun할 usage date", example = "2026-05-25")
            @PathVariable("usageDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate usageDate
    ) {
        LineDailyUsageSyncRerunResDto response = lineDailyUsageSyncRerunService.rerun(usageDate);
        return ResponseEntity.ok(response);
    }
}
