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

import com.pooli.traffic.service.batch.LineDailyBatchManagerScheduler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * local 프로파일 전용 트래픽 배치 수동 시작 컨트롤러이다.
 *
 * <p>이 컨트롤러는 {@code @Profile("local")}로 local 환경에서만 빈으로 등록된다.
 * traffic 프로파일(운영 환경)에서는 빈 자체가 생성되지 않으므로 운영 서버 노출 위험이 없다.
 *
 * <p>03:00 KST 스케줄러 없이 임의의 usageDate에 대해 배치를 즉시 구동해야 하는
 * 로컬 개발 및 수동 검증 시나리오를 위해 제공된다.
 */
@Tag(name = "Local-Admin-Traffic-Batch", description = "local 전용: 트래픽 배치 수동 시작 API")
@RestController
@Profile("local")
@RequestMapping("/api/admin/traffic/batches")
@RequiredArgsConstructor
public class LocalAdminTrafficBatchController {

    private final LineDailyBatchManagerScheduler lineDailyBatchManagerScheduler;

    /**
     * 지정한 usageDate에 대해 일별 usage sync 배치를 즉시 수동으로 시작한다.
     *
     * <p>이 엔드포인트는 03:00 KST 스케줄러가 자동으로 호출하는
     * {@link LineDailyBatchManagerScheduler#runForUsageDate(LocalDate)}를 직접 트리거한다.
     *
     * <p>주의 사항:
     * <ul>
     *   <li>이미 해당 날짜의 배치가 실행 중이면 Redis Manager Lock 미획득으로 worker 시작 감지 경로로 진입한다.</li>
     *   <li>실패한 타겟 재처리가 목적이면 이 API 대신 {@code /rerun}을 사용해야 한다.</li>
     * </ul>
     */
    @Operation(
            summary = "[local 전용] 일별 usage sync 배치 수동 시작",
            description = """
                    local 환경 전용. 지정한 usageDate에 대해 Manager 선출 → Job 생성 → Worker 구동 절차를 즉시 수동으로 시작합니다.
                    이미 같은 날짜 배치가 진행 중이면 Manager Lock을 획득하지 못해 Worker 시작 감지 경로로 진입합니다.
                    실패한 타겟 재처리는 /rerun API를 사용하세요.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "배치 시작 요청 수락"),
            @ApiResponse(responseCode = "400", description = "잘못된 usageDate 형식"),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음")
    })
    @PreAuthorize("@authz.requireAdmin(authentication)")
    @PostMapping("/daily-usage-sync/{usageDate}/start")
    public ResponseEntity<Void> startDailyUsageSync(
            @Parameter(description = "배치를 시작할 usage date (yyyy-MM-dd)", example = "2026-05-26")
            @PathVariable("usageDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate usageDate
    ) {
        // Manager 선출 → Job 생성 → Worker 구동 절차를 동기적으로 시작한다.
        // Worker의 실제 처리는 TaskScheduler 스레드에서 비동기로 진행되므로 응답은 즉시 반환된다.
        lineDailyBatchManagerScheduler.runForUsageDate(usageDate);
        return ResponseEntity.ok().build();
    }
}
