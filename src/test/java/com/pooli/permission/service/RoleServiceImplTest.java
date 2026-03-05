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

import com.pooli.common.exception.ApplicationException;
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

    @Test
    @DisplayName("자기 자신에게 대표자 양도를 요청하면 PERMISSION-4004를 반환한다")
    void transferRepresentativeRole_selfTransfer_throwsRoleTransferSelf() {
        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> roleService.transferRepresentativeRole(1001L, 1001L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.ROLE_TRANSFER_SELF);
        verifyNoInteractions(familyLineMapper, alarmHistoryService);
    }

    @Test
    @DisplayName("현재 대표 회선이 없으면 PERMISSION-4403을 반환한다")
    void transferRepresentativeRole_sourceMissing_throwsSourceNotFound() {
        when(familyLineMapper.findByLineId(1001L)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> roleService.transferRepresentativeRole(1001L, 1002L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.ROLE_TRANSFER_SOURCE_NOT_FOUND);
        verify(familyLineMapper, never()).findByLineId(1002L);
        verify(familyLineMapper, never()).updateRole(any());
        verifyNoInteractions(alarmHistoryService);
    }

    @Test
    @DisplayName("양도 대상 회선이 없으면 PERMISSION-4402를 반환한다")
    void transferRepresentativeRole_targetMissing_throwsTargetNotFound() {
        when(familyLineMapper.findByLineId(1001L))
                .thenReturn(Optional.of(familyLine(1L, 1001L, FamilyRole.OWNER)));
        when(familyLineMapper.findByLineId(1002L)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> roleService.transferRepresentativeRole(1001L, 1002L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.ROLE_TRANSFER_TARGET_NOT_FOUND);
        verify(familyLineMapper, never()).updateRole(any());
        verifyNoInteractions(alarmHistoryService);
    }

    @Test
    @DisplayName("다른 가족 간 양도 요청이면 PERMISSION-4901을 반환한다")
    void transferRepresentativeRole_differentFamily_throwsDifferentFamily() {
        when(familyLineMapper.findByLineId(1001L))
                .thenReturn(Optional.of(familyLine(1L, 1001L, FamilyRole.OWNER)));
        when(familyLineMapper.findByLineId(1002L))
                .thenReturn(Optional.of(familyLine(2L, 1002L, FamilyRole.MEMBER)));

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> roleService.transferRepresentativeRole(1001L, 1002L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.ROLE_TRANSFER_DIFFERENT_FAMILY);
        verify(familyLineMapper, never()).updateRole(any());
        verifyNoInteractions(alarmHistoryService);
    }

    @Test
    @DisplayName("대상이 이미 OWNER면 PERMISSION-4902를 반환한다")
    void transferRepresentativeRole_targetAlreadyOwner_throwsAlreadyRepresentative() {
        when(familyLineMapper.findByLineId(1001L))
                .thenReturn(Optional.of(familyLine(1L, 1001L, FamilyRole.OWNER)));
        when(familyLineMapper.findByLineId(1002L))
                .thenReturn(Optional.of(familyLine(1L, 1002L, FamilyRole.OWNER)));

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> roleService.transferRepresentativeRole(1001L, 1002L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.ROLE_TRANSFER_TARGET_ALREADY_REPRESENTATIVE);
        verify(familyLineMapper, never()).updateRole(any());
        verifyNoInteractions(alarmHistoryService);
    }

    @Test
    @DisplayName("대표자 양도 성공 시 OWNER/MEMBER 역할을 교체하고 가족 전체에 ROLE_TRANSFERRED 알림을 보낸다")
    void transferRepresentativeRole_success_updatesRoleAndSendsAlarmToFamilyMembers() {
        when(familyLineMapper.findByLineId(1001L))
                .thenReturn(Optional.of(familyLine(1L, 1001L, FamilyRole.OWNER)));
        when(familyLineMapper.findByLineId(1002L))
                .thenReturn(Optional.of(familyLine(1L, 1002L, FamilyRole.MEMBER)));
        when(familyLineMapper.findAllFamilyIdByLineId(1001L))
                .thenReturn(List.of(1001L, 1002L, 1003L));

        RepresentativeRoleTransferResDto result = roleService.transferRepresentativeRole(1001L, 1002L);

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

    private FamilyLine familyLine(Long familyId, Long lineId, FamilyRole role) {
        return FamilyLine.builder()
                .familyId(familyId)
                .lineId(lineId)
                .role(role)
                .build();
    }
}
