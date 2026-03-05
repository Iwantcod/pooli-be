package com.pooli.permission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pooli.common.exception.ApplicationException;
import com.pooli.permission.domain.dto.request.PermissionReqDto;
import com.pooli.permission.domain.dto.response.PermissionListResDto;
import com.pooli.permission.domain.dto.response.PermissionResDto;
import com.pooli.permission.domain.entity.Permission;
import com.pooli.permission.exception.PermissionErrorCode;
import com.pooli.permission.mapper.PermissionMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PermissionServiceImplTest {

    @Mock
    private PermissionMapper permissionMapper;

    @InjectMocks
    private PermissionServiceImpl permissionService;

    @Test
    @DisplayName("권한 목록 조회 시 엔티티를 응답 DTO로 매핑한다")
    void getPermissions_success_returnsMappedList() {
        Permission p1 = permission(1, "데이터 차단");
        Permission p2 = permission(2, "앱 차단");
        when(permissionMapper.findAll()).thenReturn(List.of(p1, p2));

        PermissionListResDto result = permissionService.getPermissions();

        assertThat(result.getPermissions()).hasSize(2);
        assertThat(result.getPermissions())
                .extracting(PermissionResDto::getPermissionId, PermissionResDto::getPermissionTitle)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "데이터 차단"),
                        org.assertj.core.groups.Tuple.tuple(2, "앱 차단")
                );
    }

    @Test
    @DisplayName("권한 생성 시 동일 이름이 이미 존재하면 PERMISSION-4900을 반환한다")
    void createPermission_duplicateTitle_throwsDuplicatePermissionTitle() {
        PermissionReqDto req = req("  데이터 차단  ");
        when(permissionMapper.existsByPermissionTitle("데이터 차단")).thenReturn(true);

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> permissionService.createPermission(req)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.DUPLICATE_PERMISSION_TITLE);
        verify(permissionMapper, never()).insert(any());
    }

    @Test
    @DisplayName("권한 생성 성공 시 trim 된 제목으로 저장 후 결과를 반환한다")
    void createPermission_success_trimsTitleAndReturnsCreatedPermission() {
        PermissionReqDto req = req("  데이터 차단  ");
        when(permissionMapper.existsByPermissionTitle("데이터 차단")).thenReturn(false);
        doAnswer(invocation -> {
            Permission inserted = invocation.getArgument(0);
            ReflectionTestUtils.setField(inserted, "permissionId", 11);
            ReflectionTestUtils.setField(inserted, "createdAt", LocalDateTime.of(2026, 3, 5, 10, 0));
            return null;
        }).when(permissionMapper).insert(any(Permission.class));
        when(permissionMapper.findById(11)).thenReturn(Optional.of(permission(11, "데이터 차단")));

        PermissionResDto result = permissionService.createPermission(req);

        ArgumentCaptor<Permission> captor = ArgumentCaptor.forClass(Permission.class);
        verify(permissionMapper).insert(captor.capture());
        assertThat(captor.getValue().getPermissionTitle()).isEqualTo("데이터 차단");
        assertThat(result.getPermissionId()).isEqualTo(11);
        assertThat(result.getPermissionTitle()).isEqualTo("데이터 차단");
    }

    @Test
    @DisplayName("권한 생성 후 재조회가 안 되면 PERMISSION-4400을 반환한다")
    void createPermission_createdButNotFound_throwsPermissionNotFound() {
        PermissionReqDto req = req("권한A");
        when(permissionMapper.existsByPermissionTitle("권한A")).thenReturn(false);
        doAnswer(invocation -> {
            Permission inserted = invocation.getArgument(0);
            ReflectionTestUtils.setField(inserted, "permissionId", 12);
            return null;
        }).when(permissionMapper).insert(any(Permission.class));
        when(permissionMapper.findById(12)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> permissionService.createPermission(req)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.PERMISSION_NOT_FOUND);
    }

    @Test
    @DisplayName("권한 이름 수정 시 대상 권한이 없으면 PERMISSION-4400을 반환한다")
    void updatePermissionTitle_targetMissing_throwsPermissionNotFound() {
        PermissionReqDto req = req("수정 권한");
        when(permissionMapper.findById(1)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> permissionService.updatePermissionTitle(1, req)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.PERMISSION_NOT_FOUND);
        verify(permissionMapper, never()).updateTitle(any());
    }

    @Test
    @DisplayName("권한 이름 수정 시 중복 제목이면 PERMISSION-4900을 반환한다")
    void updatePermissionTitle_duplicateTitle_throwsDuplicatePermissionTitle() {
        PermissionReqDto req = req("  데이터 차단  ");
        when(permissionMapper.findById(1)).thenReturn(Optional.of(permission(1, "기존")));
        when(permissionMapper.existsByPermissionTitleExcludingId("데이터 차단", 1)).thenReturn(true);

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> permissionService.updatePermissionTitle(1, req)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.DUPLICATE_PERMISSION_TITLE);
        verify(permissionMapper, never()).updateTitle(any());
    }

    @Test
    @DisplayName("권한 이름 수정 성공 시 trim 된 제목으로 저장 후 결과를 반환한다")
    void updatePermissionTitle_success_updatesAndReturnsPermission() {
        PermissionReqDto req = req("  변경 제목  ");
        when(permissionMapper.findById(1))
                .thenReturn(Optional.of(permission(1, "기존 제목")))
                .thenReturn(Optional.of(permission(1, "변경 제목")));
        when(permissionMapper.existsByPermissionTitleExcludingId("변경 제목", 1)).thenReturn(false);

        PermissionResDto result = permissionService.updatePermissionTitle(1, req);

        ArgumentCaptor<Permission> captor = ArgumentCaptor.forClass(Permission.class);
        verify(permissionMapper).updateTitle(captor.capture());
        assertThat(captor.getValue().getPermissionId()).isEqualTo(1);
        assertThat(captor.getValue().getPermissionTitle()).isEqualTo("변경 제목");
        assertThat(result.getPermissionId()).isEqualTo(1);
        assertThat(result.getPermissionTitle()).isEqualTo("변경 제목");
    }

    @Test
    @DisplayName("권한 이름 수정 후 재조회가 안 되면 PERMISSION-4400을 반환한다")
    void updatePermissionTitle_afterUpdateNotFound_throwsPermissionNotFound() {
        PermissionReqDto req = req("변경 제목");
        when(permissionMapper.findById(1))
                .thenReturn(Optional.of(permission(1, "기존 제목")))
                .thenReturn(Optional.empty());
        when(permissionMapper.existsByPermissionTitleExcludingId("변경 제목", 1)).thenReturn(false);

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> permissionService.updatePermissionTitle(1, req)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.PERMISSION_NOT_FOUND);
    }

    @Test
    @DisplayName("권한 삭제 시 대상 권한이 없으면 PERMISSION-4400을 반환한다")
    void deletePermission_targetMissing_throwsPermissionNotFound() {
        when(permissionMapper.findById(1)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> permissionService.deletePermission(1)
        );

        assertThat(ex.getErrorCode()).isEqualTo(PermissionErrorCode.PERMISSION_NOT_FOUND);
        verify(permissionMapper, never()).softDelete(anyInt());
    }

    @Test
    @DisplayName("권한 삭제 성공 시 softDelete를 호출한다")
    void deletePermission_success_callsSoftDelete() {
        when(permissionMapper.findById(1)).thenReturn(Optional.of(permission(1, "삭제 대상")));

        permissionService.deletePermission(1);

        verify(permissionMapper).softDelete(eq(1));
    }

    private PermissionReqDto req(String title) {
        PermissionReqDto req = new PermissionReqDto();
        req.setPermissionTitle(title);
        return req;
    }

    private Permission permission(Integer permissionId, String title) {
        return Permission.builder()
                .permissionId(permissionId)
                .permissionTitle(title)
                .createdAt(LocalDateTime.of(2026, 3, 5, 9, 0))
                .build();
    }
}
