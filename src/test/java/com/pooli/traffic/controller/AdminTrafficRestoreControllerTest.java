package com.pooli.traffic.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

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
    @DisplayName("관리자 복구 시작 API 경로와 관리자 권한 조건을 유지하며 @Valid를 적용한다")
    void startRestoreMappingAndAuthorization() throws NoSuchMethodException {
        var method = AdminTrafficRestoreController.class.getMethod("start", TrafficRestoreStartReqDto.class);

        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        var parameter = method.getParameters()[0];

        assertEquals("/start", postMapping.value()[0]);
        assertEquals("@authz.requireAdmin(authentication)", preAuthorize.value());
        assertTrue(parameter.isAnnotationPresent(jakarta.validation.Valid.class));
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

    @Test
    @DisplayName("TrafficRestoreStartReqDto의 failureDate가 null이면 검증 에러가 발생한다")
    void validateNullFailureDate() {
        TrafficRestoreStartReqDto request = new TrafficRestoreStartReqDto(null);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        var violations = validator.validate(request);

        assertEquals(1, violations.size());
        assertEquals("failureDate", violations.iterator().next().getPropertyPath().toString());
        assertEquals("failureDate는 필수입니다.", violations.iterator().next().getMessage());
    }
}
