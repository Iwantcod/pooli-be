package com.pooli.family.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.family.domain.dto.request.UpdateVisibilityReqDto;
import com.pooli.family.domain.dto.response.FamilyMemberSummaryResDto;
import com.pooli.family.domain.dto.response.FamilyMembersResDto;
import com.pooli.family.domain.dto.response.FamilyMembersSimpleResDto;
import com.pooli.family.domain.enums.FamilyRole;
import com.pooli.family.exception.FamilyErrorCode;
import com.pooli.family.mapper.FamilyMapper;
import com.pooli.traffic.service.runtime.TrafficRemainingBalanceQueryService;

@ExtendWith(MockitoExtension.class)
class FamilyServiceImplTest {

    @Mock
    private FamilyMapper familyMapper;

    @Mock
    private TrafficRemainingBalanceQueryService trafficRemainingBalanceQueryService;

    @InjectMocks
    private FamilyServiceImpl familyService;

    @Test
    @DisplayName("getFamilyMembers throws when header is missing")
    void getFamilyMembers_headerNotFound_throws() {
        AuthUserDetails principal = principalWithLineId(10L);
        when(familyMapper.selectFamilyMembersHeader(10L)).thenReturn(null);

        assertThatThrownBy(() -> familyService.getFamilyMembers(principal))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(FamilyErrorCode.FAM_NOT_FOUND));
    }

    @Test
    @DisplayName("getFamilyMembers composes actual remaining values")
    void getFamilyMembers_success_returnsComposedDto() {
        AuthUserDetails principal = principalWithLineId(10L);
        FamilyMembersResDto header = FamilyMembersResDto.builder()
                .isEnable(true)
                .familyId(1)
                .sharedPoolTotalData(1_000L)
                .sharedPoolRemainingData(300L)
                .build();
        List<FamilyMembersResDto.FamilyMemberDto> members = List.of(
                FamilyMembersResDto.FamilyMemberDto.builder()
                        .isMe(true)
                        .userId(1)
                        .lineId(10)
                        .planId(3)
                        .userName("user")
                        .phone("01012345678")
                        .planName("plan")
                        .remainingData(500L)
                        .basicDataAmount(1_000L)
                        .role(FamilyRole.OWNER)
                        .sharedPoolTotalAmount(1_000L)
                        .sharedPoolRemainingAmount(200L)
                        .sharedLimitActive(true)
                        .build()
        );

        when(familyMapper.selectFamilyMembersHeader(10L)).thenReturn(header);
        when(familyMapper.selectFamilyMembers(1, 10L)).thenReturn(members);
        when(trafficRemainingBalanceQueryService.resolveSharedActualRemaining(1L, 300L)).thenReturn(700L);
        when(trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(10L, 500L)).thenReturn(650L);

        FamilyMembersResDto result = familyService.getFamilyMembers(principal);

        assertThat(result.getIsEnable()).isTrue();
        assertThat(result.getFamilyId()).isEqualTo(1);
        assertThat(result.getSharedPoolTotalData()).isEqualTo(1_000L);
        assertThat(result.getMembers()).hasSize(1);
        assertThat(result.getMembers().get(0).getRemainingData()).isEqualTo(650L);
        assertThat(result.getMembers().get(0).getSharedPoolRemainingAmount()).isEqualTo(200L);
    }

    @Test
    @DisplayName("getFamilyMembers uses actual shared remaining when shared limit is inactive")
    void getFamilyMembers_withoutSharedLimit_usesActualSharedRemaining() {
        AuthUserDetails principal = principalWithLineId(10L);
        FamilyMembersResDto header = FamilyMembersResDto.builder()
                .isEnable(true)
                .familyId(1)
                .sharedPoolTotalData(1_000L)
                .sharedPoolRemainingData(300L)
                .build();
        List<FamilyMembersResDto.FamilyMemberDto> members = List.of(
                FamilyMembersResDto.FamilyMemberDto.builder()
                        .lineId(10)
                        .remainingData(500L)
                        .sharedPoolRemainingAmount(300L)
                        .sharedLimitActive(false)
                        .build()
        );

        when(familyMapper.selectFamilyMembersHeader(10L)).thenReturn(header);
        when(familyMapper.selectFamilyMembers(1, 10L)).thenReturn(members);
        when(trafficRemainingBalanceQueryService.resolveSharedActualRemaining(1L, 300L)).thenReturn(700L);
        when(trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(10L, 500L)).thenReturn(650L);

        FamilyMembersResDto result = familyService.getFamilyMembers(principal);

        assertThat(result.getMembers().get(0).getSharedPoolRemainingAmount()).isEqualTo(700L);
    }

    @Test
    @DisplayName("getFamilyMembersSimple throws when result is empty")
    void getFamilyMembersSimple_empty_throws() {
        AuthUserDetails principal = principalWithLineId(10L);
        when(familyMapper.selectFamilyMembersSimpleByLineId(10L)).thenReturn(List.of());

        assertThatThrownBy(() -> familyService.getFamilyMembersSimple(principal))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(FamilyErrorCode.FAM_NOT_FOUND));
    }

    @Test
    @DisplayName("getFamilyMembersSimple returns members")
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
    @DisplayName("updateVisibility throws on line mismatch")
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
    @DisplayName("updateVisibility throws when permission is disabled")
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
    @DisplayName("updateVisibility throws when update count is zero")
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
    @DisplayName("updateVisibility returns null on success")
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
    @DisplayName("getFamilyMembersByLineId throws when family id is missing")
    void getFamilyMembersByLineId_familyIdNotFound_throws() {
        when(familyMapper.selectFamilyIdByLineId(10L)).thenReturn(null);

        assertThatThrownBy(() -> familyService.getFamilyMembersByLineId(10L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(FamilyErrorCode.FAM_NOT_FOUND));
    }

    @Test
    @DisplayName("getFamilyMembersByLineId throws when members are empty")
    void getFamilyMembersByLineId_membersEmpty_throws() {
        when(familyMapper.selectFamilyIdByLineId(10L)).thenReturn(1);
        when(familyMapper.selectFamilyMemberSummaryByFamilyIdAndLineId(1, 10L)).thenReturn(List.of());

        assertThatThrownBy(() -> familyService.getFamilyMembersByLineId(10L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(FamilyErrorCode.FAM_NOT_FOUND));
    }

    @Test
    @DisplayName("getFamilyMembersByLineId returns summary")
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
        when(familyMapper.selectFamilyMemberSummaryByFamilyIdAndLineId(1, 10L)).thenReturn(members);

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
