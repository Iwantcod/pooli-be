package com.pooli.permission.service;

import com.pooli.permission.domain.dto.request.MemberPermissionUpsertReqDto;
import com.pooli.permission.domain.dto.response.MemberPermissionListResDto;
import com.pooli.permission.domain.dto.response.MemberPermissionResDto;

public interface MemberPermissionService {

    // 내 권한 상태 조회
    MemberPermissionListResDto getMyPermissions(Long lineId);

    // 구성원 권한 목록 조회
    MemberPermissionListResDto getMemberPermissions(Long familyId, Long lineId);

    // 구성원 권한 변경
    MemberPermissionResDto updateMemberPermission(Long familyId, Long lineId, MemberPermissionUpsertReqDto reqDto);
}
