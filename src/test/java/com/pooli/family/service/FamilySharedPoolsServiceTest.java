package com.pooli.family.service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.family.domain.dto.request.UpdateSharedDataThresholdReqDto;
import com.pooli.family.domain.dto.response.*;
import com.pooli.family.domain.entity.SharedPoolDomain;
import com.pooli.family.exception.SharedPoolErrorCode;
import com.pooli.family.repository.FamilySharedPoolMapper;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.service.AlarmHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FamilySharedPoolsServiceTest {

    @Mock
    private FamilySharedPoolMapper sharedPoolMapper;

    @Mock
    private AlarmHistoryService alarmHistoryService;

    @InjectMocks
    private FamilySharedPoolsService service;

    // ========== 1. getFamilyIdByLineId ==========

    @Test
    @DisplayName("getFamilyIdByLineId - 정상: lineId로 familyId 반환")
    void getFamilyIdByLineId_success() {
        when(sharedPoolMapper.selectFamilyIdByLineId(101L)).thenReturn(1L);

        Long result = service.getFamilyIdByLineId(101L);

        assertThat(result).isEqualTo(1L);
        verify(sharedPoolMapper).selectFamilyIdByLineId(101L);
    }

    @Test
    @DisplayName("getFamilyIdByLineId - 예외: 가족 미가입 시 NOT_FAMILY_MEMBER")
    void getFamilyIdByLineId_notFamilyMember() {
        when(sharedPoolMapper.selectFamilyIdByLineId(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getFamilyIdByLineId(999L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.NOT_FAMILY_MEMBER));
    }

    // ========== 2. getMySharedPoolStatus ==========

    @Test
    @DisplayName("getMySharedPoolStatus - 정상: 개인 데이터 조회 성공")
    void getMySharedPoolStatus_success() {
        SharedPoolDomain domain = SharedPoolDomain.builder()
                .remainingData(8_000_000L)
                .personalContribution(500_000L)
                .build();

        when(sharedPoolMapper.selectMySharedPoolStatus(101L)).thenReturn(domain);

        SharedPoolMyStatusResDto result = service.getMySharedPoolStatus(101L);

        assertThat(result.getRemainingData()).isEqualTo(8_000_000L);
        assertThat(result.getContributionAmount()).isEqualTo(500_000L);
    }

    @Test
    @DisplayName("getMySharedPoolStatus - 예외: 데이터 없으면 SHARED_POOL_NOT_FOUND")
    void getMySharedPoolStatus_notFound() {
        when(sharedPoolMapper.selectMySharedPoolStatus(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getMySharedPoolStatus(999L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND));
    }

    // ========== 3. getFamilySharedPool ==========

    @Test
    @DisplayName("getFamilySharedPool - 정상: 가족 공유풀 5개 필드 반환")
    void getFamilySharedPool_success() {
        SharedPoolDomain domain = SharedPoolDomain.builder()
                .poolTotalData(1_000_000L)
                .poolRemainingData(800_000L)
                .poolBaseData(0L)
                .monthlyUsageAmount(0L)
                .monthlyContributionAmount(0L)
                .build();

        when(sharedPoolMapper.selectFamilySharedPool(1L)).thenReturn(domain);

        FamilySharedPoolResDto result = service.getFamilySharedPool(1L);

        assertThat(result.getPoolTotalData()).isEqualTo(1_000_000L);
        assertThat(result.getPoolRemainingData()).isEqualTo(800_000L);
        assertThat(result.getPoolBaseData()).isEqualTo(0L);
        assertThat(result.getMonthlyUsageAmount()).isEqualTo(0L);
        assertThat(result.getMonthlyContributionAmount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("getFamilySharedPool - 예외: 데이터 없으면 SHARED_POOL_NOT_FOUND")
    void getFamilySharedPool_notFound() {
        when(sharedPoolMapper.selectFamilySharedPool(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getFamilySharedPool(999L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND));
    }

    // ========== 4. contributeToSharedPool ==========

    @Test
    @DisplayName("contributeToSharedPool - 정상: 잔량 충분 시 3단 트랜잭션 수행 + 알람 전송")
    void contributeToSharedPool_success() {
        when(sharedPoolMapper.selectRemainingData(101L)).thenReturn(8_000_000L);
        when(sharedPoolMapper.selectLineIdsByFamilyId(1L)).thenReturn(List.of(101L, 201L));

        service.contributeToSharedPool(101L, 1L, 500_000L);

        verify(sharedPoolMapper).updateLineRemainingData(101L, 500_000L);
        verify(sharedPoolMapper).updateFamilyPoolData(1L, 500_000L);
        verify(sharedPoolMapper).insertContribution(1L, 101L, 500_000L);
        // 알람: 본인(101) 제외, user2(201)에게만 전송
        verify(alarmHistoryService).createAlarm(eq(201L), eq(AlarmCode.FAMILY),
                eq(AlarmType.SHARED_POOL_CONTRIBUTION));
        verify(alarmHistoryService, never()).createAlarm(eq(101L), any(), any());
    }

    @Test
    @DisplayName("contributeToSharedPool - 예외: 회선 없으면 LINE_NOT_FOUND")
    void contributeToSharedPool_lineNotFound() {
        when(sharedPoolMapper.selectRemainingData(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.contributeToSharedPool(999L, 1L, 500_000L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.LINE_NOT_FOUND));

        verify(sharedPoolMapper, never()).updateLineRemainingData(anyLong(), anyLong());
    }

    @Test
    @DisplayName("contributeToSharedPool - 예외: 잔량 부족 시 INSUFFICIENT_DATA")
    void contributeToSharedPool_insufficientData() {
        when(sharedPoolMapper.selectRemainingData(101L)).thenReturn(100L);

        assertThatThrownBy(() -> service.contributeToSharedPool(101L, 1L, 500_000L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.INSUFFICIENT_DATA));

        verify(sharedPoolMapper, never()).updateLineRemainingData(anyLong(), anyLong());
    }

    // ========== 5. getSharedPoolDetail ==========

    @Test
    @DisplayName("getSharedPoolDetail - 정상: SHARED_LIMIT 없을 때 풀 값 그대로 반환")
    void getSharedPoolDetail_withoutLimit() {
        SharedPoolDomain domain = SharedPoolDomain.builder()
                .basicDataAmount(10_000_000L)
                .remainingData(8_000_000L)
                .poolTotalData(1_000_000L)
                .poolRemainingData(800_000L)
                .build();

        when(sharedPoolMapper.selectSharedPoolDetail(1L, 101L)).thenReturn(domain);
        when(sharedPoolMapper.selectSharedDataLimit(101L)).thenReturn(null);

        SharedPoolDetailResDto result = service.getSharedPoolDetail(1L, 101L);

        assertThat(result.getSharedPoolTotalAmount()).isEqualTo(1_000_000L);
        assertThat(result.getSharedPoolRemainingAmount()).isEqualTo(800_000L);
    }

    @Test
    @DisplayName("getSharedPoolDetail - 정상: SHARED_LIMIT 있을 때 Math.min 적용")
    void getSharedPoolDetail_withLimit() {
        SharedPoolDomain domain = SharedPoolDomain.builder()
                .basicDataAmount(10_000_000L)
                .remainingData(8_000_000L)
                .poolTotalData(1_000_000L)
                .poolRemainingData(800_000L)
                .build();

        when(sharedPoolMapper.selectSharedPoolDetail(1L, 101L)).thenReturn(domain);
        when(sharedPoolMapper.selectSharedDataLimit(101L)).thenReturn(500_000L);

        SharedPoolDetailResDto result = service.getSharedPoolDetail(1L, 101L);

        // min(1,000,000, 500,000) = 500,000
        assertThat(result.getSharedPoolTotalAmount()).isEqualTo(500_000L);
        // min(800,000, 500,000) = 500,000
        assertThat(result.getSharedPoolRemainingAmount()).isEqualTo(500_000L);
    }

    @Test
    @DisplayName("getSharedPoolDetail - 예외: 데이터 없으면 SHARED_POOL_NOT_FOUND")
    void getSharedPoolDetail_notFound() {
        when(sharedPoolMapper.selectSharedPoolDetail(999L, 999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getSharedPoolDetail(999L, 999L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND));
    }

    // ========== 6. getSharedPoolMain ==========

    @Test
    @DisplayName("getSharedPoolMain - 정상: 메인 대시보드 4개 필드 반환")
    void getSharedPoolMain_success() {
        SharedPoolDomain domain = SharedPoolDomain.builder()
                .poolBaseData(0L)
                .monthlyContributionAmount(500_000L)
                .poolTotalData(1_500_000L)
                .poolRemainingData(1_200_000L)
                .build();

        when(sharedPoolMapper.selectSharedPoolMain(1L)).thenReturn(domain);

        SharedPoolMainResDto result = service.getSharedPoolMain(1L);

        assertThat(result.getSharedPoolBaseData()).isEqualTo(0L);
        assertThat(result.getSharedPoolAdditionalData()).isEqualTo(500_000L);
        assertThat(result.getSharedPoolTotalData()).isEqualTo(1_500_000L);
        assertThat(result.getSharedPoolRemainingData()).isEqualTo(1_200_000L);
    }

    @Test
    @DisplayName("getSharedPoolMain - 예외: 데이터 없으면 SHARED_POOL_NOT_FOUND")
    void getSharedPoolMain_notFound() {
        when(sharedPoolMapper.selectSharedPoolMain(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getSharedPoolMain(999L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND));
    }

    // ========== 7. getSharedDataThreshold ==========

    @Test
    @DisplayName("getSharedDataThreshold - 정상: 임계치 활성/값 반환")
    void getSharedDataThreshold_success() {
        SharedPoolDomain domain = SharedPoolDomain.builder()
                .isThresholdActive(true)
                .familyThreshold(200_000L)
                .build();

        when(sharedPoolMapper.selectSharedDataThreshold(1L)).thenReturn(domain);

        SharedDataThresholdResDto result = service.getSharedDataThreshold(1L);

        assertThat(result.getIsThresholdActive()).isTrue();
        assertThat(result.getFamilyThreshold()).isEqualTo(200_000L);
    }

    @Test
    @DisplayName("getSharedDataThreshold - 예외: 데이터 없으면 SHARED_POOL_NOT_FOUND")
    void getSharedDataThreshold_notFound() {
        when(sharedPoolMapper.selectSharedDataThreshold(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getSharedDataThreshold(999L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(SharedPoolErrorCode.SHARED_POOL_NOT_FOUND));
    }

    // ========== 8. updateSharedDataThreshold ==========

    @Test
    @DisplayName("updateSharedDataThreshold - 정상: Mapper 호출 + 알람 전송 검증")
    void updateSharedDataThreshold_success() {
        UpdateSharedDataThresholdReqDto request = new UpdateSharedDataThresholdReqDto();
        request.setNewFamilyThreshold(100_000L);
        when(sharedPoolMapper.selectLineIdsByFamilyId(1L)).thenReturn(List.of(101L, 201L));

        service.updateSharedDataThreshold(1L, request);

        verify(sharedPoolMapper).updateSharedDataThreshold(1L, 100_000L);
        // 알람: 가족 전체(101, 201) 모두에게 전송
        verify(alarmHistoryService).createAlarm(eq(101L), eq(AlarmCode.FAMILY),
                eq(AlarmType.SHARED_POOL_THRESHOLD_CHANGE));
        verify(alarmHistoryService).createAlarm(eq(201L), eq(AlarmCode.FAMILY),
                eq(AlarmType.SHARED_POOL_THRESHOLD_CHANGE));
    }

    // ========== 9. 알람 전송 추가 검증 ==========

    @Nested
    @DisplayName("알람 전송 검증")
    class AlarmTest {

        @Test
        @DisplayName("데이터 담기 - 가족이 본인만 있으면 알람 미전송")
        void contributeAlarm_noOtherMembers() {
            when(sharedPoolMapper.selectRemainingData(101L)).thenReturn(8_000_000L);
            when(sharedPoolMapper.selectLineIdsByFamilyId(1L)).thenReturn(List.of(101L));

            service.contributeToSharedPool(101L, 1L, 500_000L);

            verify(alarmHistoryService, never()).createAlarm(anyLong(), any(), any());
        }

        @Test
        @DisplayName("데이터 담기 - 가족 멤버 3명이면 본인 제외 2명에게 알람")
        void contributeAlarm_threeMembers() {
            when(sharedPoolMapper.selectRemainingData(101L)).thenReturn(8_000_000L);
            when(sharedPoolMapper.selectLineIdsByFamilyId(1L)).thenReturn(List.of(101L, 201L, 301L));

            service.contributeToSharedPool(101L, 1L, 500_000L);

            verify(alarmHistoryService, times(2)).createAlarm(anyLong(), eq(AlarmCode.FAMILY),
                    eq(AlarmType.SHARED_POOL_CONTRIBUTION));
            verify(alarmHistoryService, never()).createAlarm(eq(101L), any(), any());
        }

        @Test
        @DisplayName("임계치 수정 - 가족 멤버가 없으면 알람 미전송")
        void thresholdAlarm_noMembers() {
            UpdateSharedDataThresholdReqDto request = new UpdateSharedDataThresholdReqDto();
            request.setNewFamilyThreshold(50_000L);
            when(sharedPoolMapper.selectLineIdsByFamilyId(1L)).thenReturn(Collections.emptyList());

            service.updateSharedDataThreshold(1L, request);

            verify(alarmHistoryService, never()).createAlarm(anyLong(), any(), any());
        }
    }
}
