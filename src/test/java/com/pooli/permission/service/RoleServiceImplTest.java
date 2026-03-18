package com.pooli.permission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.family.domain.entity.FamilyLine;
import com.pooli.family.domain.enums.FamilyRole;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.service.AlarmHistoryService;
import com.pooli.permission.domain.dto.response.RepresentativeRoleTransferResDto;
import com.pooli.permission.exception.PermissionErrorCode;
import com.pooli.permission.mapper.FamilyLineMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {

    @Mock
    private FamilyLineMapper familyLineMapper;

    @Mock
    private AlarmHistoryService alarmHistoryService;

    @InjectMocks
    private RoleServiceImpl roleService;

    // OWNER 사용자 (lineId = 1001L)
    private final AuthUserDetails ownerUser = AuthUserDetails.builder()
            .userId(1L)
            .email("user@example.com")
            .lineId(1001L)
            .authorities(AuthUserDetails.toAuthorities(List.of("USER", "FAMILY_OWNER")))
            .build();

    // ADMIN 사용자 (lineId = null)
    private final AuthUserDetails adminUser = AuthUserDetails.builder()
            .userId(100L)
            .email("admin@example.com")
            .lineId(null)
            .authorities(AuthUserDetails.toAuthorities(List.of("ADMIN")))
            .build();

    @Test
    @DisplayName("OWNER: 자기 자신에게 대표자 양도를 요청하면 PERMISSION-4004를 반환한다")
    void transferRepresentativeRole_selfTransfer_throwsRoleTransferSelf() {
        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> roleService.transferRepresentativeRole(null, 1001L, ownerUser)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.ROLE_TRANSFER_SELF);
        verifyNoInteractions(familyLineMapper, alarmHistoryService);
    }

    @Test
    @DisplayName("OWNER: 현재 대표 회선이 없으면 PERMISSION-4403을 반환한다")
    void transferRepresentativeRole_sourceMissing_throwsSourceNotFound() {
        when(familyLineMapper.findByLineId(1001L)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> roleService.transferRepresentativeRole(null, 1002L, ownerUser)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.ROLE_TRANSFER_SOURCE_NOT_FOUND);
        verify(familyLineMapper, never()).findByLineId(1002L);
        verify(familyLineMapper, never()).updateRole(any());
        verifyNoInteractions(alarmHistoryService);
    }

    @Test
    @DisplayName("OWNER: 양도 대상 회선이 없으면 PERMISSION-4402를 반환한다")
    void transferRepresentativeRole_targetMissing_throwsTargetNotFound() {
        when(familyLineMapper.findByLineId(1001L))
                .thenReturn(Optional.of(familyLine(1L, 1001L, FamilyRole.OWNER)));
        when(familyLineMapper.findByLineId(1002L)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> roleService.transferRepresentativeRole(null, 1002L, ownerUser)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.ROLE_TRANSFER_TARGET_NOT_FOUND);
        verify(familyLineMapper, never()).updateRole(any());
        verifyNoInteractions(alarmHistoryService);
    }

    @Test
    @DisplayName("OWNER: 다른 가족 간 양도 요청이면 PERMISSION-4901을 반환한다")
    void transferRepresentativeRole_differentFamily_throwsDifferentFamily() {
        when(familyLineMapper.findByLineId(1001L))
                .thenReturn(Optional.of(familyLine(1L, 1001L, FamilyRole.OWNER)));
        when(familyLineMapper.findByLineId(1002L))
                .thenReturn(Optional.of(familyLine(2L, 1002L, FamilyRole.MEMBER)));

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> roleService.transferRepresentativeRole(null, 1002L, ownerUser)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.ROLE_TRANSFER_DIFFERENT_FAMILY);
        verify(familyLineMapper, never()).updateRole(any());
        verifyNoInteractions(alarmHistoryService);
    }

    @Test
    @DisplayName("OWNER: 대상이 이미 OWNER면 PERMISSION-4902를 반환한다")
    void transferRepresentativeRole_targetAlreadyOwner_throwsAlreadyRepresentative() {
        when(familyLineMapper.findByLineId(1001L))
                .thenReturn(Optional.of(familyLine(1L, 1001L, FamilyRole.OWNER)));
        when(familyLineMapper.findByLineId(1002L))
                .thenReturn(Optional.of(familyLine(1L, 1002L, FamilyRole.OWNER)));

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> roleService.transferRepresentativeRole(null, 1002L, ownerUser)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.ROLE_TRANSFER_TARGET_ALREADY_REPRESENTATIVE);
        verify(familyLineMapper, never()).updateRole(any());
        verifyNoInteractions(alarmHistoryService);
    }

    @Test
    @DisplayName("OWNER: 대표자 양도 성공 시 OWNER/MEMBER 역할을 교체하고 가족 전체에 ROLE_TRANSFERRED 알림을 보낸다")
    void transferRepresentativeRole_success_updatesRoleAndSendsAlarmToFamilyMembers() {
        when(familyLineMapper.findByLineId(1001L))
                .thenReturn(Optional.of(familyLine(1L, 1001L, FamilyRole.OWNER)));
        when(familyLineMapper.findByLineId(1002L))
                .thenReturn(Optional.of(familyLine(1L, 1002L, FamilyRole.MEMBER)));
        when(familyLineMapper.findAllFamilyIdByLineId(1001L))
                .thenReturn(List.of(1001L, 1002L, 1003L));

        RepresentativeRoleTransferResDto result = roleService.transferRepresentativeRole(null, 1002L, ownerUser);

        assertThat(result.getCurrentOwnerLineId()).isEqualTo(1001L);
        assertThat(result.getChangeOwnerLineId()).isEqualTo(1002L);

        ArgumentCaptor<FamilyLine> updateCaptor = ArgumentCaptor.forClass(FamilyLine.class);
        verify(familyLineMapper, times(2)).updateRole(updateCaptor.capture());
        assertThat(updateCaptor.getAllValues())
                .extracting(FamilyLine::getLineId, FamilyLine::getRole)
                .containsExactlyInAnyOrder(
                        tuple(1001L, FamilyRole.MEMBER),
                        tuple(1002L, FamilyRole.OWNER)
                );

        ArgumentCaptor<Long> alarmTargetCaptor = ArgumentCaptor.forClass(Long.class);
        verify(alarmHistoryService, times(3)).createAlarm(
                alarmTargetCaptor.capture(),
                eq(AlarmCode.PERMISSION),
                eq(AlarmType.ROLE_TRANSFERRED)
        );
        assertThat(alarmTargetCaptor.getAllValues()).containsExactly(1001L, 1002L, 1003L);
    }

    // --- ADMIN 케이스 ---

    @Test
    @DisplayName("ADMIN: currentLineId 없이 호출하면 COMMON:4004 필수 파라미터 누락을 반환한다")
    void transferRepresentativeRole_admin_missingCurrentLineId_throwsMissingParam() {
        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> roleService.transferRepresentativeRole(null, 1002L, adminUser)
        );

        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.MISSING_REQUEST_PARAM);
        verifyNoInteractions(familyLineMapper, alarmHistoryService);
    }

    @Test
    @DisplayName("ADMIN: currentLineId를 지정하면 해당 회선 기준으로 양도가 정상 처리된다")
    void transferRepresentativeRole_admin_withCurrentLineId_success() {
        when(familyLineMapper.findByLineId(1001L))
                .thenReturn(Optional.of(familyLine(1L, 1001L, FamilyRole.OWNER)));
        when(familyLineMapper.findByLineId(1002L))
                .thenReturn(Optional.of(familyLine(1L, 1002L, FamilyRole.MEMBER)));
        when(familyLineMapper.findAllFamilyIdByLineId(1001L))
                .thenReturn(List.of(1001L, 1002L));

        RepresentativeRoleTransferResDto result = roleService.transferRepresentativeRole(1001L, 1002L, adminUser);

        assertThat(result.getCurrentOwnerLineId()).isEqualTo(1001L);
        assertThat(result.getChangeOwnerLineId()).isEqualTo(1002L);

        ArgumentCaptor<FamilyLine> updateCaptor = ArgumentCaptor.forClass(FamilyLine.class);
        verify(familyLineMapper, times(2)).updateRole(updateCaptor.capture());
        assertThat(updateCaptor.getAllValues())
                .extracting(FamilyLine::getLineId, FamilyLine::getRole)
                .containsExactlyInAnyOrder(
                        tuple(1001L, FamilyRole.MEMBER),
                        tuple(1002L, FamilyRole.OWNER)
                );
    }

    @Test
    @DisplayName("ADMIN: 자기 자신에게 양도 시도 시 PERMISSION-4004를 반환한다")
    void transferRepresentativeRole_admin_selfTransfer_throwsRoleTransferSelf() {
        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> roleService.transferRepresentativeRole(1001L, 1001L, adminUser)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.ROLE_TRANSFER_SELF);
        verifyNoInteractions(familyLineMapper, alarmHistoryService);
    }

    private FamilyLine familyLine(Long familyId, Long lineId, FamilyRole role) {
        return FamilyLine.builder()
                .familyId(familyId)
                .lineId(lineId)
                .role(role)
                .build();
    }
}
