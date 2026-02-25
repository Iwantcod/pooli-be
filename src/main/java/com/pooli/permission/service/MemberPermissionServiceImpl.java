package com.pooli.permission.service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.permission.domain.dto.request.MemberPermissionUpsertReqDto;
import com.pooli.permission.domain.dto.response.MemberPermissionListResDto;
import com.pooli.permission.domain.dto.response.MemberPermissionResDto;
import com.pooli.permission.domain.entity.PermissionLine;
import com.pooli.permission.exception.PermissionErrorCode;
import com.pooli.permission.mapper.PermissionLineMapper;
import com.pooli.permission.mapper.PermissionMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberPermissionServiceImpl implements MemberPermissionService {

    private final PermissionLineMapper permissionLineMapper;
    private final PermissionMapper permissionMapper;

    // 내 권한 상태 조회
    @Override
    @Transactional(readOnly = true)
    public MemberPermissionListResDto getMyPermissions(Long lineId) {
        List<MemberPermissionResDto> permissions = permissionLineMapper.findByLineId(lineId);
        return MemberPermissionListResDto.builder()
                .memberPermissions(permissions)
                .build();
    }

    //  구성원 권한 목록 조회
    @Override
    @Transactional(readOnly = true)
    public MemberPermissionListResDto getMemberPermissions(Long familyId, Long lineId) {
        List<MemberPermissionResDto> permissions = permissionLineMapper.findByFamilyIdAndLineId(familyId, lineId);
        return MemberPermissionListResDto.builder()
                .memberPermissions(permissions)
                .build();
    }

    //  구성원 권한 변경
    @Override
    @Transactional
    public MemberPermissionResDto updateMemberPermission(Long familyId, Long lineId, MemberPermissionUpsertReqDto reqDto) {
        permissionMapper.findById(reqDto.getPermissionId())
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.PERMISSION_NOT_FOUND));

        permissionLineMapper.upsert(PermissionLine.builder()
                .lineId(lineId)
                .permissionId(reqDto.getPermissionId())
                .isEnable(reqDto.getIsEnable())
                .build());

        return permissionLineMapper.findByFamilyIdAndLineId(familyId, lineId).stream()
                .filter(p -> p.getPermissionId().equals(reqDto.getPermissionId()))
                .findFirst()
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.MEMBER_PERMISSION_NOT_FOUND));
    }
}
