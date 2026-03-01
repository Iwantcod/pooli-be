package com.pooli.permission.service;

import com.pooli.permission.domain.dto.request.PermissionReqDto;
import com.pooli.permission.domain.dto.response.PermissionListResDto;
import com.pooli.permission.domain.dto.response.PermissionResDto;

public interface PermissionService {

    // 권한 목록 조회
    PermissionListResDto getPermissions();

    // 권한 생성
    PermissionResDto createPermission(PermissionReqDto reqDto);

    // 권한 이름 수정
    PermissionResDto updatePermissionTitle(Integer permissionId, PermissionReqDto reqDto);

    // 권한 삭제 (soft delete)
    void deletePermission(Integer permissionId);
}
