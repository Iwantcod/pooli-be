package com.pooli.family.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.family.domain.dto.request.UpdateVisibilityReqDto;
import com.pooli.family.domain.dto.response.FamilyMemberSummaryResDto;
import com.pooli.family.domain.dto.response.FamilyMembersResDto;
import com.pooli.family.domain.dto.response.FamilyMembersSimpleResDto;
import com.pooli.family.exception.FamilyErrorCode;
import com.pooli.family.mapper.FamilyMapper;
import com.pooli.traffic.service.runtime.TrafficRedisKeyFactory;
import com.pooli.traffic.service.runtime.TrafficRedisRuntimePolicy;

@ExtendWith(MockitoExtension.class)
class FamilyServiceImplTest {

    @Mock
    private FamilyMapper familyMapper;

    @Mock
    private StringRedisTemplate cacheStringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private TrafficRedisKeyFactory trafficRedisKeyFactory;

    @Mock
    private TrafficRedisRuntimePolicy trafficRedisRuntimePolicy;

    @InjectMocks
    private FamilyServiceImpl familyService;

    @Test
    @DisplayName("가족 구성원 조회: 헤더 없으면 FAM_NOT_FOUND")
    void getFamilyMembers_headerNotFound_throws() {
        AuthUserDetails principal = principalWithLineId(10L);
        when(familyMapper.selectFamilyMembersHeader(10L)).thenReturn(null);

        assertThatThrownBy(() -> familyService.getFamilyMembers(principal))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(FamilyErrorCode.FAM_NOT_FOUND));
    }

    @Test
    @DisplayName("가족 구성원 조회: Redis 버퍼 잔여량을 합산해서 반환")
    void getFamilyMembers_success_returnsComposedDto() {
        AuthUserDetails principal = principalWithLineId(10L);
        YearMonth targetMonth = YearMonth.of(2026, 3);
        FamilyMembersResDto header = FamilyMembersResDto.builder()
            .isEnable(true)
            .familyId(1)
            .sharedPoolTotalData(1000L)
            .build();
        List<FamilyMembersResDto.FamilyMemberDto> members = List.of(
            FamilyMembersResDto.FamilyMemberDto.builder()
                .isMe(true)
                .lineId(10)
                .userName("user")
                .remainingData(100L)
                .sharedPoolRemainingAmount(200L)
                .build()
        );

        when(familyMapper.selectFamilyMembersHeader(10L)).thenReturn(header);
        when(familyMapper.selectFamilyMembers(1, 10L)).thenReturn(members);
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingIndivAmountKey(10L, targetMonth))
            .thenReturn("pooli:remaining_indiv_amount:10:202603");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(1L, targetMonth))
            .thenReturn("pooli:remaining_shared_amount:1:202603");
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("pooli:remaining_indiv_amount:10:202603", "amount")).thenReturn("30");
        when(hashOperations.get("pooli:remaining_shared_amount:1:202603", "amount")).thenReturn("40");

        FamilyMembersResDto result = familyService.getFamilyMembers(principal);

        assertThat(result.getIsEnable()).isTrue();
        assertThat(result.getFamilyId()).isEqualTo(1);
        assertThat(result.getSharedPoolTotalData()).isEqualTo(1000L);
        assertThat(result.getMembers()).hasSize(1);
        assertThat(result.getMembers().get(0).getRemainingData()).isEqualTo(130L);
        assertThat(result.getMembers().get(0).getSharedPoolRemainingAmount()).isEqualTo(240L);
    }

    @Test
    @DisplayName("가족 구성원 조회: Redis 값이 없으면 DB 값을 유지")
    void getFamilyMembers_whenRedisAmountMissing_keepsDbValues() {
        AuthUserDetails principal = principalWithLineId(10L);
        YearMonth targetMonth = YearMonth.of(2026, 3);
        FamilyMembersResDto header = FamilyMembersResDto.builder()
            .isEnable(true)
            .familyId(1)
            .sharedPoolTotalData(1000L)
            .build();
        List<FamilyMembersResDto.FamilyMemberDto> members = List.of(
            FamilyMembersResDto.FamilyMemberDto.builder()
                .isMe(true)
                .lineId(10)
                .userName("user")
                .remainingData(100L)
                .sharedPoolRemainingAmount(200L)
                .build()
        );

        when(familyMapper.selectFamilyMembersHeader(10L)).thenReturn(header);
        when(familyMapper.selectFamilyMembers(1, 10L)).thenReturn(members);
        when(trafficRedisRuntimePolicy.zoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(trafficRedisKeyFactory.remainingIndivAmountKey(10L, targetMonth))
            .thenReturn("pooli:remaining_indiv_amount:10:202603");
        when(trafficRedisKeyFactory.remainingSharedAmountKey(1L, targetMonth))
            .thenReturn("pooli:remaining_shared_amount:1:202603");
        when(cacheStringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("pooli:remaining_indiv_amount:10:202603", "amount")).thenReturn(null);
        when(hashOperations.get("pooli:remaining_shared_amount:1:202603", "amount")).thenReturn(null);

        FamilyMembersResDto result = familyService.getFamilyMembers(principal);

        assertThat(result.getMembers()).hasSize(1);
        assertThat(result.getMembers().get(0).getRemainingData()).isEqualTo(100L);
        assertThat(result.getMembers().get(0).getSharedPoolRemainingAmount()).isEqualTo(200L);
    }

    @Test
    @DisplayName("가족 구성원 간단 조회: 결과 비어있으면 FAM_NOT_FOUND")
    void getFamilyMembersSimple_empty_throws() {
        AuthUserDetails principal = principalWithLineId(10L);
        when(familyMapper.selectFamilyMembersSimpleByLineId(10L)).thenReturn(List.of());

        assertThatThrownBy(() -> familyService.getFamilyMembersSimple(principal))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(FamilyErrorCode.FAM_NOT_FOUND));
    }

    @Test
    @DisplayName("가족 구성원 간단 조회: 목록 반환")
    void getFamilyMembersSimple_success_returnsList() {
        AuthUserDetails principal = principalWithLineId(10L);
        List<FamilyMembersSimpleResDto> list = List.of(
            FamilyMembersSimpleResDto.builder()
                .lineId(10L)
                .userId(1L)
                .userName("user")
                .phone("01012345678")
                .build()
        );
        when(familyMapper.selectFamilyMembersSimpleByLineId(10L)).thenReturn(list);

        List<FamilyMembersSimpleResDto> result = familyService.getFamilyMembersSimple(principal);

        assertThat(result).isEqualTo(list);
    }

    @Test
    @DisplayName("가족 공개 설정 변경: 요청 lineId 불일치면 LINE_OWNERSHIP_FORBIDDEN")
    void updateVisibility_lineIdMismatch_throws() {
        AuthUserDetails principal = principalWithLineId(10L);
        UpdateVisibilityReqDto request = new UpdateVisibilityReqDto();
        request.setLineId(11L);
        request.setIsPublic(true);

        assertThatThrownBy(() -> familyService.updateVisibility(request, principal))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN));
    }

    @Test
    @DisplayName("가족 공개 설정 변경: 권한 비활성이면 FAM_NOT_FOUND")
    void updateVisibility_permissionDisabled_throws() {
        AuthUserDetails principal = principalWithLineId(10L);
        UpdateVisibilityReqDto request = new UpdateVisibilityReqDto();
        request.setLineId(10L);
        request.setIsPublic(true);

        when(familyMapper.isPermissionEnabledByTitle(10L)).thenReturn(false);

        assertThatThrownBy(() -> familyService.updateVisibility(request, principal))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(FamilyErrorCode.FAM_NOT_FOUND));
    }

    @Test
    @DisplayName("가족 공개 설정 변경: 업데이트 0건이면 FAM_CONFLICT")
    void updateVisibility_updateConflict_throws() {
        AuthUserDetails principal = principalWithLineId(10L);
        UpdateVisibilityReqDto request = new UpdateVisibilityReqDto();
        request.setLineId(10L);
        request.setIsPublic(false);

        when(familyMapper.isPermissionEnabledByTitle(10L)).thenReturn(true);
        when(familyMapper.updateFamilyLineVisibility(10L, false)).thenReturn(0);

        assertThatThrownBy(() -> familyService.updateVisibility(request, principal))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(FamilyErrorCode.FAM_CONFLICT));
    }

    @Test
    @DisplayName("가족 공개 설정 변경: 정상 처리 시 null 반환")
    void updateVisibility_success_returnsNull() {
        AuthUserDetails principal = principalWithLineId(10L);
        UpdateVisibilityReqDto request = new UpdateVisibilityReqDto();
        request.setLineId(10L);
        request.setIsPublic(false);

        when(familyMapper.isPermissionEnabledByTitle(10L)).thenReturn(true);
        when(familyMapper.updateFamilyLineVisibility(10L, false)).thenReturn(1);

        Void result = familyService.updateVisibility(request, principal);

        assertThat(result).isNull();
        verify(familyMapper).updateFamilyLineVisibility(10L, false);
    }

    @Test
    @DisplayName("가족 구성원 요약 조회: 가족 ID 없으면 FAM_NOT_FOUND")
    void getFamilyMembersByLineId_familyIdNotFound_throws() {
        when(familyMapper.selectFamilyIdByLineId(10L)).thenReturn(null);

        assertThatThrownBy(() -> familyService.getFamilyMembersByLineId(10L))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(FamilyErrorCode.FAM_NOT_FOUND));
    }

    @Test
    @DisplayName("가족 구성원 요약 조회: 멤버 비어있으면 FAM_NOT_FOUND")
    void getFamilyMembersByLineId_membersEmpty_throws() {
        when(familyMapper.selectFamilyIdByLineId(10L)).thenReturn(1);
        when(familyMapper.selectFamilyMemberSummaryByFamilyIdAndLineId(1, 10L))
            .thenReturn(List.of());

        assertThatThrownBy(() -> familyService.getFamilyMembersByLineId(10L))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                .isEqualTo(FamilyErrorCode.FAM_NOT_FOUND));
    }

    @Test
    @DisplayName("가족 구성원 요약 조회: 가족 ID와 멤버 목록 반환")
    void getFamilyMembersByLineId_success_returnsSummary() {
        List<FamilyMemberSummaryResDto.FamilyMemberSummaryDto> members = List.of(
            FamilyMemberSummaryResDto.FamilyMemberSummaryDto.builder()
                .lineId(10L)
                .userId(1L)
                .userName("user")
                .phone("01012345678")
                .role("OWNER")
                .build()
        );
        when(familyMapper.selectFamilyIdByLineId(10L)).thenReturn(1);
        when(familyMapper.selectFamilyMemberSummaryByFamilyIdAndLineId(1, 10L))
            .thenReturn(members);

        FamilyMemberSummaryResDto result = familyService.getFamilyMembersByLineId(10L);

        assertThat(result.getFamilyId()).isEqualTo(1);
        assertThat(result.getMembers()).isEqualTo(members);
    }

    private AuthUserDetails principalWithLineId(Long lineId) {
        return AuthUserDetails.builder()
            .userId(1L)
            .userName("user")
            .email("user@example.com")
            .password("pw")
            .lineId(lineId)
            .authorities(List.of())
            .build();
    }
}
