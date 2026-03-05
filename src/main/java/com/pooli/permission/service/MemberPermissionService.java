package com.pooli.permission.service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.permission.domain.dto.request.MemberPermissionBulkUpsertReqDto;
import com.pooli.permission.domain.dto.request.MemberPermissionUpsertReqDto;
import com.pooli.permission.domain.dto.response.MemberPermissionListResDto;
import com.pooli.permission.domain.dto.response.MemberPermissionResDto;
import java.util.List;

public interface MemberPermissionService {

    // 내 권한 상태 조회
    MemberPermissionListResDto getMyPermissions(Long lineId);

    // 구성원 권한 목록 조회
    MemberPermissionListResDto getMemberPermissions(Long lineId, AuthUserDetails userDetails);

    // 가족 전체 구성원 권한 목록 조회
    MemberPermissionListResDto getFamilyMemberPermissions(Long lineId, AuthUserDetails userDetails);

    // 구성원 권한 변경
    MemberPermissionResDto updateMemberPermission(Long familyId, Long lineId, MemberPermissionUpsertReqDto reqDto, AuthUserDetails userDetails);

    // 구성원 권한 일괄 변경
    MemberPermissionListResDto bulkUpdateMemberPermissions(Long lineId, List<MemberPermissionBulkUpsertReqDto> reqList, AuthUserDetails userDetails);
}
