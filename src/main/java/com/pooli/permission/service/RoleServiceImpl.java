package com.pooli.permission.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.common.exception.ApplicationException;
import com.pooli.family.domain.entity.FamilyLine;
import com.pooli.family.domain.enums.FamilyRole;
import com.pooli.line.domain.entity.Line;
import com.pooli.permission.domain.dto.response.RepresentativeRoleTransferResDto;
import com.pooli.permission.exception.PermissionErrorCode;
import com.pooli.permission.mapper.FamilyLineMapper;
import com.pooli.permission.mapper.LineUserPermissionMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final LineUserPermissionMapper lineUserPermissionMapper;
    private final FamilyLineMapper familyLineMapper;

    @Override
    @Transactional
    public RepresentativeRoleTransferResDto transferRepresentativeRole(Long currentUserId, Long changeUserId) {

        if (currentUserId.equals(changeUserId)) {
            throw new ApplicationException(PermissionErrorCode.ROLE_TRANSFER_SELF);
        }

        Line currentLine = lineUserPermissionMapper.findMainLineByUserId(currentUserId)
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.ROLE_TRANSFER_SOURCE_NOT_FOUND));

        Line targetLine = lineUserPermissionMapper.findMainLineByUserId(changeUserId)
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.ROLE_TRANSFER_TARGET_NOT_FOUND));

        FamilyLine currentFamilyLine = familyLineMapper.findByLineId(currentLine.getLineId())
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.ROLE_TRANSFER_SOURCE_NOT_FOUND));

        FamilyLine targetFamilyLine = familyLineMapper.findByLineId(targetLine.getLineId())
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.ROLE_TRANSFER_TARGET_NOT_FOUND));

        if (!currentFamilyLine.getFamilyId().equals(targetFamilyLine.getFamilyId())) {
            throw new ApplicationException(PermissionErrorCode.ROLE_TRANSFER_DIFFERENT_FAMILY);
        }

        if (targetFamilyLine.getRole() == FamilyRole.OWNER) {
            throw new ApplicationException(PermissionErrorCode.ROLE_TRANSFER_TARGET_ALREADY_REPRESENTATIVE);
        }

        familyLineMapper.updateRole(FamilyLine.builder()
                .familyId(currentFamilyLine.getFamilyId())
                .lineId(currentFamilyLine.getLineId())
                .role(FamilyRole.MEMBER)
                .build());

        familyLineMapper.updateRole(FamilyLine.builder()
                .familyId(targetFamilyLine.getFamilyId())
                .lineId(targetFamilyLine.getLineId())
                .role(FamilyRole.OWNER)
                .build());

        return RepresentativeRoleTransferResDto.builder()
                .currentOwnerUserId(currentUserId)
                .changeOwnerUserId(changeUserId)
                .build();
    }
}
