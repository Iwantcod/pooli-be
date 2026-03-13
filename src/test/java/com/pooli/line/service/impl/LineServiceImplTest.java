package com.pooli.line.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.line.domain.dto.request.UpdateIndividualThresholdReqDto;
import com.pooli.line.domain.dto.response.IndividualThresholdResDto;
import com.pooli.line.domain.dto.response.LineSimpleResDto;
import com.pooli.line.domain.dto.response.LineUserSummaryResDto;
import com.pooli.line.error.LineErrorCode;
import com.pooli.line.mapper.LineMapper;

@ExtendWith(MockitoExtension.class)
class LineServiceImplTest {

    @Mock
    private LineMapper lineMapper;

    @Mock
    private SecurityContextRepository securityContextRepository;

    @InjectMocks
    private LineServiceImpl lineService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("회선 목록 조회: 결과 비어있으면 LINE_NOT_FOUND")
    void getLines_empty_throws() {
        when(lineMapper.selectLinesByUserIdOrderByLineId(1L, 10L)).thenReturn(List.of());

        assertThatThrownBy(() -> lineService.getLines(1L, 10L))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(LineErrorCode.LINE_NOT_FOUND));
    }

    @Test
    @DisplayName("회선 목록 조회: 목록 반환")
    void getLines_success_returnsList() {
        List<LineSimpleResDto> list = List.of(
            LineSimpleResDto.builder().lineId(10L).phoneNumber("010-0000-0000").build()
        );
        when(lineMapper.selectLinesByUserIdOrderByLineId(1L, 10L)).thenReturn(list);

        List<LineSimpleResDto> result = lineService.getLines(1L, 10L);

        assertThat(result).isEqualTo(list);
    }

    @Test
    @DisplayName("회선 변경: 소유자 없으면 LINE_NOT_FOUND")
    void switchLine_ownerNotFound_throws() {
        AuthUserDetails principal = principalWithUserIdAndLineId(1L, 10L);
        when(lineMapper.findOwnerUserIdByLineId(99L)).thenReturn(null);

        assertThatThrownBy(() -> lineService.switchLine(principal, 99L, mockRequest(), mockResponse()))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(LineErrorCode.LINE_NOT_FOUND));
    }

    @Test
    @DisplayName("회선 변경: 소유자 불일치면 LINE_OWNERSHIP_FORBIDDEN")
    void switchLine_ownerMismatch_throws() {
        AuthUserDetails principal = principalWithUserIdAndLineId(1L, 10L);
        when(lineMapper.findOwnerUserIdByLineId(99L)).thenReturn(2L);

        assertThatThrownBy(() -> lineService.switchLine(principal, 99L, mockRequest(), mockResponse()))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN));
    }

    @Test
    @DisplayName("회선 변경: 인증 컨텍스트 교체 및 저장")
    void switchLine_success_updatesSecurityContext() {
        AuthUserDetails principal = principalWithUserIdAndLineId(1L, 10L);
        when(lineMapper.findOwnerUserIdByLineId(99L)).thenReturn(1L);
        HttpServletRequest request = mockRequest();
        HttpServletResponse response = mockResponse();

        Void result = lineService.switchLine(principal, 99L, request, response);

        assertThat(result).isNull();
        ArgumentCaptor<SecurityContext> captor = ArgumentCaptor.forClass(SecurityContext.class);
        verify(securityContextRepository).saveContext(captor.capture(), same(request), same(response));

        SecurityContext savedContext = captor.getValue();
        Authentication authentication = savedContext.getAuthentication();
        assertThat(authentication).isNotNull();
        AuthUserDetails updated = (AuthUserDetails) authentication.getPrincipal();
        assertThat(updated.getUserId()).isEqualTo(1L);
        assertThat(updated.getLineId()).isEqualTo(99L);
        assertThat(updated.getPassword()).isNull();
    }

    @Test
    @DisplayName("개별 임계치 조회: 결과 없으면 LINE_NOT_FOUND")
    void getIndividualThreshold_null_throws() {
        AuthUserDetails principal = principalWithUserIdAndLineId(1L, 10L);
        when(lineMapper.selectIndividualThresholdByLineId(10L)).thenReturn(null);

        assertThatThrownBy(() -> lineService.getIndividualThreshold(principal))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(LineErrorCode.LINE_NOT_FOUND));
    }

    @Test
    @DisplayName("개별 임계치 조회: 값 반환")
    void getIndividualThreshold_success_returnsDto() {
        AuthUserDetails principal = principalWithUserIdAndLineId(1L, 10L);
        IndividualThresholdResDto dto = IndividualThresholdResDto.builder()
            .individualThreshold(3000L)
            .isThresholdActive(true)
            .build();
        when(lineMapper.selectIndividualThresholdByLineId(10L)).thenReturn(dto);

        IndividualThresholdResDto result = lineService.getIndividualThreshold(principal);

        assertThat(result).isEqualTo(dto);
    }

    @Test
    @DisplayName("개별 임계치 수정: 업데이트 0건이면 LINE_NOT_FOUND")
    void updateIndividualThreshold_notFound_throws() {
        AuthUserDetails principal = principalWithUserIdAndLineId(1L, 10L);
        UpdateIndividualThresholdReqDto request = new UpdateIndividualThresholdReqDto();
        request.setIndividualThreshold(2000L);
        request.setIsThresholdActive(true);

        when(lineMapper.updateIndividualThreshold(10L, 2000L, true)).thenReturn(0);

        assertThatThrownBy(() -> lineService.updateIndividualThreshold(principal, request))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(LineErrorCode.LINE_NOT_FOUND));
    }

    @Test
    @DisplayName("개별 임계치 수정: 정상 처리 시 null 반환")
    void updateIndividualThreshold_success_returnsNull() {
        AuthUserDetails principal = principalWithUserIdAndLineId(1L, 10L);
        UpdateIndividualThresholdReqDto request = new UpdateIndividualThresholdReqDto();
        request.setIndividualThreshold(2000L);
        request.setIsThresholdActive(false);

        when(lineMapper.updateIndividualThreshold(10L, 2000L, false)).thenReturn(1);

        Void result = lineService.updateIndividualThreshold(principal, request);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("회선 요약 조회: phone 이 null 이면 INVALID_REQUEST_PARAM")
    void getLinesListByPhone_nullParam_throws() {
        assertThatThrownBy(() -> lineService.getLinesListByPhone(null))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST_PARAM));
    }

    @Test
    @DisplayName("회선 요약 조회: phone 형식 오류면 INVALID_REQUEST_PARAM")
    void getLinesListByPhone_invalidFormat_throws() {
        assertThatThrownBy(() -> lineService.getLinesListByPhone("123"))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST_PARAM));
    }

    @Test
    @DisplayName("회선 요약 조회: 결과 비어있으면 LINE_NOT_FOUND")
    void getLinesListByPhone_empty_throws() {
        when(lineMapper.selectLineUserSummaryListByPhoneSuffix("1234")).thenReturn(List.of());

        assertThatThrownBy(() -> lineService.getLinesListByPhone("1234"))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(LineErrorCode.LINE_NOT_FOUND));
    }

    @Test
    @DisplayName("회선 요약 조회: 전화번호 마스킹 후 반환")
    void getLinesListByPhone_success_masksPhone() {
        LineUserSummaryResDto item = LineUserSummaryResDto.builder()
            .lineId(10L)
            .phone("010-1234-5678")
            .userId(1L)
            .userName("user")
            .email("user@example.com")
            .build();
        when(lineMapper.selectLineUserSummaryListByPhoneSuffix("5678")).thenReturn(List.of(item));

        List<LineUserSummaryResDto> result = lineService.getLinesListByPhone("5678");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPhone()).isEqualTo("010-****-5678");
        assertThat(result.get(0).getLineId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("회선 목록 조회(사용자): 결과 비어있으면 LINE_NOT_FOUND")
    void getLinesListByUserId_empty_throws() {
        when(lineMapper.selectLinesByUserId(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> lineService.getLinesListByUserId(1L))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(LineErrorCode.LINE_NOT_FOUND));
    }

    @Test
    @DisplayName("회선 목록 조회(사용자): 목록 반환")
    void getLinesListByUserId_success_returnsList() {
        List<LineSimpleResDto> list = List.of(
            LineSimpleResDto.builder().lineId(10L).phoneNumber("010-0000-0000").build()
        );
        when(lineMapper.selectLinesByUserId(1L)).thenReturn(list);

        List<LineSimpleResDto> result = lineService.getLinesListByUserId(1L);

        assertThat(result).isEqualTo(list);
    }

    private static AuthUserDetails principalWithUserIdAndLineId(Long userId, Long lineId) {
        return AuthUserDetails.builder()
            .userId(userId)
            .userName("user")
            .email("user@example.com")
            .password("pw")
            .lineId(lineId)
            .authorities(List.of())
            .build();
    }

    private static HttpServletRequest mockRequest() {
        return org.mockito.Mockito.mock(HttpServletRequest.class);
    }

    private static HttpServletResponse mockResponse() {
        return org.mockito.Mockito.mock(HttpServletResponse.class);
    }
}
