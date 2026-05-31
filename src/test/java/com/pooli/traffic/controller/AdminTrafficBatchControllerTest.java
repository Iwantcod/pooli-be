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

import com.pooli.traffic.domain.batch.LineDailyBatchStatus;
import com.pooli.traffic.domain.dto.response.LineDailyUsageSyncResumeResDto;
import com.pooli.traffic.domain.dto.response.LineDailyUsageSyncRerunResDto;
import com.pooli.traffic.service.batch.LineDailyUsageSyncResumeService;
import com.pooli.traffic.service.batch.LineDailyUsageSyncRerunService;

@ExtendWith(MockitoExtension.class)
class AdminTrafficBatchControllerTest {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 5, 25);

    @Mock
    private LineDailyUsageSyncResumeService lineDailyUsageSyncResumeService;

    @Mock
    private LineDailyUsageSyncRerunService lineDailyUsageSyncRerunService;

    @InjectMocks
    private AdminTrafficBatchController adminTrafficBatchController;

    @Test
    @DisplayName("관리자 usage sync 재개 API는 usageDate를 service에 그대로 전달한다")
    void resumeDailyUsageSyncDelegatesUsageDate() {
        LineDailyUsageSyncResumeResDto serviceResponse = LineDailyUsageSyncResumeResDto.builder()
                .batchJobId(2L)
                .usageDate(USAGE_DATE)
                .status(LineDailyBatchStatus.RUNNING)
                .resumeAccepted(true)
                .build();
        when(lineDailyUsageSyncResumeService.resume(USAGE_DATE)).thenReturn(serviceResponse);

        ResponseEntity<LineDailyUsageSyncResumeResDto> response =
                adminTrafficBatchController.resumeDailyUsageSync(USAGE_DATE);

        assertEquals(200, response.getStatusCode().value());
        assertSame(serviceResponse, response.getBody());
        verify(lineDailyUsageSyncResumeService).resume(USAGE_DATE);
    }

    @Test
    @DisplayName("관리자 usage sync 재개 API 경로와 관리자 권한 조건을 유지한다")
    void resumeDailyUsageSyncMappingAndAuthorization() throws NoSuchMethodException {
        var method = AdminTrafficBatchController.class.getMethod("resumeDailyUsageSync", LocalDate.class);

        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertEquals("/daily-usage-sync/{usageDate}/resume", postMapping.value()[0]);
        assertEquals("@authz.requireAdmin(authentication)", preAuthorize.value());
    }

    @Test
    @DisplayName("관리자 usage sync rerun API는 usageDate를 service에 그대로 전달한다")
    void rerunDailyUsageSyncDelegatesUsageDate() {
        LineDailyUsageSyncRerunResDto serviceResponse = LineDailyUsageSyncRerunResDto.builder()
                .previousBatchJobId(2L)
                .rerunBatchJobId(3L)
                .usageDate(USAGE_DATE)
                .previousStatus(LineDailyBatchStatus.FAILED)
                .targetCount(4L)
                .rerunAccepted(true)
                .build();
        when(lineDailyUsageSyncRerunService.rerun(USAGE_DATE)).thenReturn(serviceResponse);

        ResponseEntity<LineDailyUsageSyncRerunResDto> response =
                adminTrafficBatchController.rerunDailyUsageSync(USAGE_DATE);

        assertEquals(200, response.getStatusCode().value());
        assertSame(serviceResponse, response.getBody());
        verify(lineDailyUsageSyncRerunService).rerun(USAGE_DATE);
    }

    @Test
    @DisplayName("관리자 usage sync rerun API 경로와 관리자 권한 조건을 유지한다")
    void rerunDailyUsageSyncMappingAndAuthorization() throws NoSuchMethodException {
        var method = AdminTrafficBatchController.class.getMethod("rerunDailyUsageSync", LocalDate.class);

        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertEquals("/daily-usage-sync/{usageDate}/rerun", postMapping.value()[0]);
        assertEquals("@authz.requireAdmin(authentication)", preAuthorize.value());
    }
}
