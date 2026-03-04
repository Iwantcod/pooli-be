package com.pooli.permission.service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.permission.domain.dto.request.PermissionReqDto;
import com.pooli.permission.domain.dto.response.PermissionListResDto;
import com.pooli.permission.domain.dto.response.PermissionResDto;
import com.pooli.permission.domain.entity.Permission;
import com.pooli.permission.exception.PermissionErrorCode;
import com.pooli.permission.mapper.PermissionMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final PermissionMapper permissionMapper;

    // 권한 목록 조회
    @Override
    @Transactional(readOnly = true)
    public PermissionListResDto getPermissions() {
        List<PermissionResDto> permissions = permissionMapper.findAll().stream()
                .map(this::toResDto)
                .toList();
        return PermissionListResDto.builder()
                .permissions(permissions)
                .build();
    }

    // 권한 생성
    @Override
    @Transactional
    public PermissionResDto createPermission(PermissionReqDto reqDto) {
        String title = reqDto.getPermissionTitle().trim();

        if (permissionMapper.existsByPermissionTitle(title)) {
            throw new ApplicationException(PermissionErrorCode.DUPLICATE_PERMISSION_TITLE);
        }

        Permission permission = Permission.builder()
                .permissionTitle(title)
                .build();
        permissionMapper.insert(permission);

        return permissionMapper.findById(permission.getPermissionId())
                .map(this::toResDto)
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.PERMISSION_NOT_FOUND));
    }

    // 권한 이름 수정
    @Override
    @Transactional
    public PermissionResDto updatePermissionTitle(Integer permissionId, PermissionReqDto reqDto) {
        String title = reqDto.getPermissionTitle().trim();

        permissionMapper.findById(permissionId)
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.PERMISSION_NOT_FOUND));

        if (permissionMapper.existsByPermissionTitleExcludingId(title, permissionId)) {
            throw new ApplicationException(PermissionErrorCode.DUPLICATE_PERMISSION_TITLE);
        }

        permissionMapper.updateTitle(Permission.builder()
                .permissionId(permissionId)
                .permissionTitle(title)
                .build());

        return permissionMapper.findById(permissionId)
                .map(this::toResDto)
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.PERMISSION_NOT_FOUND));
    }

    // 권한 삭제 (soft delete)
    @Override
    @Transactional
    public void deletePermission(Integer permissionId) {
        permissionMapper.findById(permissionId)
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.PERMISSION_NOT_FOUND));
        permissionMapper.softDelete(permissionId);
    }

    private PermissionResDto toResDto(Permission permission) {
        return PermissionResDto.builder()
                .permissionId(permission.getPermissionId())
                .permissionTitle(permission.getPermissionTitle())
                .createdAt(permission.getCreatedAt())
                .deletedAt(permission.getDeletedAt())
                .build();
    }
}
