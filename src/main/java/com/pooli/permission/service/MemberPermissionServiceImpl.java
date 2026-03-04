package com.pooli.permission.service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.family.domain.enums.FamilyRole;
import com.pooli.permission.domain.dto.request.MemberPermissionBulkUpsertReqDto;
import com.pooli.permission.domain.dto.request.MemberPermissionUpsertReqDto;
import com.pooli.permission.domain.dto.response.MemberPermissionListResDto;
import com.pooli.permission.domain.dto.response.MemberPermissionResDto;
import com.pooli.permission.domain.entity.PermissionLine;
import com.pooli.permission.exception.PermissionErrorCode;
import com.pooli.permission.mapper.FamilyLineMapper;
import com.pooli.permission.mapper.PermissionLineMapper;
import com.pooli.permission.mapper.PermissionMapper;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberPermissionServiceImpl implements MemberPermissionService {

    private final FamilyLineMapper familyLineMapper;
    private final PermissionLineMapper permissionLineMapper;
    private final PermissionMapper permissionMapper;

    // 내 권한 상태 조회
    @Override
    @Transactional(readOnly = true)
    public MemberPermissionListResDto getMyPermissions(Long lineId) {
        familyLineMapper.findByLineId(lineId)
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.LINE_NOT_FOUND));

        List<MemberPermissionResDto> permissions = permissionLineMapper.findByLineId(lineId);
        return MemberPermissionListResDto.builder()
                .memberPermissions(permissions)
                .build();
    }

    // 가족 전체 구성원 권한 목록 조회
    @Override
    @Transactional(readOnly = true)
    public MemberPermissionListResDto getFamilyMemberPermissions(Long lineId, AuthUserDetails userDetails) {
        Long familyId = familyLineMapper.findByLineId(lineId)
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.LINE_NOT_FOUND))
                .getFamilyId();

        validateFamilyOwnership(familyId, userDetails);

        List<MemberPermissionResDto> permissions = permissionLineMapper.findByFamilyId(familyId);
        return MemberPermissionListResDto.builder()
                .memberPermissions(permissions)
                .build();
    }

    //  구성원 권한 목록 조회
    @Override
    @Transactional(readOnly = true)
    public MemberPermissionListResDto getMemberPermissions(Long familyId, Long lineId, AuthUserDetails userDetails) {
        validateFamilyOwnership(familyId, userDetails);

        familyLineMapper.findByFamilyIdAndLineId(familyId, lineId)
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.FAMILY_LINE_MAPPING_NOT_FOUND));

        List<MemberPermissionResDto> permissions = permissionLineMapper.findByFamilyIdAndLineId(familyId, lineId);
        return MemberPermissionListResDto.builder()
                .memberPermissions(permissions)
                .build();
    }

    //  구성원 권한 변경
    @Override
    @Transactional
    public MemberPermissionResDto updateMemberPermission(Long familyId, Long lineId, MemberPermissionUpsertReqDto reqDto, AuthUserDetails userDetails) {
        validateFamilyOwnership(familyId, userDetails);

        // upsert 전 lineId가 해당 familyId 소속인지 검증 (타 가족 lineId 쓰기 방지)
        familyLineMapper.findByFamilyIdAndLineId(familyId, lineId)
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.FAMILY_LINE_MAPPING_NOT_FOUND));

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
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.MEMBER_PERMISSION_APPLY_ERROR));
    }

    // 구성원 권한 일괄 변경
    @Override
    @Transactional
    public MemberPermissionListResDto bulkUpdateMemberPermissions(Long familyId, List<MemberPermissionBulkUpsertReqDto> reqList, AuthUserDetails userDetails) {
        validateFamilyOwnership(familyId, userDetails);

        // Fix2: 빈 배열이면 DB 호출 없이 현재 상태 반환 (SQL foreach 오류 방지)
        if (reqList.isEmpty()) {
            return MemberPermissionListResDto.builder()
                    .memberPermissions(permissionLineMapper.findByFamilyId(familyId))
                    .build();
        }

        // Fix5: 요청된 permissionId가 실제 존재하는지 검증
        Set<Integer> permissionIds = reqList.stream()
                .map(MemberPermissionBulkUpsertReqDto::getPermissionId)
                .collect(Collectors.toSet());
        for (Integer permissionId : permissionIds) {
            permissionMapper.findById(permissionId)
                    .orElseThrow(() -> new ApplicationException(PermissionErrorCode.PERMISSION_NOT_FOUND));
        }

        // Fix1: 요청된 lineId가 모두 해당 familyId 소속인지 검증
        Set<Long> lineIds = reqList.stream()
                .map(MemberPermissionBulkUpsertReqDto::getLineId)
                .collect(Collectors.toSet());
        for (Long lineId : lineIds) {
            familyLineMapper.findByFamilyIdAndLineId(familyId, lineId)
                    .orElseThrow(() -> new ApplicationException(PermissionErrorCode.FAMILY_LINE_MAPPING_NOT_FOUND));
        }

        permissionLineMapper.bulkUpsert(reqList);

        List<MemberPermissionResDto> permissions = permissionLineMapper.findByFamilyId(familyId);
        return MemberPermissionListResDto.builder()
                .memberPermissions(permissions)
                .build();
    }

    // ADMIN이면 통과, FAMILY_OWNER면 요청한 familyId에 본인이 OWNER로 속해있는지 DB 확인
    private void validateFamilyOwnership(Long familyId, AuthUserDetails userDetails) {
        boolean isAdmin = userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (isAdmin) {
            return;
        }

        familyLineMapper.findByFamilyIdAndLineId(familyId, userDetails.getLineId())
                .filter(fl -> fl.getRole() == FamilyRole.OWNER)
                .orElseThrow(() -> new ApplicationException(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN));
    }
}
