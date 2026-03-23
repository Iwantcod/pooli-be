package com.pooli.permission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.pooli.permission.domain.dto.request.MemberPermissionBulkUpsertReqDto;
import com.pooli.permission.domain.dto.request.MemberPermissionUpsertReqDto;
import com.pooli.permission.domain.dto.response.MemberPermissionListResDto;
import com.pooli.permission.domain.dto.response.MemberPermissionResDto;
import com.pooli.permission.domain.entity.Permission;
import com.pooli.permission.domain.entity.PermissionLine;
import com.pooli.permission.exception.PermissionErrorCode;
import com.pooli.permission.mapper.FamilyLineMapper;
import com.pooli.permission.mapper.PermissionLineMapper;
import com.pooli.permission.mapper.PermissionMapper;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class MemberPermissionServiceImplTest {

    @Mock
    private FamilyLineMapper familyLineMapper;

    @Mock
    private PermissionLineMapper permissionLineMapper;

    @Mock
    private PermissionMapper permissionMapper;

    @Mock
    private AlarmHistoryService alarmHistoryService;

    @InjectMocks
    private MemberPermissionServiceImpl memberPermissionService;

    @Test
    @DisplayName("일괄 변경: ADMIN이 lineId 없이 요청하면 COMMON:4004를 반환한다")
    void bulkUpdateMemberPermissions_adminWithoutLineId_throwsMissingRequestParam() {
        AuthUserDetails admin = userWithRole(1L, "ROLE_ADMIN");
        List<MemberPermissionBulkUpsertReqDto> reqList = List.of(req(20L, 1, true));

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> memberPermissionService.bulkUpdateMemberPermissions(null, reqList, admin)
        );

        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.MISSING_REQUEST_PARAM);
        verifyNoInteractions(familyLineMapper, permissionLineMapper, permissionMapper, alarmHistoryService);
    }

    @Test
    @DisplayName("내 권한 조회 시 회선이 존재하면 권한 목록을 반환한다")
    void getMyPermissions_success_returnsPermissionList() {
        when(familyLineMapper.findByLineId(10L))
                .thenReturn(Optional.of(familyLine(1L, 10L, FamilyRole.OWNER)));
        List<MemberPermissionResDto> permissions = List.of(
                memberPermission(10L, 1, true),
                memberPermission(10L, 2, false)
        );
        when(permissionLineMapper.findByLineId(10L)).thenReturn(permissions);

        MemberPermissionListResDto result = memberPermissionService.getMyPermissions(10L);

        assertThat(result.getMemberPermissions()).isEqualTo(permissions);
        verify(permissionLineMapper).findByLineId(10L);
    }

    @Test
    @DisplayName("내 권한 조회 시 회선이 없으면 PERMISSION-4401을 반환한다")
    void getMyPermissions_lineNotFound_throwsLineNotFound() {
        when(familyLineMapper.findByLineId(10L)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> memberPermissionService.getMyPermissions(10L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.LINE_NOT_FOUND);
        verify(permissionLineMapper, never()).findByLineId(anyLong());
    }

    @Test
    @DisplayName("가족 전체 권한 조회 시 ADMIN은 대상 lineId를 사용한다")
    void getFamilyMemberPermissions_adminSuccess_usesTargetLineId() {
        AuthUserDetails admin = userWithRole(99L, "ROLE_ADMIN");
        when(familyLineMapper.findByLineId(30L))
                .thenReturn(Optional.of(familyLine(1L, 30L, FamilyRole.MEMBER)));
        List<MemberPermissionResDto> familyPermissions = List.of(
                memberPermission(10L, 1, true),
                memberPermission(20L, 1, false)
        );
        when(permissionLineMapper.findByFamilyId(1L)).thenReturn(familyPermissions);

        MemberPermissionListResDto result = memberPermissionService.getFamilyMemberPermissions(30L, admin);

        assertThat(result.getMemberPermissions()).isEqualTo(familyPermissions);
        verify(familyLineMapper, never()).findByFamilyIdAndLineId(anyLong(), anyLong());
        verify(permissionLineMapper).findByFamilyId(1L);
    }

    @Test
    @DisplayName("가족 전체 권한 조회 시 기준 회선이 없으면 PERMISSION-4401을 반환한다")
    void getFamilyMemberPermissions_lineNotFound_throwsLineNotFound() {
        AuthUserDetails owner = userWithRole(10L, "ROLE_FAMILY_OWNER");
        when(familyLineMapper.findByLineId(10L)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> memberPermissionService.getFamilyMemberPermissions(null, owner)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.LINE_NOT_FOUND);
        verify(permissionLineMapper, never()).findByFamilyId(anyLong());
    }

    @Test
    @DisplayName("구성원 권한 조회 시 ADMIN은 대상 회선의 권한 목록을 반환한다")
    void getMemberPermissions_adminSuccess_returnsMemberPermissions() {
        AuthUserDetails admin = userWithRole(99L, "ROLE_ADMIN");
        when(familyLineMapper.findByLineId(20L))
                .thenReturn(Optional.of(familyLine(1L, 20L, FamilyRole.MEMBER)));
        List<MemberPermissionResDto> permissions = List.of(memberPermission(20L, 1, true));
        when(permissionLineMapper.findByFamilyIdAndLineId(1L, 20L)).thenReturn(permissions);

        MemberPermissionListResDto result = memberPermissionService.getMemberPermissions(20L, admin);

        assertThat(result.getMemberPermissions()).isEqualTo(permissions);
        verify(permissionLineMapper, times(1)).findByFamilyIdAndLineId(1L, 20L);
    }

    @Test
    @DisplayName("구성원 권한 조회 시 회선이 없으면 PERMISSION-4401을 반환한다")
    void getMemberPermissions_lineNotFound_throwsLineNotFound() {
        AuthUserDetails admin = userWithRole(99L, "ROLE_ADMIN");
        when(familyLineMapper.findByLineId(20L)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> memberPermissionService.getMemberPermissions(20L, admin)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.LINE_NOT_FOUND);
        verify(permissionLineMapper, never()).findByFamilyIdAndLineId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("구성원 권한 변경 성공 시 변경된 권한 정보를 반환한다")
    void updateMemberPermission_success_returnsUpdatedPermission() {
        AuthUserDetails admin = userWithRole(99L, "ROLE_ADMIN");
        MemberPermissionUpsertReqDto req = updateReq(1, true);

        when(familyLineMapper.findByFamilyIdAndLineId(1L, 20L))
                .thenReturn(Optional.of(familyLine(1L, 20L, FamilyRole.MEMBER)));
        when(permissionMapper.findById(1)).thenReturn(Optional.of(permission(1)));
        when(permissionLineMapper.findByFamilyIdAndLineId(1L, 20L))
                .thenReturn(List.of(
                        memberPermission(20L, 1, true),
                        memberPermission(20L, 2, false)
                ));

        MemberPermissionResDto result =
                memberPermissionService.updateMemberPermission(1L, 20L, req, admin);

        assertThat(result.getLineId()).isEqualTo(20L);
        assertThat(result.getPermissionId()).isEqualTo(1);
        assertThat(result.getIsEnable()).isTrue();

        ArgumentCaptor<PermissionLine> captor = ArgumentCaptor.forClass(PermissionLine.class);
        verify(permissionLineMapper).upsert(captor.capture());
        assertThat(captor.getValue().getLineId()).isEqualTo(20L);
        assertThat(captor.getValue().getPermissionId()).isEqualTo(1);
        assertThat(captor.getValue().getIsEnable()).isTrue();
    }

    @Test
    @DisplayName("단건 변경: 비공개 권한(permission_id=2)을 비활성화하면 공개 상태가 복구된다")
    void updateMemberPermission_privacyPermissionDisabled_restoresVisibility() {
        AuthUserDetails admin = userWithRole(99L, "ROLE_ADMIN");
        MemberPermissionUpsertReqDto req = updateReq(2, false);

        when(familyLineMapper.findByFamilyIdAndLineId(1L, 20L))
                .thenReturn(Optional.of(familyLine(1L, 20L, FamilyRole.MEMBER)));
        when(permissionMapper.findById(2)).thenReturn(Optional.of(permission(2)));
        when(permissionLineMapper.findByFamilyIdAndLineId(1L, 20L))
                .thenReturn(List.of(memberPermission(20L, 2, false)));

        MemberPermissionResDto result =
                memberPermissionService.updateMemberPermission(1L, 20L, req, admin);

        assertThat(result.getLineId()).isEqualTo(20L);
        assertThat(result.getPermissionId()).isEqualTo(2);
        assertThat(result.getIsEnable()).isFalse();

        verify(familyLineMapper).forcePublicByLineId(20L);
    }

    @Test
    @DisplayName("단건 변경: 비공개 권한이 아닌 다른 권한을 꺼도 공개 복구가 호출되지 않는다")
    void updateMemberPermission_nonPrivacyPermission_doesNotRestoreVisibility() {
        AuthUserDetails admin = userWithRole(99L, "ROLE_ADMIN");
        MemberPermissionUpsertReqDto req = updateReq(1, false);

        when(familyLineMapper.findByFamilyIdAndLineId(1L, 20L))
                .thenReturn(Optional.of(familyLine(1L, 20L, FamilyRole.MEMBER)));
        when(permissionMapper.findById(1)).thenReturn(Optional.of(permission(1)));
        when(permissionLineMapper.findByFamilyIdAndLineId(1L, 20L))
                .thenReturn(List.of(memberPermission(20L, 1, false)));

        memberPermissionService.updateMemberPermission(1L, 20L, req, admin);

        verify(familyLineMapper, never()).forcePublicByLineId(anyLong());
    }

    @Test
    @DisplayName("구성원 권한 변경 시 가족-회선 매핑이 없으면 PERMISSION-4404를 반환한다")
    void updateMemberPermission_familyLineMissing_throwsFamilyLineMappingNotFound() {
        AuthUserDetails admin = userWithRole(99L, "ROLE_ADMIN");
        MemberPermissionUpsertReqDto req = updateReq(1, true);
        when(familyLineMapper.findByFamilyIdAndLineId(1L, 20L)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> memberPermissionService.updateMemberPermission(1L, 20L, req, admin)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.FAMILY_LINE_MAPPING_NOT_FOUND);
        verify(permissionMapper, never()).findById(anyInt());
        verify(permissionLineMapper, never()).upsert(any());
    }

    @Test
    @DisplayName("구성원 권한 변경 시 권한 정보가 없으면 PERMISSION-4400을 반환한다")
    void updateMemberPermission_permissionMissing_throwsPermissionNotFound() {
        AuthUserDetails admin = userWithRole(99L, "ROLE_ADMIN");
        MemberPermissionUpsertReqDto req = updateReq(1, true);
        when(familyLineMapper.findByFamilyIdAndLineId(1L, 20L))
                .thenReturn(Optional.of(familyLine(1L, 20L, FamilyRole.MEMBER)));
        when(permissionMapper.findById(1)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> memberPermissionService.updateMemberPermission(1L, 20L, req, admin)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.PERMISSION_NOT_FOUND);
        verify(permissionLineMapper, never()).upsert(any());
    }

    @Test
    @DisplayName("구성원 권한 변경 후 결과 조회 시 대상 권한이 없으면 PERMISSION-5000을 반환한다")
    void updateMemberPermission_applyError_throwsMemberPermissionApplyError() {
        AuthUserDetails admin = userWithRole(99L, "ROLE_ADMIN");
        MemberPermissionUpsertReqDto req = updateReq(1, true);
        when(familyLineMapper.findByFamilyIdAndLineId(1L, 20L))
                .thenReturn(Optional.of(familyLine(1L, 20L, FamilyRole.MEMBER)));
        when(permissionMapper.findById(1)).thenReturn(Optional.of(permission(1)));
        when(permissionLineMapper.findByFamilyIdAndLineId(1L, 20L))
                .thenReturn(List.of(memberPermission(20L, 2, false)));

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> memberPermissionService.updateMemberPermission(1L, 20L, req, admin)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.MEMBER_PERMISSION_APPLY_ERROR);
    }

    @Test
    @DisplayName("일괄 변경 시 OWNER의 lineId 파라미터는 무시되고 알람은 대상별로 중복 없이 발송된다")
    void bulkUpdateMemberPermissions_ownerLineIdIgnored_usesSessionLineIdAndDistinctAlarmTargets() {
        AuthUserDetails owner = userWithRole(10L, "ROLE_FAMILY_OWNER");
        List<MemberPermissionBulkUpsertReqDto> reqList = List.of(
                req(20L, 1, true),
                req(20L, 2, false),
                req(30L, 1, true)
        );

        when(familyLineMapper.findByLineId(10L)).thenReturn(Optional.of(familyLine(1L, 10L, FamilyRole.OWNER)));
        when(familyLineMapper.findByFamilyIdAndLineId(1L, 10L))
                .thenReturn(Optional.of(familyLine(1L, 10L, FamilyRole.OWNER)));
        when(familyLineMapper.findByFamilyIdAndLineId(1L, 20L))
                .thenReturn(Optional.of(familyLine(1L, 20L, FamilyRole.MEMBER)));
        when(familyLineMapper.findByFamilyIdAndLineId(1L, 30L))
                .thenReturn(Optional.of(familyLine(1L, 30L, FamilyRole.MEMBER)));
        when(permissionMapper.findById(anyInt()))
                .thenAnswer(invocation -> Optional.of(permission(invocation.getArgument(0))));

        List<MemberPermissionResDto> finalPermissions = List.of(
                memberPermission(20L, 1, true),
                memberPermission(20L, 2, false),
                memberPermission(30L, 1, true)
        );
        when(permissionLineMapper.findByFamilyId(1L)).thenReturn(finalPermissions);

        MemberPermissionListResDto result =
                memberPermissionService.bulkUpdateMemberPermissions(999L, reqList, owner);

        assertThat(result.getMemberPermissions()).isEqualTo(finalPermissions);
        verify(familyLineMapper).findByLineId(10L);
        verify(familyLineMapper, never()).findByLineId(999L);
        verify(permissionLineMapper).bulkUpsert(reqList);

        ArgumentCaptor<Long> lineIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(alarmHistoryService, times(2)).createAlarm(
                lineIdCaptor.capture(),
                eq(AlarmCode.PERMISSION),
                eq(AlarmType.PERMISSION_CHANGED)
        );
        assertThat(lineIdCaptor.getAllValues()).containsExactlyInAnyOrder(20L, 30L);
    }

    @Test
    @DisplayName("일괄 변경: 비공개 권한(permission_id=2)을 비활성화하면 해당 회선만 공개 복구된다")
    void bulkUpdateMemberPermissions_privacyPermissionDisabled_restoresVisibility() {
        AuthUserDetails admin = userWithRole(50L, "ROLE_ADMIN");
        List<MemberPermissionBulkUpsertReqDto> reqList = List.of(
                req(20L, 2, false),
                req(30L, 1, true)
        );

        when(familyLineMapper.findByLineId(50L)).thenReturn(Optional.of(familyLine(1L, 50L, FamilyRole.OWNER)));
        when(familyLineMapper.findByFamilyIdAndLineId(1L, 20L))
                .thenReturn(Optional.of(familyLine(1L, 20L, FamilyRole.MEMBER)));
        when(familyLineMapper.findByFamilyIdAndLineId(1L, 30L))
                .thenReturn(Optional.of(familyLine(1L, 30L, FamilyRole.MEMBER)));
        when(permissionMapper.findById(anyInt()))
                .thenAnswer(invocation -> Optional.of(permission(invocation.getArgument(0))));
        when(permissionLineMapper.findByFamilyId(1L)).thenReturn(List.of());

        memberPermissionService.bulkUpdateMemberPermissions(50L, reqList, admin);

        verify(familyLineMapper).forcePublicByLineIds(List.of(20L));
    }

    @Test
    @DisplayName("일괄 변경: 비공개 권한이 아닌 다른 권한만 변경하면 공개 복구가 호출되지 않는다")
    void bulkUpdateMemberPermissions_nonPrivacyPermission_doesNotRestoreVisibility() {
        AuthUserDetails admin = userWithRole(50L, "ROLE_ADMIN");
        List<MemberPermissionBulkUpsertReqDto> reqList = List.of(
                req(20L, 1, false),
                req(30L, 1, true)
        );

        when(familyLineMapper.findByLineId(50L)).thenReturn(Optional.of(familyLine(1L, 50L, FamilyRole.OWNER)));
        when(familyLineMapper.findByFamilyIdAndLineId(1L, 20L))
                .thenReturn(Optional.of(familyLine(1L, 20L, FamilyRole.MEMBER)));
        when(familyLineMapper.findByFamilyIdAndLineId(1L, 30L))
                .thenReturn(Optional.of(familyLine(1L, 30L, FamilyRole.MEMBER)));
        when(permissionMapper.findById(anyInt()))
                .thenAnswer(invocation -> Optional.of(permission(invocation.getArgument(0))));
        when(permissionLineMapper.findByFamilyId(1L)).thenReturn(List.of());

        memberPermissionService.bulkUpdateMemberPermissions(50L, reqList, admin);

        verify(familyLineMapper, never()).forcePublicByLineIds(anyList());
    }

    @Test
    @DisplayName("일괄 변경 시 빈 목록이면 업데이트/알람 없이 현재 상태를 반환한다")
    void bulkUpdateMemberPermissions_emptyReqList_returnsCurrentState() {
        AuthUserDetails admin = userWithRole(50L, "ROLE_ADMIN");
        when(familyLineMapper.findByLineId(50L)).thenReturn(Optional.of(familyLine(1L, 50L, FamilyRole.OWNER)));

        List<MemberPermissionResDto> currentPermissions = List.of(memberPermission(50L, 1, true));
        when(permissionLineMapper.findByFamilyId(1L)).thenReturn(currentPermissions);

        MemberPermissionListResDto result = memberPermissionService.bulkUpdateMemberPermissions(
                50L, Collections.emptyList(), admin
        );

        assertThat(result.getMemberPermissions()).isEqualTo(currentPermissions);
        verify(permissionLineMapper, never()).bulkUpsert(anyList());
        verify(familyLineMapper, never()).findByFamilyIdAndLineId(anyLong(), anyLong());
        verifyNoInteractions(permissionMapper, alarmHistoryService);
    }

    @Test
    @DisplayName("일괄 변경 시 존재하지 않는 권한이면 PERMISSION-4400을 반환한다")
    void bulkUpdateMemberPermissions_permissionNotFound_throwsPermissionNotFound() {
        AuthUserDetails admin = userWithRole(50L, "ROLE_ADMIN");
        List<MemberPermissionBulkUpsertReqDto> reqList = List.of(req(20L, 99, true));

        when(familyLineMapper.findByLineId(50L)).thenReturn(Optional.of(familyLine(1L, 50L, FamilyRole.OWNER)));
        when(permissionMapper.findById(99)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> memberPermissionService.bulkUpdateMemberPermissions(50L, reqList, admin)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.PERMISSION_NOT_FOUND);
        verify(permissionLineMapper, never()).bulkUpsert(anyList());
        verifyNoInteractions(alarmHistoryService);
    }

    @Test
    @DisplayName("일괄 변경 시 가족-회선 매핑이 없으면 PERMISSION-4404를 반환한다")
    void bulkUpdateMemberPermissions_familyLineMissing_throwsFamilyLineMappingNotFound() {
        AuthUserDetails admin = userWithRole(50L, "ROLE_ADMIN");
        List<MemberPermissionBulkUpsertReqDto> reqList = List.of(req(20L, 1, true));

        when(familyLineMapper.findByLineId(50L)).thenReturn(Optional.of(familyLine(1L, 50L, FamilyRole.OWNER)));
        when(permissionMapper.findById(1)).thenReturn(Optional.of(permission(1)));
        when(familyLineMapper.findByFamilyIdAndLineId(1L, 20L)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> memberPermissionService.bulkUpdateMemberPermissions(50L, reqList, admin)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.FAMILY_LINE_MAPPING_NOT_FOUND);
        verify(permissionLineMapper, never()).bulkUpsert(anyList());
        verifyNoInteractions(alarmHistoryService);
    }

    @Test
    @DisplayName("구성원 권한 조회 시 OWNER가 아니면 COMMON:4302를 반환한다")
    void getMemberPermissions_nonOwner_throwsLineOwnershipForbidden() {
        AuthUserDetails member = userWithRole(10L, "ROLE_FAMILY_MEMBER");
        when(familyLineMapper.findByLineId(20L)).thenReturn(Optional.of(familyLine(1L, 20L, FamilyRole.MEMBER)));
        when(familyLineMapper.findByFamilyIdAndLineId(1L, 10L))
                .thenReturn(Optional.of(familyLine(1L, 10L, FamilyRole.MEMBER)));

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> memberPermissionService.getMemberPermissions(20L, member)
        );

        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN);
        verify(permissionLineMapper, never()).findByFamilyIdAndLineId(anyLong(), anyLong());
    }

    private AuthUserDetails userWithRole(Long lineId, String role) {
        return AuthUserDetails.builder()
                .userId(1L)
                .email("test@pooli.com")
                .lineId(lineId)
                .authorities(List.of(new SimpleGrantedAuthority(role)))
                .build();
    }

    private FamilyLine familyLine(Long familyId, Long lineId, FamilyRole role) {
        return FamilyLine.builder()
                .familyId(familyId)
                .lineId(lineId)
                .role(role)
                .build();
    }

    private Permission permission(Integer permissionId) {
        return Permission.builder()
                .permissionId(permissionId)
                .permissionTitle("perm-" + permissionId)
                .build();
    }

    private MemberPermissionBulkUpsertReqDto req(Long lineId, Integer permissionId, Boolean isEnable) {
        MemberPermissionBulkUpsertReqDto dto = new MemberPermissionBulkUpsertReqDto();
        dto.setLineId(lineId);
        dto.setPermissionId(permissionId);
        dto.setIsEnable(isEnable);
        return dto;
    }

    private MemberPermissionUpsertReqDto updateReq(Integer permissionId, Boolean isEnable) {
        MemberPermissionUpsertReqDto dto = new MemberPermissionUpsertReqDto();
        dto.setPermissionId(permissionId);
        dto.setIsEnable(isEnable);
        return dto;
    }

    private MemberPermissionResDto memberPermission(Long lineId, Integer permissionId, Boolean isEnable) {
        return MemberPermissionResDto.builder()
                .familyId(1L)
                .lineId(lineId)
                .permissionId(permissionId)
                .permissionTitle("perm-" + permissionId)
                .isEnable(isEnable)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
