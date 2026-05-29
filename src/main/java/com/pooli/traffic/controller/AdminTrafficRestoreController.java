package com.pooli.traffic.controller;

import java.time.LocalDate;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.traffic.domain.dto.request.TrafficRestoreStartReqDto;
import com.pooli.traffic.domain.dto.response.TrafficRestoreResumeResDto;
import com.pooli.traffic.domain.dto.response.TrafficRestoreStartResDto;
import com.pooli.traffic.service.restore.TrafficRestoreOrchestratorService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Admin-Traffic-Restore", description = "관리자용 Redis 복구 API")
@RestController
@Profile({"local", "traffic"})
@RequestMapping("/api/admin/traffic/restore")
@RequiredArgsConstructor
public class AdminTrafficRestoreController {

    private final TrafficRestoreOrchestratorService trafficRestoreOrchestratorService;

    /**
     * Redis 장애 복구 batch를 시작한다.
     */
    @PreAuthorize("@authz.requireAdmin(authentication)")
    @PostMapping("/start")
    public ResponseEntity<TrafficRestoreStartResDto> start(@RequestBody TrafficRestoreStartReqDto request) {
        return ResponseEntity.ok(trafficRestoreOrchestratorService.start(request));
    }

    /**
     * Redis 장애 복구 batch 재개 가능 상태를 확인한다.
     */
    @PreAuthorize("@authz.requireAdmin(authentication)")
    @PostMapping("/{anchorDate}/resume")
    public ResponseEntity<TrafficRestoreResumeResDto> resume(
            @PathVariable("anchorDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate anchorDate
    ) {
        return ResponseEntity.ok(trafficRestoreOrchestratorService.resume(anchorDate));
    }
}
