package com.pooli.traffic.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

import com.pooli.traffic.domain.dto.request.TrafficRestoreStartReqDto;
import com.pooli.traffic.domain.dto.response.TrafficRestoreResumeResDto;
import com.pooli.traffic.domain.dto.response.TrafficRestoreStartResDto;
import com.pooli.traffic.service.restore.TrafficRestoreOrchestratorService;

@ExtendWith(MockitoExtension.class)
class AdminTrafficRestoreControllerTest {

    @Mock
    private TrafficRestoreOrchestratorService trafficRestoreOrchestratorService;

    @InjectMocks
    private AdminTrafficRestoreController controller;

    @Test
    @DisplayName("관리자 복구 시작 API는 request를 service에 그대로 전달한다")
    void startRestoreDelegatesRequest() {
        TrafficRestoreStartReqDto request = new TrafficRestoreStartReqDto(
                LocalDate.of(2026, 5, 29)
        );
        TrafficRestoreStartResDto serviceResponse = new TrafficRestoreStartResDto(
                true,
                "RESTORE_P0_TARGET_INSERT",
                LocalDate.of(2026, 5, 29),
                LocalDate.of(2026, 5, 27)
        );
        when(trafficRestoreOrchestratorService.start(request)).thenReturn(serviceResponse);

        ResponseEntity<TrafficRestoreStartResDto> response = controller.start(request);

        assertEquals(200, response.getStatusCode().value());
        assertSame(serviceResponse, response.getBody());
        verify(trafficRestoreOrchestratorService).start(request);
    }

    @Test
    @DisplayName("관리자 복구 시작 API 경로와 관리자 권한 조건을 유지한다")
    void startRestoreMappingAndAuthorization() throws NoSuchMethodException {
        var method = AdminTrafficRestoreController.class.getMethod("start", TrafficRestoreStartReqDto.class);

        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertEquals("/start", postMapping.value()[0]);
        assertEquals("@authz.requireAdmin(authentication)", preAuthorize.value());
    }

    @Test
    @DisplayName("관리자 복구 재개 API 경로와 관리자 권한 조건을 유지한다")
    void resumeRestoreMappingAndAuthorization() throws NoSuchMethodException {
        var method = AdminTrafficRestoreController.class.getMethod("resume", LocalDate.class);

        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertEquals("/{anchorDate}/resume", postMapping.value()[0]);
        assertEquals("@authz.requireAdmin(authentication)", preAuthorize.value());
    }

    @Test
    @DisplayName("관리자 복구 재개 API는 anchorDate를 service에 그대로 전달한다")
    void resumeRestoreDelegatesAnchorDate() {
        LocalDate anchorDate = LocalDate.of(2026, 5, 29);
        TrafficRestoreResumeResDto serviceResponse = new TrafficRestoreResumeResDto(anchorDate, false, "FAILED");
        when(trafficRestoreOrchestratorService.resume(anchorDate)).thenReturn(serviceResponse);

        ResponseEntity<TrafficRestoreResumeResDto> response = controller.resume(anchorDate);

        assertEquals(200, response.getStatusCode().value());
        assertSame(serviceResponse, response.getBody());
        verify(trafficRestoreOrchestratorService).resume(anchorDate);
    }
}
